package com.mineastr;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

class MineAstrNativeChatWireTest {
    @Test void previewAndCompletionRetainIdentityUnicodeAndUpdateFlag() {
        UUID id=UUID.randomUUID(),sender=UUID.randomUUID();
        for(boolean update:new boolean[]{false,true}) {
            var payload=new MineAstrPayloads.NativeChat(id,sender,"Alice","你好 / مرحبا / 👩🏽‍💻\ntranslation",update);
            var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
            try { MineAstrPayloads.NativeChat.CODEC.encode(buffer,payload); assertEquals(payload,MineAstrPayloads.NativeChat.CODEC.decode(buffer)); }
            finally { buffer.release(); }
        }
    }
    @Test void excessiveContentIsRejectedBeforeNetworkTransmission() {
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try { assertThrows(RuntimeException.class,()->MineAstrPayloads.NativeChat.CODEC.encode(buffer,
                new MineAstrPayloads.NativeChat(UUID.randomUUID(),UUID.randomUUID(),"Alice","x".repeat(4097),false))); }
        finally { buffer.release(); }
    }
}
