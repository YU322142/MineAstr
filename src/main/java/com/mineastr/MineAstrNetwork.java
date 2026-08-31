package com.mineastr;

import java.lang.reflect.Method;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class MineAstrNetwork {
    private MineAstrNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToServer(MineAstrPayloads.ClientHello.TYPE, MineAstrPayloads.ClientHello.CODEC,
                MineAstrNetwork::handleClientHello);
        registrar.playToServer(MineAstrPayloads.TranslationPreferences.TYPE,
                MineAstrPayloads.TranslationPreferences.CODEC, MineAstrNetwork::handleTranslationPreferences);
        registrar.playToServer(MineAstrPayloads.BotImagePreferences.TYPE,
                MineAstrPayloads.BotImagePreferences.CODEC, MineAstrNetwork::handleBotImagePreferences);
        registrar.playToServer(MineAstrPayloads.SignTranslationQuery.TYPE,
                MineAstrPayloads.SignTranslationQuery.CODEC, MineAstrNetwork::handleSignTranslationQuery);
        registrar.playToServer(MineAstrPayloads.ImageTranslationQuery.TYPE,
                MineAstrPayloads.ImageTranslationQuery.CODEC, MineAstrNetwork::handleImageTranslationQuery);
        registrar.playToServer(MineAstrPayloads.ScreenshotChunk.TYPE, MineAstrPayloads.ScreenshotChunk.CODEC,
                MineAstrNetwork::handleScreenshotChunk);
        registrar.playToServer(MineAstrPayloads.ScreenshotError.TYPE, MineAstrPayloads.ScreenshotError.CODEC,
                MineAstrNetwork::handleScreenshotError);

        registrar.playToClient(MineAstrPayloads.ScreenshotRequest.TYPE, MineAstrPayloads.ScreenshotRequest.CODEC,
                (payload, context) -> invokeClientHandler(context, "handleScreenshotRequest",
                        MineAstrPayloads.ScreenshotRequest.class, payload));
        registrar.playToClient(MineAstrPayloads.SignTranslationResult.TYPE,
                MineAstrPayloads.SignTranslationResult.CODEC,
                (payload, context) -> invokeClientHandler(context, "handleSignTranslationResult",
                        MineAstrPayloads.SignTranslationResult.class, payload));
        registrar.playToClient(MineAstrPayloads.SignTranslationCacheReset.TYPE,
                MineAstrPayloads.SignTranslationCacheReset.CODEC,
                (payload, context) -> invokeClientHandler(context, "handleSignTranslationCacheReset",
                        MineAstrPayloads.SignTranslationCacheReset.class, payload));
        registrar.playToClient(MineAstrPayloads.ImageTranslationResult.TYPE,
                MineAstrPayloads.ImageTranslationResult.CODEC,
                (payload, context) -> invokeClientHandler(context, "handleImageTranslationResult",
                        MineAstrPayloads.ImageTranslationResult.class, payload));
        registrar.playToClient(MineAstrPayloads.BotImageChunk.TYPE,
                MineAstrPayloads.BotImageChunk.CODEC,
                (payload, context) -> invokeClientHandler(context, "handleBotImageChunk",
                        MineAstrPayloads.BotImageChunk.class, payload));
    }

    private static void handleClientHello(MineAstrPayloads.ClientHello payload, IPayloadContext context) {
        context.enqueueWork(() -> withServerPlayer(context, player -> MineAstr.bridge().registerClientCapability(
                player, payload.screenshotSupported(), payload.modVersion())));
    }

    private static void handleTranslationPreferences(
            MineAstrPayloads.TranslationPreferences payload, IPayloadContext context) {
        context.enqueueWork(() -> withServerPlayer(context, player -> MineAstr.bridge().registerTranslationPreference(
                player, payload.translationsEnabled(), payload.showOriginal())));
    }

    private static void handleBotImagePreferences(
            MineAstrPayloads.BotImagePreferences payload, IPayloadContext context) {
        context.enqueueWork(() -> withServerPlayer(context, player -> MineAstr.bridge().registerBotImagePreference(
                player, payload.enabled(), payload.chatImageAvailable())));
    }

    private static void handleSignTranslationQuery(
            MineAstrPayloads.SignTranslationQuery payload, IPayloadContext context) {
        context.enqueueWork(() -> withServerPlayer(context,
                player -> MineAstr.bridge().handleSignTranslationQuery(player, payload)));
    }

    private static void handleImageTranslationQuery(
            MineAstrPayloads.ImageTranslationQuery payload, IPayloadContext context) {
        context.enqueueWork(() -> withServerPlayer(context,
                player -> MineAstr.bridge().handleImageTranslationQuery(player, payload)));
    }

    private static void handleScreenshotChunk(MineAstrPayloads.ScreenshotChunk payload, IPayloadContext context) {
        context.enqueueWork(() -> withServerPlayer(context,
                player -> MineAstr.bridge().receiveScreenshotChunk(player, payload)));
    }

    private static void handleScreenshotError(MineAstrPayloads.ScreenshotError payload, IPayloadContext context) {
        context.enqueueWork(() -> withServerPlayer(context, player -> MineAstr.bridge().receiveScreenshotError(
                player, payload.code(), payload.message(), payload.requestId())));
    }

    private static void withServerPlayer(IPayloadContext context, java.util.function.Consumer<ServerPlayer> action) {
        if (context.player() instanceof ServerPlayer player) {
            action.accept(player);
        }
    }

    private static <T extends CustomPacketPayload> void invokeClientHandler(
            IPayloadContext context, String methodName, Class<T> payloadType, T payload) {
        context.enqueueWork(() -> {
            if (!FMLEnvironment.dist.isClient()) {
                return;
            }
            try {
                Class<?> clientClass = Class.forName("com.mineastr.MineAstrClient");
                Method method = clientClass.getMethod(methodName, payloadType);
                method.invoke(null, payload);
            } catch (ReflectiveOperationException exc) {
                MineAstr.LOGGER.warn("MineAstr client payload handler failed: {} {}", methodName, exc.getMessage());
            }
        });
    }

    public static boolean canSendScreenshotRequest(ServerPlayer player) {
        return canSend(player, MineAstrPayloads.ScreenshotRequest.TYPE);
    }

    public static void sendScreenshotRequest(ServerPlayer player, MineAstrPayloads.ScreenshotRequest request) {
        if (canSendScreenshotRequest(player)) {
            PacketDistributor.sendToPlayer(player, request);
        }
    }

    public static boolean canSendSignTranslationResult(ServerPlayer player) {
        return canSend(player, MineAstrPayloads.SignTranslationResult.TYPE);
    }

    public static void sendSignTranslationResult(ServerPlayer player, MineAstrPayloads.SignTranslationResult result) {
        if (canSendSignTranslationResult(player)) {
            PacketDistributor.sendToPlayer(player, result);
        }
    }

    public static void sendSignTranslationCacheReset(ServerPlayer player, long revision) {
        if (canSend(player, MineAstrPayloads.SignTranslationCacheReset.TYPE)) {
            PacketDistributor.sendToPlayer(player, new MineAstrPayloads.SignTranslationCacheReset(revision));
        }
    }

    public static boolean canSendImageTranslationResult(ServerPlayer player) {
        return canSend(player, MineAstrPayloads.ImageTranslationResult.TYPE);
    }

    public static void sendImageTranslationResult(ServerPlayer player, MineAstrPayloads.ImageTranslationResult result) {
        if (canSendImageTranslationResult(player)) {
            PacketDistributor.sendToPlayer(player, result);
        }
    }

    public static boolean canSendBotImageChunk(ServerPlayer player) {
        return canSend(player, MineAstrPayloads.BotImageChunk.TYPE);
    }

    public static void sendBotImageChunk(ServerPlayer player, MineAstrPayloads.BotImageChunk chunk) {
        if (canSendBotImageChunk(player)) {
            PacketDistributor.sendToPlayer(player, chunk);
        }
    }

    private static boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return player != null && player.connection != null && player.connection.hasChannel(type);
    }
}
