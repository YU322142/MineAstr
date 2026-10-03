package com.mineastr;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** A complete, persistent binding snapshot; failed loads never grant access. */
final class BindingWhitelistStore {
    static final int MAX_ENTRIES = 4096;
    record Entry(String playerName, String ownerKey, String ownerDisplay, UUID uuid) {}
    private record Snapshot(int version, String scope, List<Entry> entries) {}
    private final Path file;
    private final String scope;
    private volatile Map<String, Entry> entries = Map.of();

    BindingWhitelistStore(Path file, String scope) { this.file = file; this.scope = scope; }

    synchronized void load() throws IOException {
        entries = Map.of();
        if (!Files.exists(file)) return;
        if (Files.size(file) > 2_000_000) throw new IOException("binding snapshot byte limit");
        try {
            Snapshot snapshot = new Gson().fromJson(Files.readString(file), Snapshot.class);
            if (snapshot == null || snapshot.version != 1 || !scope.equals(snapshot.scope)) return;
            entries = validate(snapshot.entries);
        } catch (RuntimeException error) { throw new IOException("invalid binding snapshot", error); }
    }

    synchronized void replace(List<Entry> replacement) throws IOException {
        Map<String, Entry> validated = validate(replacement);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), "bindings-", ".tmp");
        try {
            Files.writeString(temporary, new Gson().toJson(new Snapshot(1, scope, List.copyOf(validated.values()))));
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            entries = validated;
        } finally { Files.deleteIfExists(temporary); }
    }

    boolean allows(String name) { return name != null && entries.containsKey(name.toLowerCase(Locale.ROOT)); }
    void invalidate() { entries = Map.of(); }
    List<Entry> snapshot() { return List.copyOf(entries.values()); }

    private static Map<String, Entry> validate(List<Entry> replacement) throws IOException {
        if (replacement == null || replacement.size() > MAX_ENTRIES) throw new IOException("binding snapshot entry limit");
        Map<String, Entry> result = new LinkedHashMap<>();
        for (Entry entry : replacement) {
            if (entry == null || entry.playerName == null || (entry.playerName.isBlank() || entry.playerName.length() > 64 || entry.playerName.codePoints().anyMatch(Character::isISOControl))
                    || entry.ownerKey == null || entry.ownerKey.isBlank() || entry.ownerKey.length() > 256
                    || entry.ownerDisplay == null || entry.ownerDisplay.length() > 128 || entry.uuid == null
                    || result.putIfAbsent(entry.playerName.toLowerCase(Locale.ROOT), entry) != null)
                throw new IOException("invalid or duplicate binding identity");
        }
        return Map.copyOf(result);
    }
}
