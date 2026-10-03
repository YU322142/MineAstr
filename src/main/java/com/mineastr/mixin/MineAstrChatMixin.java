package com.mineastr.mixin;

import com.mineastr.MineAstrChatImages;
import com.mineastr.MineAstrChatLayout;
import java.util.List;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
public abstract class MineAstrChatMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private List<GuiMessage.Line> trimmedMessages;
    @Shadow private int chatScrollbarPos;
    @Shadow public abstract int getWidth();
    @Shadow public abstract double getScale();
    @Shadow public abstract int getLinesPerPage();
    @Shadow protected abstract int getLineHeight();
    @Shadow protected abstract boolean isChatHidden();

    @ModifyVariable(method = "addMessageToDisplayQueue", at = @At("HEAD"), argsOnly = true)
    private GuiMessage mineastr$layout(GuiMessage message) {
        int width = (int) (getWidth() / getScale());
        if (message.icon() != null) width -= message.icon().width + 6;
        return new GuiMessage(message.addedTime(), MineAstrChatLayout.format(message.content(), width,
                getLineHeight(), getLinesPerPage()), message.signature(), message.tag());
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void mineastr$images(GuiGraphics graphics, int tick, int mouseX, int mouseY, boolean focused, CallbackInfo ci) {
        if (isChatHidden()) return;
        float scale = (float) getScale();
        int bottom = (int) Math.floor((graphics.guiHeight() - 40) / scale);
        int height = getLineHeight();
        graphics.pose().pushPose();
        graphics.pose().scale(scale, scale, 1);
        graphics.pose().translate(4, 0, 60);
        try {
            for (int index = 0; index < getLinesPerPage() && index + chatScrollbarPos < trimmedMessages.size(); index++) {
                var line = trimmedMessages.get(index + chatScrollbarPos);
                int age = tick - line.addedTime();
                if (age >= 200 && !focused) continue;
                var row = MineAstrChatLayout.imageRow(line.content());
                if (row == null) continue;
                double fade = focused ? 1 : Math.clamp((1 - age / 200.0) * 10, 0, 1);
                float alpha = (float) ((focused ? 1 : fade * fade) * (minecraft.options.chatOpacity().get() * .9 + .1));
                if (alpha * 255 > 3) MineAstrChatImages.renderRow(graphics, row, bottom - index * height, height, scale, alpha);
            }
        } finally { graphics.pose().popPose(); }
    }
}
