package com.mineastr;

import com.google.gson.JsonObject;
import java.net.http.WebSocket;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;

public final class MineAstrEvents {
    private static final int MAX_EVENT_TEXT_LENGTH = 512;

    private MineAstrEvents() {
    }

    public static void forwardChat(ServerPlayer player, String rawText, WebSocket socket) {
        if (socket == null || socket.isOutputClosed()) {
            MineAstr.LOGGER.debug("MineAstr 未连接，已丢弃本条 Minecraft 聊天。");
            return;
        }
        String content = MineAstrProtocol.trimContent(rawText, MineAstrConfig.MAX_MESSAGE_LENGTH.getAsInt());
        if (content.isEmpty()) {
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("type", "chat");
        payload.addProperty("message_id", UUID.randomUUID().toString());
        payload.addProperty("time_ms", System.currentTimeMillis());
        payload.addProperty("server_id", MineAstrConfig.SERVER_ID.get());
        payload.addProperty("server_name", MineAstrConfig.SERVER_NAME.get());
        payload.addProperty("player_uuid", player.getUUID().toString());
        payload.addProperty("player_name", player.getGameProfile().name());
        payload.addProperty("content", content);
        MineAstrProtocol.sendJson(socket, payload);
    }

    public static void forwardJoin(ServerPlayer player, WebSocket socket) {
        sendPlayerEvent("player_join", player, socket);
    }

    public static void forwardLeave(ServerPlayer player, WebSocket socket) {
        sendPlayerEvent("player_leave", player, socket);
    }

    public static void forwardDeath(ServerPlayer player, DamageSource damageSource, WebSocket socket) {
        if (socket == null || socket.isOutputClosed()) {
            return;
        }
        JsonObject payload = MineAstrProtocol.eventEnvelope("player_death");
        payload.addProperty("player_uuid", player.getUUID().toString());
        payload.addProperty("player_name", player.getGameProfile().name());

        String deathMessage = MineAstrProtocol.trimFlatContent(
                player.getCombatTracker().getDeathMessage().getString(),
                MAX_EVENT_TEXT_LENGTH);
        if (!deathMessage.isBlank()) {
            payload.addProperty("death_message", deathMessage);
            payload.addProperty("reason", deathMessage);
        }
        String deathType = MineAstrProtocol.trimFlatContent(damageSource.getMsgId(), 128);
        if (!deathType.isBlank()) {
            payload.addProperty("death_type", deathType);
        }
        if (damageSource.getEntity() != null) {
            payload.addProperty(
                    "attacker",
                    MineAstrProtocol.trimFlatContent(damageSource.getEntity().getDisplayName().getString(), MAX_EVENT_TEXT_LENGTH));
        }
        if (damageSource.getDirectEntity() != null) {
            payload.addProperty(
                    "direct_entity",
                    MineAstrProtocol.trimFlatContent(damageSource.getDirectEntity().getDisplayName().getString(), MAX_EVENT_TEXT_LENGTH));
        }
        var weapon = damageSource.getWeaponItem();
        if (weapon != null && !weapon.isEmpty()) {
            payload.addProperty(
                    "weapon",
                    MineAstrProtocol.trimFlatContent(weapon.getDisplayName().getString(), MAX_EVENT_TEXT_LENGTH));
        }
        MineAstrProtocol.sendJson(socket, payload);
    }

    private static void sendPlayerEvent(String event, ServerPlayer player, WebSocket socket) {
        if (socket == null || socket.isOutputClosed()) {
            return;
        }
        JsonObject payload = MineAstrProtocol.eventEnvelope(event);
        payload.addProperty("player_uuid", player.getUUID().toString());
        payload.addProperty("player_name", player.getGameProfile().name());
        MineAstrProtocol.sendJson(socket, payload);
    }
}
