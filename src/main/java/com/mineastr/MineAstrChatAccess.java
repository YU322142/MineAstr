package com.mineastr;

import java.util.UUID;
import net.minecraft.network.chat.Component;

/**
 * Implemented on ChatComponent; replaces history without adding a second message.
 * <p>Mixin 实现的聊天历史访问契约；替换原消息而不添加重复消息。<br>
 * Expose mixin-backed history replacement without inserting duplicate messages.</p>
 */
public interface MineAstrChatAccess {
    boolean mineastr$replaceNative(UUID id, Component content);
    void mineastr$scrollPixels(double pixels);
    boolean mineastr$chatClick(double x, double y, int button);
    boolean mineastr$chatDrag(double y, int button);
    boolean mineastr$chatRelease(int button);
}
