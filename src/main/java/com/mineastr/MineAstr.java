package com.mineastr;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

public final class MineAstr implements ModInitializer {
    public static final String MODID = "mineastr";
    public static final String MOD_VERSION = "0.6.5";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final MineAstrBridge BRIDGE = new MineAstrBridge();

    public static MineAstrBridge bridge() {
        return BRIDGE;
    }

    @Override
    public void onInitialize() {
        MineAstrConfig.load();
        MineAstrNetwork.initializeServerNetworking();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!server.isDedicatedServer()
                    && FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT
                    && !MineAstrClientConfig.LOCAL_WORLD_SERVER_ENABLED.getAsBoolean()) {
                LOGGER.info("MineAstr 本地世界服务端桥接默认关闭；可在客户端配置文件中启用。");
                return;
            }
            BRIDGE.start(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> BRIDGE.stop());

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> MineAstrCommands.register(dispatcher, BRIDGE));

        ServerMessageEvents.CHAT_MESSAGE.register(
                (message, sender, params) -> BRIDGE.forwardChat(sender, message.signedContent()));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> BRIDGE.forwardPlayerJoin(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            BRIDGE.forwardPlayerLeave(handler.player);
            BRIDGE.unregisterClientCapability(handler.player);
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof ServerPlayer player) {
                BRIDGE.forwardPlayerDeath(player, player.getCombatTracker().getDeathMessage().getString());
            }
        });

        ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, synchronizer) -> {
            if (!MineAstrConfig.LOGIN_BINDING_CHECK_ENABLED.getAsBoolean()) {
                return;
            }
            synchronizer.waitFor(BRIDGE.checkPlayerLogin(handler.getUserName())
                    .thenAccept(result -> {
                        if (!result.allowed()) {
                            handler.disconnect(net.minecraft.network.chat.Component.literal(result.message()));
                        }
                    }));
        });
    }
}
