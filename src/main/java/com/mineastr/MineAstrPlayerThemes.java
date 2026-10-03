package com.mineastr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

/** Server-owned themes. Only the authenticated player can update their own UUID. */
public final class MineAstrPlayerThemes extends SavedData {
    private static final String FILE = "mineastr_player_themes";
    private static final Factory<MineAstrPlayerThemes> FACTORY = new Factory<>(MineAstrPlayerThemes::new, MineAstrPlayerThemes::load);
    private final Map<UUID, MineAstrPayloads.PlayerTheme> profiles = new LinkedHashMap<>();
    private final Map<ServerPlayer, Long> requests = new WeakHashMap<>();

    public static MineAstrPlayerThemes load(CompoundTag tag, HolderLookup.Provider registries) {
        var store = new MineAstrPlayerThemes();
        ListTag entries = tag.getList("players", Tag.TAG_COMPOUND);
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag entry = entries.getCompound(index);
            if (!entry.hasUUID("uuid")) continue;
            UUID id = entry.getUUID("uuid");
            String name = entry.getString("name");
            var profile = new MineAstrPayloads.PlayerTheme(id, name, entry.getInt("color"), entry.getInt("second"),
                    entry.getInt("third"), entry.getInt("count"), entry.getInt("period"));
            if (!name.isBlank() && name.length() <= 64 && profile.valid()) store.profiles.put(id, profile);
        }
        return store;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag entries = new ListTag();
        for (var profile : profiles.values()) {
            var entry = new CompoundTag();
            entry.putUUID("uuid", profile.id()); entry.putString("name", profile.name()); entry.putInt("color", profile.color());
            entry.putInt("second", profile.second()); entry.putInt("third", profile.third());
            entry.putInt("count", profile.count()); entry.putInt("period", profile.period());
            entries.add(entry);
        }
        tag.put("players", entries);
        return tag;
    }

    public static void receive(ServerPlayer player, MineAstrPayloads.ThemePreferences request) {
        var store = player.server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE);
        long now = System.nanoTime();
        Long last = store.requests.get(player);
        boolean first = last == null;
        if (!first && (!request.update() || now - last < 500_000_000L)) return;
        store.requests.put(player, now);
        var previous = store.profiles.get(player.getUUID());
        var requested = new MineAstrPayloads.PlayerTheme(player.getUUID(), player.getGameProfile().getName(),
                request.color(), request.second(), request.third(), request.count(), request.period());
        if ((request.update() || previous == null) && !requested.valid()) {
            if (first) store.sendSnapshot(player);
            else if (previous != null) MineAstrNetwork.sendThemePalette(player, new MineAstrPayloads.ThemePalette(false, java.util.List.of(previous)));
            return;
        }
        var profile = request.update() || previous == null ? requested : new MineAstrPayloads.PlayerTheme(requested.id(), requested.name(),
                previous.color(), previous.second(), previous.third(), previous.count(), previous.period());
        boolean changed = !profile.equals(previous);
        if (changed) {
            store.profiles.put(profile.id(), profile); store.setDirty();
            MineAstr.LOGGER.debug("MineAstr player theme updated: player={} color={} stops={}", profile.name(), MineAstrThemeColor.hex(profile.color()), profile.count());
        }
        if (first) store.sendSnapshot(player);
        else MineAstrNetwork.sendThemePalette(player, new MineAstrPayloads.ThemePalette(false, java.util.List.of(profile)));
        if (changed) for (var other : player.server.getPlayerList().getPlayers()) {
            if (other != player) MineAstrNetwork.sendThemePalette(other, new MineAstrPayloads.ThemePalette(false, java.util.List.of(profile)));
        }
    }
    private void sendSnapshot(ServerPlayer player) {
        var all = new ArrayList<>(profiles.values());
        if (all.isEmpty()) MineAstrNetwork.sendThemePalette(player, new MineAstrPayloads.ThemePalette(true, java.util.List.of()));
        for (int offset = 0; offset < all.size(); offset += 128)
            MineAstrNetwork.sendThemePalette(player, new MineAstrPayloads.ThemePalette(offset == 0,
                    all.subList(offset, Math.min(offset + 128, all.size()))));
    }
}
