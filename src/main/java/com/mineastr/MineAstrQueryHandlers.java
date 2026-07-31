package com.mineastr;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.management.ManagementFactory;
import java.net.http.WebSocket;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

public final class MineAstrQueryHandlers {
    private MineAstrQueryHandlers() {
    }

    public static void dispatch(WebSocket socket, JsonObject payload, MinecraftServer server, ScheduledExecutorService executor) {
        String query = MineAstrProtocol.trimFlatContent(MineAstrProtocol.getString(payload, "query", ""), 64).toLowerCase(Locale.ROOT);
        String messageId = MineAstrProtocol.trimFlatContent(MineAstrProtocol.getString(payload, "message_id", UUID.randomUUID().toString()), 64);

        if (server == null) {
            MineAstrProtocol.sendQueryError(socket, messageId, query, "Minecraft 服务器尚未启动。");
            return;
        }

        server.execute(() -> {
            try {
                switch (query) {
                    case "status" -> MineAstrProtocol.sendQueryResult(socket, messageId, query, buildStatusData(server, MineAstr.bridge().getStartedAtMs()));
                    case "players" -> MineAstrProtocol.sendQueryResult(socket, messageId, query, buildPlayersData(server));
                    case "player_state" -> handlePlayerStateQuery(socket, messageId, payload, server);
                    case "inventory" -> handleInventoryQuery(socket, messageId, payload, server);
                    case "nearby_entities" -> handleNearbyEntitiesQuery(socket, messageId, payload, server);
                    case "region_features" -> handleRegionQuery(socket, messageId, payload, server);
                    case "command" -> MineAstrCommands.handleCommandQuery(socket, messageId, payload, server);
                    case "screenshot" -> MineAstrScreenshots.handleScreenshotQuery(socket, messageId, payload, server, executor);
                    case "performance" -> MineAstrProtocol.sendQueryResult(socket, messageId, query, buildPerformanceData(server));
                    case "notify_player" -> handleNotifyPlayerQuery(socket, messageId, payload, server);
                    case "binding" -> MineAstrBindings.handleBindingQuery(socket, messageId, payload, server);
                    case "trusted_users" -> MineAstrCommands.handleTrustedUsersQuery(socket, messageId, payload);
                    default -> MineAstrProtocol.sendQueryError(socket, messageId, query, "不支持的查询类型：" + query);
                }
            } catch (RuntimeException exc) {
                MineAstr.LOGGER.warn("MineAstr 查询 {} 处理失败：{}", query, exc.getMessage());
                MineAstrProtocol.sendQueryError(socket, messageId, query, "查询处理失败：" + MineAstrProtocol.safeErrorMessage(exc));
            }
        });
    }

    private static void handlePlayerStateQuery(WebSocket socket, String messageId, JsonObject payload, MinecraftServer server) {
        if (!MineAstrConfig.ENABLE_PLAYER_STATE_TOOL.getAsBoolean()) {
            MineAstrProtocol.sendQueryError(socket, messageId, "player_state", "服务端已禁用玩家状态工具。");
            return;
        }
        ServerPlayer player = findTargetPlayer(server, payload);
        if (player == null) {
            MineAstrProtocol.sendQueryError(socket, messageId, "player_state", "未找到目标在线玩家。");
            return;
        }
        MineAstrProtocol.sendQueryResult(socket, messageId, "player_state", MineAstrTools.buildPlayerState(player));
    }

    private static void handleInventoryQuery(WebSocket socket, String messageId, JsonObject payload, MinecraftServer server) {
        if (!MineAstrConfig.ENABLE_INVENTORY_TOOL.getAsBoolean()) {
            MineAstrProtocol.sendQueryError(socket, messageId, "inventory", "服务端已禁用背包查询工具。");
            return;
        }
        ServerPlayer player = findTargetPlayer(server, payload);
        if (player == null) {
            MineAstrProtocol.sendQueryError(socket, messageId, "inventory", "未找到目标在线玩家。");
            return;
        }
        boolean includeEnderChest = MineAstrProtocol.getBoolean(payload, "include_ender_chest", false);
        MineAstrProtocol.sendQueryResult(socket, messageId, "inventory", MineAstrTools.buildInventory(player, includeEnderChest));
    }

