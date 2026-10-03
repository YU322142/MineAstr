package com.mineastr;

import java.util.UUID;
import net.minecraft.network.chat.Component;

/** Implemented on ChatComponent; replaces history without adding a second message. */
public interface MineAstrChatAccess {
    boolean mineastr$replaceNative(UUID id, Component content);
}
