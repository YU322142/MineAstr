package com.mineastr.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mineastr.MineAstrChatEasing;
import com.mineastr.MineAstrChatMotion;
import com.mineastr.MineAstrChatInsertionMotion;
import com.mineastr.MineAstrChatImages;
import com.mineastr.MineAstrChatGeometry;
import com.mineastr.MineAstrChatIcons;
import com.mineastr.MineAstrChatLayout;
import com.mineastr.MineAstrClientConfig;
import com.mineastr.MineAstrClientThemes;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatComponent.class)
public abstract class MineAstrChatMixin implements com.mineastr.MineAstrChatAccess {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private List<GuiMessage.Line> trimmedMessages;
    @Shadow @Final private List<GuiMessage> allMessages;
    @Shadow private int chatScrollbarPos;
    @Shadow public abstract int getWidth();
    @Shadow public abstract double getScale();
    @Shadow public abstract int getLinesPerPage();
    @Shadow public abstract boolean isChatFocused();
    @Shadow protected abstract int getLineHeight();
    @Shadow protected abstract boolean isChatHidden();
    @Shadow protected abstract void drawTagIcon(GuiGraphics graphics, int x, int y, GuiMessageTag.Icon icon);
    @Shadow protected abstract void refreshTrimmedMessages();

    @Unique private final MineAstrChatMotion mineastr$scroll = new MineAstrChatMotion(MineAstrChatEasing::gentle);
    @Unique private final MineAstrChatInsertionMotion mineastr$insert = new MineAstrChatInsertionMotion();
    @Unique private final Map<GuiMessage.Line, Double> mineastr$arrivals = new WeakHashMap<>();
    @Unique private GuiMessage.Line mineastr$previous, mineastr$current;
    @Unique private boolean mineastr$rebuilding, mineastr$clipActive, mineastr$updating;
    @Unique private double mineastr$now, mineastr$position, mineastr$insertRows;
    @Unique private int mineastr$base;
    @Unique private int mineastr$width = -1, mineastr$pages, mineastr$height, mineastr$imageScale;
    @Unique private double mineastr$scale;

    @Override public boolean mineastr$replaceNative(java.util.UUID id, net.minecraft.network.chat.Component content) {
        for(int index=0;index<allMessages.size();index++) {
            GuiMessage original=allMessages.get(index);
            if(!id.equals(com.mineastr.MineAstrNativeChatClient.id(original.content())))continue;
            if(original.content().getString().equals(content.getString()))return true;
            allMessages.set(index,new GuiMessage(original.addedTime(),content,original.signature(),original.tag()));
            int scroll=chatScrollbarPos;
            java.util.Map<Integer,Double> arrivals=new java.util.HashMap<>();
            for(var line:trimmedMessages) {
                Double started=mineastr$arrivals.get(line);
                if(started!=null)arrivals.put(line.addedTime(),started);
            }
            mineastr$updating=true;
            try { refreshTrimmedMessages(); }
            finally { mineastr$updating=false; }
            chatScrollbarPos=Math.clamp(scroll,0,Math.max(0,trimmedMessages.size()-getLinesPerPage()));
            for(var line:trimmedMessages) {
                Double started=arrivals.get(line.addedTime());
                if(started!=null)mineastr$arrivals.put(line,started);
            }
            return true;
        }
        return false;
    }

    @ModifyVariable(method = "addMessageToDisplayQueue", at = @At("HEAD"), argsOnly = true)
    private GuiMessage mineastr$layout(GuiMessage message) {
        int width = (int) (getWidth() / getScale());
        if (message.icon() != null) width -= message.icon().width + 6;
        return new GuiMessage(message.addedTime(), MineAstrChatLayout.format(message.content(), width,
                getLineHeight(), getLinesPerPage()), message.signature(), message.tag());
    }

    @Inject(method = "addMessageToDisplayQueue", at = @At("HEAD"))
    private void mineastr$beforeArrival(GuiMessage message, CallbackInfo ci) {
        mineastr$previous = trimmedMessages.isEmpty() ? null : trimmedMessages.getFirst();
    }

