package com.mineastr.mixin;

import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerLoginPacketListenerImpl.class)
public interface ServerLoginPacketListenerAccessor {
    @Accessor("requestedUsername")
    @Nullable
    String mineastr$getRequestedUsername();
}
