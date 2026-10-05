package com.mineastr.mixin;

import com.mineastr.MineAstrChatAccess;
import com.mineastr.MineAstrChatGeometry;
import com.mineastr.MineAstrChatLayout;
import com.mineastr.MineAstrChatViewport;
import com.mineastr.MineAstrClientConfig;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps vanilla's message/signature/history APIs; MineAstr owns layout, viewport and input geometry. */
@Mixin(net.minecraft.client.gui.components.ChatComponent.class)
public abstract class MineAstrChatMixin implements MineAstrChatAccess {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private List<GuiMessage.Line> trimmedMessages;
    @Shadow @Final private List<GuiMessage> allMessages;
    @Shadow private int chatScrollbarPos;
    @Shadow private boolean newMessageSinceScroll;
    @Shadow public abstract int getWidth();
    @Shadow public abstract int getHeight();
    @Shadow public abstract double getScale();
    @Shadow public abstract int getLinesPerPage();
    @Shadow public abstract boolean isChatFocused();
    @Shadow protected abstract int getLineHeight();
    @Shadow protected abstract boolean isChatHidden();
    @Shadow protected abstract void refreshTrimmedMessages();
    @Unique private final MineAstrChatViewport mineastr$viewport = new MineAstrChatViewport();
    @Unique private boolean mineastr$rebuilding;
    @Unique private int mineastr$width = -1, mineastr$pages, mineastr$height, mineastr$imageScale;
    @Unique private double mineastr$scale;

    @Override public boolean mineastr$replaceNative(UUID id, Component content) {
        for (int index = 0; index < allMessages.size(); index++) {
            GuiMessage original = allMessages.get(index);
            if (!id.equals(com.mineastr.MineAstrNativeChatClient.id(original.content()))) continue;
            if (original.content().equals(content)) return true;
            allMessages.set(index, new GuiMessage(original.addedTime(), content, original.signature(), original.tag()));
            refreshTrimmedMessages();
            return true;
        }
        return false;
    }
    @Override public void mineastr$scrollPixels(double pixels) { mineastr$viewport.scroll(pixels); }
    @Override public boolean mineastr$chatClick(double x, double y, int button) { return mineastr$viewport.click(x, y, button); }
    @Override public boolean mineastr$chatDrag(double y, int button) { return mineastr$viewport.drag(y, button); }
    @Override public boolean mineastr$chatRelease(int button) { return mineastr$viewport.release(button); }

    @Inject(method = "addMessageToDisplayQueue", at = @At("HEAD"), cancellable = true)
    private void mineastr$layout(GuiMessage message, CallbackInfo ci) {
        int width = (int) (getWidth() / getScale());
        if (message.icon() != null) width -= message.icon().width + 6;
        Component formatted = MineAstrChatLayout.format(message.content(), width, getLineHeight(), getLinesPerPage());
        var lines = ComponentRenderUtils.wrapComponents(formatted, Math.max(1, width), minecraft.font);
        int count = Math.min(4096, lines.size());
        for (int index = 0; index < count; index++) {
            trimmedMessages.addFirst(new GuiMessage.Line(message.addedTime(), lines.get(index), message.tag(), index == count - 1));
        }
        // Retain 100 complete messages, with a separate bounded display-row budget for images/multiline text.
        int entries = 0;
        for (int index = 0; index < trimmedMessages.size(); index++) {
            if (trimmedMessages.get(index).endOfEntry()) entries++;
            if (entries > 100 || index >= 4096) {
                trimmedMessages.subList(index, trimmedMessages.size()).clear(); break;
            }
        }
        if (!mineastr$rebuilding) {
            mineastr$viewport.rowsInserted(trimmedMessages, Math.min(count, trimmedMessages.size()), getLineHeight());
            if (mineastr$viewport.position() > 0) newMessageSinceScroll = true;
        }
        ci.cancel();
    }
    @Inject(method = "refreshTrimmedMessages", at = @At("HEAD"))
    private void mineastr$reflowStart(CallbackInfo ci) { mineastr$rebuilding = true; }
    @Inject(method = "refreshTrimmedMessages", at = @At("RETURN"))
    private void mineastr$reflowEnd(CallbackInfo ci) { mineastr$rebuilding = false; }
    @Inject(method = "clearMessages", at = @At("RETURN"))
    private void mineastr$clear(CallbackInfo ci) { mineastr$viewport.clear(); }
    @Inject(method = "resetChatScroll", at = @At("RETURN"))
    private void mineastr$reset(CallbackInfo ci) { mineastr$viewport.resetScroll(); }
    @Inject(method = "scrollChat", at = @At("HEAD"), cancellable = true)
    private void mineastr$legacyScroll(int rows, CallbackInfo ci) {
        mineastr$viewport.scroll(rows * (double) getLineHeight()); ci.cancel();
    }
    @Inject(method = "getHeight()I", at = @At("RETURN"), cancellable = true)
    private void mineastr$height(CallbackInfoReturnable<Integer> cir) {
        if (!MineAstrClientConfig.isLoaded()) return;
        int percent = MineAstrClientConfig.CHAT_MAX_HEIGHT_PERCENT.getAsInt();
        if (percent != 0) cir.setReturnValue(MineAstrChatGeometry.heightLimit(cir.getReturnValue(),
                minecraft.getWindow().getGuiScaledHeight(), getScale(), percent, getLineHeight()));
    }
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void mineastr$render(GuiGraphics graphics, int tick, int mouseX, int mouseY, boolean focused, CallbackInfo ci) {
        int width = getWidth(), pages = getLinesPerPage(), height = getLineHeight(), imageScale = MineAstrClientConfig.chatImageScale();
        double scale = getScale();
        if (mineastr$width >= 0 && (width != mineastr$width || height != mineastr$height
                || pages != mineastr$pages || imageScale != mineastr$imageScale || scale != mineastr$scale)) refreshTrimmedMessages();
        mineastr$width = width; mineastr$pages = pages; mineastr$height = height;
        mineastr$imageScale = imageScale; mineastr$scale = scale;
        mineastr$viewport.render(graphics, trimmedMessages, tick, mouseX, mouseY, focused,
                width, getHeight(), height, scale, isChatHidden());
        chatScrollbarPos = (int) Math.floor(mineastr$viewport.position() / height); // API compatibility only; never used for rendering.
        if (chatScrollbarPos == 0) newMessageSinceScroll = false;
        ci.cancel();
    }
    @Inject(method = "getMessageLineIndexAt", at = @At("HEAD"), cancellable = true)
    private void mineastr$hit(double x, double y, CallbackInfoReturnable<Integer> cir) { cir.setReturnValue(mineastr$viewport.lineAt(x, y)); }
    @Inject(method = "getClickedComponentStyleAt", at = @At("HEAD"), cancellable = true)
    private void mineastr$style(double x, double y, CallbackInfoReturnable<Style> cir) { cir.setReturnValue(mineastr$viewport.styleAt(trimmedMessages, x, y)); }
    @Inject(method = "getMessageTagAt", at = @At("HEAD"), cancellable = true)
    private void mineastr$tag(double x, double y, CallbackInfoReturnable<GuiMessageTag> cir) { cir.setReturnValue(mineastr$viewport.tagAt(trimmedMessages, x, y)); }
}