    @Inject(method = "addMessageToDisplayQueue", at = @At("RETURN"))
    private void mineastr$afterArrival(GuiMessage message, CallbackInfo ci) {
        if (mineastr$rebuilding) return;
        int inserted = mineastr$previous == null ? trimmedMessages.size() : trimmedMessages.indexOf(mineastr$previous);
        boolean hasPrevious = mineastr$previous != null && inserted >= 0;
        if (inserted < 0) inserted = trimmedMessages.size();
        double now = MineAstrChatEasing.now();
        for (int index = 0; index < inserted; index++) mineastr$arrivals.put(trimmedMessages.get(index), now);
        if (chatScrollbarPos > 0) {
            mineastr$scroll.offset(inserted);
        } else if (hasPrevious && mineastr$width >= 0 && MineAstrClientConfig.chatAnimationsEnabled()) {
            mineastr$insert.push(inserted, now, mineastr$arrivalDuration());
        }
    }

    @Inject(method = "refreshTrimmedMessages", at = @At("HEAD"))
    private void mineastr$beforeReflow(CallbackInfo ci) { mineastr$rebuilding = true; }

    @Inject(method = "refreshTrimmedMessages", at = @At("RETURN"))
    private void mineastr$afterReflow(CallbackInfo ci) {
        mineastr$rebuilding = false;
        if (!mineastr$updating) mineastr$resetMotion();
    }

    @Inject(method = {"clearMessages", "resetChatScroll"}, at = @At("RETURN"))
    private void mineastr$reset(CallbackInfo ci) { mineastr$resetMotion(); }

    @Unique private void mineastr$resetMotion() {
        mineastr$scroll.snap(chatScrollbarPos);
        mineastr$insert.clear();
        mineastr$arrivals.clear();
    }

