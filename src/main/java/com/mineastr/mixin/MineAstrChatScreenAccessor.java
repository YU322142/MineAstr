package com.mineastr.mixin;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChatScreen.class)
public interface MineAstrChatScreenAccessor {
    @Accessor("input") EditBox mineastr$input();
    @Accessor("initial") void mineastr$initial(String draft);
    @Accessor("initial") String mineastr$initial();
    @Accessor("commandSuggestions") CommandSuggestions mineastr$suggestions();
    @Accessor("commandSuggestions") void mineastr$suggestions(CommandSuggestions suggestions);
}
