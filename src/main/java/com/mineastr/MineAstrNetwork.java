package com.mineastr;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

public final class MineAstrNetwork {
    private MineAstrNetwork() {
    }

    public static void initializeServerNetworking() {
        PayloadTypeRegistry.playC2S().register(MineAstrPayloads.ClientHello.TYPE, MineAstrPayloads.ClientHello.CODEC);
        PayloadTypeRegistry.playC2S().register(MineAstrPayloads.ScreenshotChunk.TYPE, MineAstrPayloads.ScreenshotChunk.CODEC);
        PayloadTypeRegistry.playC2S().register(MineAstrPayloads.ScreenshotError.TYPE, MineAstrPayloads.ScreenshotError.CODEC);
        PayloadTypeRegistry.playS2C().register(MineAstrPayloads.ScreenshotRequest.TYPE, MineAstrPayloads.ScreenshotRequest.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(MineAstrPayloads.ClientHello.TYPE, (payload, context) ->
                context.server().execute(() -> MineAstr.bridge().registerClientCapability(
                        context.player(), payload.screenshotSupported(), payload.modVersion())));
        ServerPlayNetworking.registerGlobalReceiver(MineAstrPayloads.ScreenshotChunk.TYPE, (payload, context) ->
                context.server().execute(() -> MineAstr.bridge().receiveScreenshotChunk(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(MineAstrPayloads.ScreenshotError.TYPE, (payload, context) ->
                context.server().execute(() -> MineAstr.bridge().receiveScreenshotError(
                        context.player(), payload.code(), payload.message(), payload.requestId())));
    }

    public static boolean canSendScreenshotRequest(ServerPlayer player) {
        return ServerPlayNetworking.canSend(player, MineAstrPayloads.ScreenshotRequest.TYPE);
    }

    public static void sendScreenshotRequest(ServerPlayer player, MineAstrPayloads.ScreenshotRequest request) {
        ServerPlayNetworking.send(player, request);
    }
}