    private static void handleNearbyEntitiesQuery(WebSocket socket, String messageId, JsonObject payload, MinecraftServer server) {
        if (!MineAstrConfig.ENABLE_NEARBY_ENTITIES_TOOL.getAsBoolean()) {
            MineAstrProtocol.sendQueryError(socket, messageId, "nearby_entities", "服务端已禁用附近实体工具。");
            return;
        }
        ServerPlayer player = findTargetPlayer(server, payload);
        if (player == null) {
            MineAstrProtocol.sendQueryError(socket, messageId, "nearby_entities", "未找到目标在线玩家。");
            return;
        }
        double radius = MineAstrProtocol.getDouble(payload, "radius", 12.0, 1.0, 32.0);
        MineAstrProtocol.sendQueryResult(socket, messageId, "nearby_entities", MineAstrTools.buildNearbyEntities(player, radius));
    }

    private static void handleRegionQuery(WebSocket socket, String messageId, JsonObject payload, MinecraftServer server) {
        if (!MineAstrConfig.ENABLE_REGION_TOOL.getAsBoolean()) {
            MineAstrProtocol.sendQueryError(socket, messageId, "region_features", "服务端已禁用区域特征工具。");
            return;
        }
        boolean coordinateMode = hasCoordinates(payload);
        ServerPlayer player = coordinateMode ? null : findTargetPlayer(server, payload);
        ServerLevel level;
        BlockPos fallbackCenter;
        if (player != null) {
            level = player.level();
            fallbackCenter = player.blockPosition();
        } else {
            level = findTargetLevel(server, payload);
            if (level == null || !coordinateMode) {
                MineAstrProtocol.sendQueryError(socket, messageId, "region_features", "请指定在线玩家，或提供有效的 dimension、x、y、z。");
                return;
            }
            fallbackCenter = new BlockPos(0, level.getSeaLevel(), 0);
        }

        int x = MineAstrProtocol.getInt(payload, "x", fallbackCenter.getX(), -30_000_000, 30_000_000);
        int y = MineAstrProtocol.getInt(payload, "y", fallbackCenter.getY(), level.getMinY(), level.getMaxY() - 1);
        int z = MineAstrProtocol.getInt(payload, "z", fallbackCenter.getZ(), -30_000_000, 30_000_000);
        int horizontalRadius = MineAstrProtocol.getInt(payload, "horizontal_radius", 8, 1, 24);
        int verticalRadius = MineAstrProtocol.getInt(payload, "vertical_radius", 6, 1, 16);
        long volume = (horizontalRadius * 2L + 1L) * (verticalRadius * 2L + 1L) * (horizontalRadius * 2L + 1L);
        int maxBlocks = MineAstrConfig.REGION_MAX_BLOCKS.getAsInt();
        if (volume > maxBlocks) {
            MineAstrProtocol.sendQueryError(socket, messageId, "region_features", "请求区域过大：" + volume + " 方块，服务端上限为 " + maxBlocks + "。");
            return;
        }
        BlockPos center = new BlockPos(x, y, z);
        if (!level.hasChunk(x >> 4, z >> 4)) {
            MineAstrProtocol.sendQueryError(socket, messageId, "region_features", "目标中心所在区块尚未加载；为避免卡服，MineAstr 不会强制加载新区块。");
            return;
        }
        MineAstrProtocol.sendQueryResult(socket, messageId, "region_features", MineAstrTools.analyzeRegion(level, center, horizontalRadius, verticalRadius));
    }

