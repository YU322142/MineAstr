package com.mineastr.mixin;

import com.mineastr.MineAstrChatScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CommandSuggestions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Native completion polls the mouse directly; convert it to the editor's logical canvas. */
@Mixin(CommandSuggestions.SuggestionsList.class)
public abstract class MineAstrChatSuggestionsMixin {
    @ModifyArg(method = "mouseScrolled", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Rect2i;contains(II)Z"), index = 0)
    private int mineastr$scrollX(int x) { return mineastr$logical(x); }
    @ModifyArg(method = "mouseScrolled", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Rect2i;contains(II)Z"), index = 1)
    private int mineastr$scrollY(int y) { return mineastr$logical(y); }
    private static int mineastr$logical(int position) {
        return Minecraft.getInstance().screen instanceof MineAstrChatScreen chat
                ? (int) Math.floor(position / chat.inputScale()) : position;
    }
}
