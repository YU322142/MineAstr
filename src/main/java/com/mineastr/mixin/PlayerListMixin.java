package com.mineastr.mixin;

import com.mineastr.MineAstrBindings;
import java.net.SocketAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Shadow
    public abstract MinecraftServer getServer();

    @Inject(method = "canPlayerLogin", at = @At("HEAD"))
    private void mineastr$reconcileWhitelistIdentity(
            SocketAddress address,
            NameAndId identity,
            CallbackInfoReturnable<Component> callback) {
        MineAstrBindings.reconcileLogin(identity, getServer());
    }
}