    private static void handleNotifyPlayerQuery(WebSocket socket, String messageId, JsonObject payload, MinecraftServer server) {
        if (!MineAstrConfig.ENABLE_PLAYER_NOTIFICATIONS.getAsBoolean()) {
            MineAstrProtocol.sendQueryError(socket, messageId, "notify_player", "player_notifications_disabled");
            return;
        }
        String requestedName = MineAstrProtocol.trimFlatContent(MineAstrProtocol.getString(payload, "player_name", ""), 64);
        ServerPlayer player = findExactPlayer(server, requestedName);
        if (player == null) {
            MineAstrProtocol.sendQueryError(socket, messageId, "notify_player", "player_not_online");
            return;
        }
        String sender = MineAstrProtocol.trimFlatContent(MineAstrProtocol.getString(payload, "sender_name", "AstrBot"), 64);
        String message = MineAstrProtocol.trimFlatContent(
                MineAstrProtocol.getString(payload, "message", ""), MineAstrConfig.NOTIFICATION_MAX_LENGTH.getAsInt());
        if (message.isBlank()) {
            MineAstrProtocol.sendQueryError(socket, messageId, "notify_player", "empty_message");
            return;
        }

        String fallback = player.clientInformation().language().toLowerCase(Locale.ROOT).startsWith("zh_")
                ? "[提醒/" + sender + "] " + message
                : "[Notice/" + sender + "] " + message;
        Component text = Component.translatableWithFallback(
                "message.mineastr.player_notification",
                fallback,
                sender,
                message);
        player.sendSystemMessage(text);
        if (MineAstrConfig.NOTIFY_ACTION_BAR.getAsBoolean()) {
            player.displayClientMessage(text, true);
        }
        if (MineAstrConfig.NOTIFY_TITLE.getAsBoolean()) {
            player.connection.send(new ClientboundSetTitleTextPacket(text));
        }
        if (MineAstrConfig.NOTIFY_SOUND.getAsBoolean()) {
            player.level().playSound(null, player.blockPosition(), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1.0F, 1.0F);
        }
        MineAstr.LOGGER.info("MineAstr 已向玩家 {} 发送来自 {} 的定向提醒。", player.getGameProfile().name(), sender);

        JsonObject data = new JsonObject();
        data.addProperty("player_uuid", player.getUUID().toString());
        data.addProperty("player_name", player.getGameProfile().name());
        data.addProperty("notified", true);
        MineAstrProtocol.sendQueryResult(socket, messageId, "notify_player", data);
    }

    private static JsonObject buildStatusData(MinecraftServer server, long startedAtMs) {
        PlayerList playerList = server.getPlayerList();
        JsonObject data = new JsonObject();
        data.addProperty("server_id", MineAstrConfig.SERVER_ID.get());
        data.addProperty("server_name", MineAstrConfig.SERVER_NAME.get());
        data.addProperty("mod_version", MineAstr.MOD_VERSION);
        data.addProperty("minecraft_version", SharedConstants.getCurrentVersion().name());
        data.addProperty("dedicated", server.isDedicatedServer());
        data.addProperty("uptime_ms", System.currentTimeMillis() - startedAtMs);
        data.addProperty("player_count", playerList.getPlayerCount());
        data.addProperty("max_players", playerList.getMaxPlayers());
        BlockPos spawn = server.overworld().getRespawnData().pos();
        JsonObject spawnData = new JsonObject();
        spawnData.addProperty("dimension", Level.OVERWORLD.identifier().toString());
        spawnData.addProperty("x", spawn.getX());
        spawnData.addProperty("y", spawn.getY());
        spawnData.addProperty("z", spawn.getZ());
        data.add("world_spawn", spawnData);
        JsonArray names = new JsonArray();
        JsonArray screenshotCapableNames = new JsonArray();
        for (ServerPlayer player : playerList.getPlayers()) {
            names.add(player.getGameProfile().name());
            if (MineAstrScreenshots.hasScreenshotCapability(player.getUUID())) {
                screenshotCapableNames.add(player.getGameProfile().name());
            }
        }
        data.add("online_player_names", names);
        data.add("screenshot_capable_player_names", screenshotCapableNames);
        return data;
    }

