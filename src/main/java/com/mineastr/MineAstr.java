package com.mineastr;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import java.lang.reflect.Method;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerNegotiationEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

@Mod(MineAstr.MODID)
public final class MineAstr {
    public static final String MODID = "mineastr";
    public static final String MOD_VERSION = "0.6.25";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final MineAstrBridge BRIDGE = new MineAstrBridge();

    public MineAstr(IEventBus modEventBus, ModContainer modContainer) {
        NeoForge.EVENT_BUS.register(this);
        modEventBus.addListener(MineAstrNetwork::register);
        modContainer.registerConfig(ModConfig.Type.COMMON, MineAstrConfig.SPEC);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            modContainer.registerConfig(ModConfig.Type.CLIENT, MineAstrClientConfig.SPEC);
            initClient(modContainer, modEventBus);
        }
    }

    public static MineAstrBridge bridge() {
        return BRIDGE;
    }

    private static void initClient(ModContainer modContainer, IEventBus modEventBus) {
        try {
            Class<?> clientClass = Class.forName("com.mineastr.MineAstrClient");
            Method init = clientClass.getMethod("init", ModContainer.class, IEventBus.class);
            init.invoke(null, modContainer, modEventBus);
        } catch (ReflectiveOperationException exc) {
            LOGGER.warn("MineAstr client initialization failed: {}", exc.getMessage());
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        if (!event.getServer().isDedicatedServer()
                && FMLEnvironment.dist == Dist.CLIENT
                && !MineAstrClientConfig.LOCAL_WORLD_SERVER_ENABLED.getAsBoolean()) {
            LOGGER.info("MineAstr integrated-server bridge is disabled by the client setting.");
            return;
        }
        BRIDGE.start(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        BRIDGE.stop();
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        BRIDGE.forwardChat(event.getPlayer(), event.getRawText());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        MineAstrCommands.register(event.getDispatcher(), BRIDGE);
    }

    @SubscribeEvent
    public void onSignInteraction(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()
                || event.getHand() != InteractionHand.MAIN_HAND
                || !(event.getEntity() instanceof ServerPlayer player)
                || !(event.getLevel().getBlockState(event.getPos()).getBlock() instanceof SignBlock)
                || !(event.getLevel().getBlockEntity(event.getPos()) instanceof SignBlockEntity sign)) {
            return;
        }
        BRIDGE.translateSign(player, sign, sign.isFacingFrontText(player));
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BRIDGE.forwardPlayerJoin(player);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BRIDGE.forwardPlayerLeave(player);
            BRIDGE.unregisterClientCapability(player);
        }
    }

    @SubscribeEvent
    public void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BRIDGE.forwardPlayerDeath(player, event.getSource());
        }
    }

    @SubscribeEvent
    public void onPlayerNegotiation(PlayerNegotiationEvent event) {
        GameProfile profile = event.getProfile();
        if (profile == null || profile.getName() == null || profile.getName().isBlank()) {
            if (MineAstrConfig.LOGIN_BINDING_CHECK_ENABLED.getAsBoolean()
                    && !MineAstrConfig.LOGIN_CHECK_FAIL_OPEN.getAsBoolean()) {
                event.getConnection().disconnect(net.minecraft.network.chat.Component.translatableWithFallback(
                        "disconnect.mineastr.login.identity_unavailable",
                        "[MC] Unable to read the login identity. Please try again later."));
            }
            return;
        }

        BRIDGE.reconcileLoginWhitelistIdentity(profile);
        if (!MineAstrConfig.LOGIN_BINDING_CHECK_ENABLED.getAsBoolean()) {
            return;
        }
        event.enqueueWork(BRIDGE.checkPlayerLogin(profile.getName()).thenAccept(result -> {
            if (!result.allowed()) {
                event.getConnection().disconnect(result.component());
            }
        }));
    }
}
