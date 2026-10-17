package com.mineastr.mixin;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * <p>原版聊天输入框的 Mixin 访问器；字段名依赖当前游戏版本。<br>
 * Expose the vanilla chat input through a mixin accessor tied to the current game mappings.</p>
 */
@Mixin(ChatScreen.class)
public interface MineAstrChatScreenAccessor {
    @Accessor("input") EditBox mineastr$input();
    @Accessor("initial") void mineastr$initial(String draft);
    @Accessor("initial") String mineastr$initial();
    @Accessor("commandSuggestions") CommandSuggestions mineastr$suggestions();
}
