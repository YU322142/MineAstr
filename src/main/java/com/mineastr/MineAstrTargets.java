package com.mineastr;

import com.google.gson.JsonObject;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;

import static com.mineastr.MineAstrProtocolValues.getString;
import static com.mineastr.MineAstrProtocolValues.trimFlatContent;

/**
 * 在线玩家和已加载维度的选择；调用者必须在服务器线程。
 * Resolve online players and dimensions; callers must remain on the server thread.
 */
final class MineAstrTargets {
    private MineAstrTargets() {}

    static ServerPlayer findExactPlayer(MinecraftServer currentServer, String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return null;
        }
        for (ServerPlayer player : currentServer.getPlayerList().getPlayers()) {
            if (player.getGameProfile().getName().equalsIgnoreCase(playerName)) {
                return player;
            }
        }
        return null;
    }

    static ServerPlayer findTargetPlayer(MinecraftServer currentServer, JsonObject payload) {
        String playerUuid = getString(payload, "player_uuid", "").strip();
        String playerName = getString(payload, "player_name", "").strip();
        PlayerList playerList = currentServer.getPlayerList();
        if (!playerUuid.isEmpty()) {
            try {
                ServerPlayer player = playerList.getPlayer(UUID.fromString(playerUuid));
                if (player != null) {
                    return player;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (!playerName.isEmpty()) {
            return findExactPlayer(currentServer, playerName);
        }
        if (playerUuid.isEmpty() && playerName.isEmpty() && playerList.getPlayerCount() == 1) {
            return playerList.getPlayers().getFirst();
        }
        return null;
    }

    static ServerLevel findTargetLevel(MinecraftServer currentServer, JsonObject payload) {
        String dimensionText = trimFlatContent(getString(payload, "dimension", "minecraft:overworld"), 128);
        ResourceLocation location = ResourceLocation.tryParse(dimensionText);
        if (location == null) {
            return null;
        }
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, location);
        return currentServer.getLevel(key);
    }

    static boolean hasCoordinates(JsonObject payload) {
        return payload.has("x") && payload.has("y") && payload.has("z");
    }
}