    @Inject(method = "getHeight()I", at = @At("RETURN"), cancellable = true)
    private void mineastr$maxHeight(CallbackInfoReturnable<Integer> cir) {
        if (!MineAstrClientConfig.isLoaded()) return;
        int percent = MineAstrClientConfig.CHAT_MAX_HEIGHT_PERCENT.getAsInt();
        if (percent == 0) return;
        cir.setReturnValue(MineAstrChatGeometry.heightLimit(cir.getReturnValue(),
                minecraft.getWindow().getGuiScaledHeight(), getScale(), percent, getLineHeight()));
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void mineastr$frame(GuiGraphics graphics, int tick, int x, int y, boolean focused, CallbackInfo ci) {
        int width = getWidth(), pages = getLinesPerPage(), height = getLineHeight();
        int imageScale = MineAstrClientConfig.chatImageScale();
        double scale = getScale();
        if (mineastr$width >= 0 && (width != mineastr$width || pages != mineastr$pages
                || height != mineastr$height || imageScale != mineastr$imageScale || scale != mineastr$scale)) {
            int previousScroll = chatScrollbarPos;
            // Vanilla reflow auto-scrolls when focused; suspend it while rebuilding.
            chatScrollbarPos = 0;
            refreshTrimmedMessages();
            chatScrollbarPos = Math.clamp(previousScroll, 0, Math.max(0, trimmedMessages.size() - pages));
            mineastr$resetMotion();
        }
        mineastr$width = width; mineastr$pages = pages; mineastr$height = height;
        mineastr$imageScale = imageScale; mineastr$scale = scale;
        mineastr$now = MineAstrChatEasing.now();
        mineastr$clipActive = false;
        mineastr$current = null;
        int maximum = Math.max(0, trimmedMessages.size() - getLinesPerPage());
        int target = Math.clamp(chatScrollbarPos, 0, maximum);
        if (!MineAstrClientConfig.chatAnimationsEnabled() || !focused) mineastr$scroll.snap(target);
        else mineastr$scroll.target(target, mineastr$now,
                MineAstrClientConfig.isLoaded() ? MineAstrClientConfig.CHAT_SCROLL_DURATION.getAsInt() : 180);
        if (!MineAstrClientConfig.chatAnimationsEnabled()) mineastr$insert.clear();
        mineastr$position = Math.clamp(mineastr$scroll.value(mineastr$now), 0, maximum);
        mineastr$base = (int) Math.floor(mineastr$position);
        mineastr$insertRows = mineastr$insert.value(mineastr$now);
    }

    @Redirect(method = "render", at = @At(value = "FIELD", target = "Lnet/minecraft/client/gui/components/ChatComponent;chatScrollbarPos:I"))
    private int mineastr$renderIndex(ChatComponent component) { return mineastr$base; }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;getLinesPerPage()I"))
    private int mineastr$extraRow(ChatComponent component) { return getLinesPerPage() + 1 + (int) Math.ceil(mineastr$insertRows); }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Ljava/util/List;get(I)Ljava/lang/Object;"))
    private Object mineastr$line(List<?> lines, int index, GuiGraphics graphics, int tick, int x, int y, boolean focused) {
        mineastr$current = (GuiMessage.Line) lines.get(index);
        if (!mineastr$clipActive) { mineastr$clip(graphics); mineastr$clipActive = true; }
        return mineastr$current;
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getChatListener()Lnet/minecraft/client/multiplayer/chat/ChatListener;"))
    private void mineastr$endRows(GuiGraphics graphics, int tick, int x, int y, boolean focused, CallbackInfo ci) {
        if (mineastr$clipActive) { graphics.disableScissor(); mineastr$clipActive = false; }
        mineastr$current = null;
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)I"))
    private int mineastr$text(GuiGraphics graphics, Font font, FormattedCharSequence text, int x, int y, int color, Operation<Integer> original) {
        int animated = mineastr$color(color, mineastr$current);
        if ((animated >>> 24) < 4) return 0;
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(0, mineastr$offset(mineastr$current), 0);
            // Forward the existing renderer so other mods retain their item/text hooks.
            return original.call(graphics, font, MineAstrClientThemes.animate(text, mineastr$now), x, y, animated);
        } finally { graphics.pose().popPose(); }
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V"))
    private void mineastr$background(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color) {
        if (!mineastr$clipActive) { graphics.fill(x1, y1, x2, y2, color); return; }
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(0, mineastr$offset(mineastr$current), 0);
            graphics.fill(x1, y1, x2, y2, mineastr$color(color, mineastr$current));
        } finally { graphics.pose().popPose(); }
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIIII)V"))
    private void mineastr$scrollbar(GuiGraphics graphics, int x1, int y1, int x2, int y2, int z, int color) {
        int rows = trimmedMessages.size();
        if (rows == 0) return;
        int visible = Math.min(getLinesPerPage(), rows);
        double bottom = Math.floor((graphics.guiHeight() - 40) / getScale());
        double top = bottom - mineastr$position * visible * getLineHeight() / rows;
        int thumb = Math.max(1, visible * visible * getLineHeight() / rows);
        int rounded = (int) Math.floor(top);
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(0, (float) (top - rounded), 0);
            graphics.fill(x1, rounded, x2, rounded - thumb, z, color);
        } finally { graphics.pose().popPose(); }
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;drawTagIcon(Lnet/minecraft/client/gui/GuiGraphics;IILnet/minecraft/client/GuiMessageTag$Icon;)V"))
    private void mineastr$tag(ChatComponent component, GuiGraphics graphics, int x, int y, GuiMessageTag.Icon icon) {
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(0, mineastr$offset(mineastr$current), 0);
            drawTagIcon(graphics, x, y, icon);
        } finally { graphics.pose().popPose(); }
    }

    @Unique private int mineastr$arrivalDuration() {
        return MineAstrClientConfig.isLoaded() ? MineAstrClientConfig.CHAT_ARRIVAL_DURATION.getAsInt() : 200;
    }

    @Unique private double mineastr$progress(GuiMessage.Line line) {
        if (!MineAstrClientConfig.chatAnimationsEnabled()) return 1;
        Double started = mineastr$arrivals.get(line);
        return started == null ? 1 : MineAstrChatEasing.gentle((mineastr$now - started) / mineastr$arrivalDuration());
    }

