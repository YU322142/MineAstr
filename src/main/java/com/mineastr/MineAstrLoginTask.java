package com.mineastr;

import com.mojang.authlib.GameProfile;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;

/** Holds world entry until the asynchronous binding decision is complete. */
final class MineAstrLoginTask implements ConfigurationTask {
    static final Type TYPE = new Type("mineastr:binding_login_check");
    private final Runnable prepare;
    private final Supplier<CompletableFuture<MineAstrBridge.LoginCheckResult>> check;
    private final Executor executor;
    private final BooleanSupplier connected;
    private final Consumer<Component> disconnect;
    private final Runnable finish;

    MineAstrLoginTask(Runnable prepare,
            Supplier<CompletableFuture<MineAstrBridge.LoginCheckResult>> check,
            Executor executor, BooleanSupplier connected,
            Consumer<Component> disconnect, Runnable finish) {
        this.prepare = prepare;
        this.check = check;
        this.executor = executor;
        this.connected = connected;
        this.disconnect = disconnect;
        this.finish = finish;
    }

    static MineAstrLoginTask forConnection(
            ServerConfigurationPacketListener listener, MineAstrBridge bridge) {
        GameProfile profile = listener instanceof ServerCommonPacketListenerImpl common
                ? common.getOwner() : null;
        return new MineAstrLoginTask(
                () -> bridge.reconcileLoginWhitelistIdentity(profile),
                () -> {
                    if (profile == null || profile.getName() == null || profile.getName().isBlank()) {
                        return CompletableFuture.completedFuture(new MineAstrBridge.LoginCheckResult(
                                !MineAstrConfig.LOGIN_BINDING_CHECK_ENABLED.getAsBoolean(),
                                "[MC] Unable to read the login identity. Please try again later.",
                                "disconnect.mineastr.login.identity_unavailable", ""));
                    }
                    return bridge.checkPlayerLogin(profile.getName());
                },
                listener.getMainThreadEventLoop(), listener.getConnection()::isConnected,
                listener::disconnect, () -> listener.finishCurrentTask(TYPE));
    }

    @Override
    public void start(Consumer<Packet<?>> sender) {
        try {
            prepare.run();
            check.get().whenComplete((result, error) -> executor.execute(() -> {
                if (!connected.getAsBoolean()) {
                    return;
                }
                if (error != null || result == null) {
                    rejectUnexpected(error);
                } else if (result.allowed()) {
                    finish.run();
                } else {
                    disconnect.accept(result.component());
                }
            }));
        } catch (Exception error) {
            executor.execute(() -> {
                if (connected.getAsBoolean()) {
                    rejectUnexpected(error);
                }
            });
        }
    }

    private void rejectUnexpected(Throwable error) {
        MineAstr.LOGGER.warn("MineAstr 登录绑定任务异常，拒绝进入世界", error);
        disconnect.accept(Component.translatableWithFallback(
                "disconnect.mineastr.login.unavailable",
                "[MC] Account binding verification is unavailable. Please try again later."));
    }

    @Override
    public Type type() {
        return TYPE;
    }
}
