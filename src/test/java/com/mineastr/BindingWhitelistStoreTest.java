package com.mineastr;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BindingWhitelistStoreTest {
    @TempDir Path directory;
    private BindingWhitelistStore.Entry entry(String name) {
        return new BindingWhitelistStore.Entry(name, "discord:42", "Owner", UUID.randomUUID());
    }
    @Test void persistedBindingsAllowOfflineLoginAfterRestartAndRevokeOnReplacement() throws IOException {
        Path file = directory.resolve("bindings.json");
        var original = new BindingWhitelistStore(file, "server-token-scope");
        original.replace(List.of(entry("Steve"), entry("Alex")));
        var restarted = new BindingWhitelistStore(file, "server-token-scope");
        restarted.load();
        assertTrue(restarted.allows("sTeVe")); assertFalse(restarted.allows("Unknown"));
        restarted.replace(List.of(entry("Alex")));
        assertFalse(restarted.allows("Steve")); assertTrue(restarted.allows("Alex"));
        var again = new BindingWhitelistStore(file, "server-token-scope"); again.load();
        assertFalse(again.allows("Steve"));
    }
    @Test void invalidOrDuplicateSnapshotPreservesPreviousCompleteList() throws IOException {
        var store = new BindingWhitelistStore(directory.resolve("bindings.json"), "scope");
        store.replace(List.of(entry("Steve")));
        assertThrows(IOException.class, () -> store.replace(List.of(entry("Alex"), entry("aLeX"))));
        assertTrue(store.allows("Steve")); assertFalse(store.allows("Alex"));
        assertThrows(IOException.class, () -> store.replace(List.of(new BindingWhitelistStore.Entry("Alex", "", "", UUID.randomUUID()))));
        assertTrue(store.allows("Steve"));
    }
    @Test void changedBridgeScopeAndCorruptedFileNeverGrantCachedAccess() throws IOException {
        Path file = directory.resolve("bindings.json");
        var store = new BindingWhitelistStore(file, "old"); store.replace(List.of(entry("Steve")));
        var changed = new BindingWhitelistStore(file, "new"); changed.load();
        assertFalse(changed.allows("Steve"));
        Files.writeString(file, "{broken");
        assertThrows(IOException.class, store::load); assertFalse(store.allows("Steve"));
    }
}