    @Unique private float mineastr$offset(GuiMessage.Line line) {
        int rise = MineAstrClientConfig.isLoaded() ? MineAstrClientConfig.CHAT_ARRIVAL_DISTANCE.getAsInt() : 4;
        return (float) ((mineastr$position - mineastr$base + mineastr$insertRows) * getLineHeight()
                + (1 - mineastr$progress(line)) * rise);
    }

    @Unique private int mineastr$color(int color, GuiMessage.Line line) {
        return (color & 0xFFFFFF) | ((int) ((color >>> 24) * mineastr$progress(line)) << 24);
    }

    @Unique private void mineastr$clip(GuiGraphics graphics) {
        double scale = getScale();
        int bottom = (int) Math.floor((graphics.guiHeight() - 40) / scale);
        graphics.enableScissor(0, Math.max(0, (int) Math.floor((bottom - getLinesPerPage() * getLineHeight()) * scale)),
                Math.min(graphics.guiWidth(), getWidth() + (int) Math.ceil(14 * scale)), (int) Math.ceil(bottom * scale));
    }

    @Inject(method = "getMessageLineIndexAt", at = @At("HEAD"), cancellable = true)
    private void mineastr$hit(double x, double y, CallbackInfoReturnable<Integer> cir) {
        if (!MineAstrClientConfig.chatAnimationsEnabled()) return;
        int index = -1;
        if (isChatFocused() && !isChatHidden() && x >= -4 && x <= Math.floor(getWidth() / getScale())
                && y >= 0 && y < getLinesPerPage()) {
            index = (int) Math.floor(y + mineastr$position + mineastr$insertRows);
            if (index >= 0 && index < trimmedMessages.size()) {
                double extra = mineastr$offset(trimmedMessages.get(index)) / getLineHeight()
                        - (mineastr$position - mineastr$base + mineastr$insertRows);
                index = (int) Math.floor(y + mineastr$position + mineastr$insertRows + extra);
            }
            if (index < 0 || index >= trimmedMessages.size()) index = -1;
        }
        cir.setReturnValue(index);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void mineastr$media(GuiGraphics graphics, int tick, int mouseX, int mouseY, boolean focused, CallbackInfo ci) {
        if (mineastr$clipActive) { graphics.disableScissor(); mineastr$clipActive = false; }
        MineAstrChatImages.beginFrame();
        if (isChatHidden()) return;
        float scale = (float) getScale();
        int bottom = (int) Math.floor((graphics.guiHeight() - 40) / scale);
        int height = getLineHeight();
        int baseline = (int) Math.round(-8 * (minecraft.options.chatLineSpacing().get() + 1)
                + 4 * minecraft.options.chatLineSpacing().get());
        mineastr$clip(graphics);
        graphics.pose().pushPose();
        graphics.pose().scale(scale, scale, 1);
        graphics.pose().translate(4, 0, 60);
        try {
            for (int index = 0; index <= getLinesPerPage() + (int) Math.ceil(mineastr$insertRows) && index + mineastr$base < trimmedMessages.size(); index++) {
                var line = trimmedMessages.get(index + mineastr$base);
                int age = tick - line.addedTime();
                if (age >= 200 && !focused) continue;
                var decorations = MineAstrChatLayout.decorations(line.content());
                if (decorations.image() == null && decorations.platform() == null) continue;
                double fade = focused ? 1 : Math.clamp((1 - age / 200.0) * 10, 0, 1);
                float alpha = (float) ((focused ? 1 : fade * fade) * mineastr$progress(line)
                        * (minecraft.options.chatOpacity().get() * .9 + .1));
                if (alpha * 255 <= 3) continue;
                float offset = mineastr$offset(line);
                graphics.pose().pushPose();
                try {
                    graphics.pose().translate(0, offset, 0);
                    int lineBottom = bottom - index * height;
                    if (decorations.platform() != null) MineAstrChatIcons.render(graphics, decorations.platform(), lineBottom + baseline - 1, alpha);
                    if (decorations.image() != null) MineAstrChatImages.renderRow(graphics, decorations.image(), lineBottom, height, scale, alpha, offset, bottom - getLinesPerPage() * height, bottom);
                } finally { graphics.pose().popPose(); }
            }
        } finally { graphics.pose().popPose(); graphics.disableScissor(); }
    }
}