    private static JsonObject buildPlayersData(MinecraftServer server) {
        PlayerList playerList = server.getPlayerList();
        JsonObject data = new JsonObject();
        data.addProperty("server_id", MineAstrConfig.SERVER_ID.get());
        data.addProperty("server_name", MineAstrConfig.SERVER_NAME.get());
        data.addProperty("player_count", playerList.getPlayerCount());
        data.addProperty("max_players", playerList.getMaxPlayers());
        JsonArray players = new JsonArray();
        for (ServerPlayer player : playerList.getPlayers()) {
            JsonObject playerData = new JsonObject();
            playerData.addProperty("uuid", player.getUUID().toString());
            playerData.addProperty("name", player.getGameProfile().name());
            playerData.addProperty("display_name", player.getDisplayName().getString());
            playerData.addProperty("screenshot_supported", MineAstrScreenshots.hasScreenshotCapability(player.getUUID()));
            players.add(playerData);
        }
        data.add("players", players);
        return data;
    }

    private static JsonObject buildPerformanceData(MinecraftServer server) {
        double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
        double tps = mspt <= 0.0 ? 20.0 : Math.min(20.0, 1000.0 / mspt);
        Runtime runtime = Runtime.getRuntime();
        long usedBytes = runtime.totalMemory() - runtime.freeMemory();

        JsonObject data = new JsonObject();
        data.addProperty("tps", Math.round(tps * 100.0) / 100.0);
        data.addProperty("mspt", Math.round(mspt * 100.0) / 100.0);
        data.addProperty("memory_used_mb", usedBytes / (1024L * 1024L));
        data.addProperty("memory_max_mb", runtime.maxMemory() / (1024L * 1024L));
        var operatingSystem = ManagementFactory.getOperatingSystemMXBean();
        if (operatingSystem instanceof com.sun.management.OperatingSystemMXBean extended) {
            double cpuLoad = extended.getCpuLoad();
            if (cpuLoad >= 0.0) {
                data.addProperty("cpu_percent", Math.round(cpuLoad * 10_000.0) / 100.0);
            }
            double processCpuLoad = extended.getProcessCpuLoad();
            if (processCpuLoad >= 0.0) {
                data.addProperty("process_cpu_percent", Math.round(processCpuLoad * 10_000.0) / 100.0);
            }
        }
        return data;
    }

    public static ServerPlayer findTargetPlayer(MinecraftServer server, JsonObject payload) {
        String playerUuid = MineAstrProtocol.getString(payload, "player_uuid", "").strip();
        String playerName = MineAstrProtocol.getString(payload, "player_name", "").strip();
        PlayerList playerList = server.getPlayerList();
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
            return findExactPlayer(server, playerName);
        }
        if (playerUuid.isEmpty() && playerName.isEmpty() && playerList.getPlayerCount() == 1) {
            return playerList.getPlayers().getFirst();
        }
        return null;
    }

    private static ServerPlayer findExactPlayer(MinecraftServer server, String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return null;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getGameProfile().name().equalsIgnoreCase(playerName)) {
                return player;
            }
        }
        return null;
    }

    private static ServerLevel findTargetLevel(MinecraftServer server, JsonObject payload) {
        String dimensionText = MineAstrProtocol.trimFlatContent(MineAstrProtocol.getString(payload, "dimension", "minecraft:overworld"), 128);
        Identifier location = Identifier.tryParse(dimensionText);
        if (location == null) {
            return null;
        }
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, location);
        return server.getLevel(key);
    }

    private static boolean hasCoordinates(JsonObject payload) {
        return payload.has("x") && payload.has("y") && payload.has("z");
    }
}
