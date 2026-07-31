package com.mineastr;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

public final class MineAstrNetwork {
    private MineAstrNetwork() {
    }

    public static void initializeServerNetworking() {
        PayloadTypeRegistry.playC2S().register(MineAstrPayloads.ClientHello.TYPE, MineAstrPayloads.ClientHello.CODEC);
        PayloadTypeRegistry.playC2S().register(
                MineAstrPayloads.TranslationPreferences.TYPE,
                MineAstrPayloads.TranslationPreferences.CODEC);
        PayloadTypeRegistry.playC2S().register(MineAstrPayloads.ScreenshotChunk.TYPE, MineAstrPayloads.ScreenshotChunk.CODEC);
        PayloadTypeRegistry.playC2S().register(MineAstrPayloads.ScreenshotError.TYPE, MineAstrPayloads.ScreenshotError.CODEC);
        PayloadTypeRegistry.playS2C().register(MineAstrPayloads.ScreenshotRequest.TYPE, MineAstrPayloads.ScreenshotRequest.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(MineAstrPayloads.ClientHello.TYPE, (payload, context) ->
                context.server().execute(() -> MineAstrScreenshots.registerClient(
                        context.player(), payload.screenshotSupported(), payload.modVersion())));
        ServerPlayNetworking.registerGlobalReceiver(MineAstrPayloads.TranslationPreferences.TYPE, (payload, context) ->
                context.server().execute(() -> MineAstrChat.registerPreference(
                        context.player(), payload.translationsEnabled(), payload.showOriginal())));
        ServerPlayNetworking.registerGlobalReceiver(MineAstrPayloads.ScreenshotChunk.TYPE, (payload, context) ->
                context.server().execute(() -> MineAstrScreenshots.receiveChunk(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(MineAstrPayloads.ScreenshotError.TYPE, (payload, context) ->
                context.server().execute(() -> MineAstrScreenshots.receiveError(
                        context.player(), payload.code(), payload.message(), payload.requestId())));
    }

    public static boolean canSendScreenshotRequest(ServerPlayer player) {
        return ServerPlayNetworking.canSend(player, MineAstrPayloads.ScreenshotRequest.TYPE);
    }

    public static void sendScreenshotRequest(ServerPlayer player, MineAstrPayloads.ScreenshotRequest request) {
        ServerPlayNetworking.send(player, request);
    }
}
