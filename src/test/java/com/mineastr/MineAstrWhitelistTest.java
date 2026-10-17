package com.mineastr;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.authlib.GameProfile;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Collection;
import java.util.UUID;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.server.players.UserWhiteListEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MineAstrWhitelistTest {
    @TempDir Path directory;

    private static final class CountingWhitelist extends UserWhiteList {
        int saves;
        int scans;
        boolean failSave;

        CountingWhitelist(Path path) { super(path.toFile()); }
        @Override public void save() throws IOException {
            saves++;
            if (failSave) throw new IOException("disk unavailable");
            super.save();
        }
        @Override public Collection<UserWhiteListEntry> getEntries() {
            scans++;
            return super.getEntries();
        }
    }

    private GameProfile profile(String name) { return new GameProfile(UUID.randomUUID(), name); }

    @Test
    void thousandsOfUnchangedGrantsUseOneIndexAndZeroFileWrites() {
        var list = new CountingWhitelist(directory.resolve("whitelist.json"));
        var profiles = new java.util.ArrayList<GameProfile>();
        // Populate using the production API, then measure a periodic reconciliation.
        for (int index = 0; index < 256; index++) {
            GameProfile profile = profile("Player" + index);
            profiles.add(profile);
            list.add(new UserWhiteListEntry(profile));
        }
        list.saves = 0;
        list.scans = 0;
        var sync = new MineAstrWhitelist(list);
        for (int repeat = 0; repeat < 16; repeat++) {
            for (GameProfile profile : profiles) {
                var result = sync.update(profile, true, true);
                assertTrue(result.ok());
                assertFalse(result.changed());
            }
        }
        assertEquals(0, list.saves);
        assertEquals(1, list.scans);
    }

    @Test
    void conflictingUuidIsRemovedWithoutTouchingUnrelatedManualGrants() {
        var list = new CountingWhitelist(directory.resolve("whitelist.json"));
        GameProfile old = profile("Steve"), current = profile("sTeVe"), manual = profile("Manual");
        list.add(new UserWhiteListEntry(old));
        list.add(new UserWhiteListEntry(manual));
        var sync = new MineAstrWhitelist(list);
        assertEquals(old, sync.find("STEVE"));
        assertTrue(sync.update(current, true, true).ok());
        assertFalse(list.isWhiteListed(old));
        assertTrue(list.isWhiteListed(current));
        assertTrue(list.isWhiteListed(manual));
        assertEquals(current, sync.find("Steve"));
        assertTrue(sync.update(current, false, false).ok());
        assertFalse(list.isWhiteListed(current));
        assertTrue(list.isWhiteListed(manual));
    }

    @Test
    void diskFailureMustNotBeReportedAsSuccessfulWhitelistSync() {
        var list = new CountingWhitelist(directory.resolve("whitelist.json"));
        list.failSave = true;
        GameProfile alex = profile("Alex");
        var result = new MineAstrWhitelist(list).update(alex, true, true);
        assertFalse(result.ok());
        assertEquals("whitelist_save_failed", result.error());
        list.failSave = false;
        list.saves = 0;
        var retry = new MineAstrWhitelist(list).update(alex, true, true);
        assertTrue(retry.ok());
        assertFalse(retry.changed());
        assertEquals(1, list.saves);
    }

    @Test
    void bulkCommitPersistsAllChangesAndPreservesManualEditsMadeAfterQueueing() throws IOException {
        Path file = directory.resolve("whitelist.json");
        var list = new CountingWhitelist(file);
        GameProfile old = profile("Steve"), current = profile("sTeVe"), revoked = profile("Revoked");
        list.add(new UserWhiteListEntry(old));
        list.add(new UserWhiteListEntry(revoked));
        var sync = new MineAstrWhitelist(list);
        GameProfile manual = profile("ManualAddedWhileQueued");
        list.add(new UserWhiteListEntry(manual));
        var grants = new java.util.ArrayList<GameProfile>();
        grants.add(current);
        for (int index = 0; index < 512; index++) grants.add(profile("Player" + index));
        list.saves = 0;

        var result = sync.reconcile(grants, List.of(revoked));
        assertTrue(result.ok());
        assertTrue(result.changed());
        // The bulk path bypasses vanilla's per-entry autosave, using one staged flush.
        assertEquals(0, list.saves);
        assertFalse(list.isWhiteListed(old));
        assertFalse(list.isWhiteListed(revoked));
        assertTrue(list.isWhiteListed(manual));
        var restarted = new UserWhiteList(file.toFile());
        restarted.load();
        assertEquals(514, restarted.getEntries().size());
        assertTrue(restarted.isWhiteListed(manual));
        for (GameProfile grant : grants) assertTrue(restarted.isWhiteListed(grant));

        FileTime sentinel = FileTime.fromMillis(1_600_000_000_000L);
        Files.setLastModifiedTime(file, sentinel);
        assertFalse(sync.reconcile(grants, List.of(revoked)).changed());
        assertEquals(sentinel, Files.getLastModifiedTime(file));
        try (var files = Files.list(directory)) {
            assertEquals(List.of(file), files.toList());
        }
    }

    @Test
    void failedAtomicReplacementLeavesTheLiveAuthorizationUnchanged() throws IOException {
        Path file = directory.resolve("whitelist.json");
        var list = new UserWhiteList(file.toFile());
        GameProfile existing = profile("Steve"), added = profile("Alex");
        list.add(new UserWhiteListEntry(existing));
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("prevent-replacement"), "disk failure fixture");

        var result = new MineAstrWhitelist(list).reconcile(List.of(added), List.of(existing));
        assertFalse(result.ok());
        assertTrue(list.isWhiteListed(existing));
        assertFalse(list.isWhiteListed(added));
        try (var files = Files.list(directory)) {
            assertEquals(List.of(file), files.toList());
        }
    }
}
