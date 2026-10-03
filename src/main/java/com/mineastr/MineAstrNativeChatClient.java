package com.mineastr;

import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.contents.TranslatableContents;

public final class MineAstrNativeChatClient {
    private static final String KEY = "message.mineastr.native_chat_id";
    private MineAstrNativeChatClient() {}
    public static UUID id(Component content) {
        var hover=content.getStyle().getHoverEvent();
        if(hover==null)return null;
        var value=hover.getValue(HoverEvent.Action.SHOW_TEXT);
        if(value==null || !(value.getContents() instanceof TranslatableContents data) || !KEY.equals(data.getKey()) || data.getArgs().length!=1)return null;
        try { return UUID.fromString(String.valueOf(data.getArgs()[0])); } catch(IllegalArgumentException e) { return null; }
    }
    public static Component message(MineAstrPayloads.NativeChat payload) {
        return Component.translatable("chat.type.text", Component.literal(payload.sender()), Component.literal(payload.content()))
                .withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,Component.translatable(KEY,payload.id().toString()))));
    }
    public static void receive(MineAstrPayloads.NativeChat payload) {
        var client=Minecraft.getInstance();
        if(client.gui==null || client.options.chatVisibility().get()!=net.minecraft.world.entity.player.ChatVisiblity.FULL
                || client.options.onlyShowSecureChat().get() || client.getPlayerSocialManager().shouldHideMessageFrom(payload.senderUuid()))return;
        var chat=client.gui.getChat();
        var content=message(payload);
        boolean replaced=((MineAstrChatAccess)chat).mineastr$replaceNative(payload.id(),content);
        // A completion for cleared/evicted history must never resurrect the message.
        if(!payload.update() && !replaced) {
            chat.addMessage(content);
            client.getNarrator().sayChat(content);
        }
    }
}
