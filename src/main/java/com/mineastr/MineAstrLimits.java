package com.mineastr;

import java.time.Duration;

/**
 * 协议长度、速率和超时上限；改变这些值时检查兼容性。
 * Protocol length, rate and timeout bounds; review compatibility when changing them.
 */
final class MineAstrLimits {
    private MineAstrLimits() {}

    static final int PROTOCOL_VERSION = 1;
    static final int MAX_INBOUND_WS_CHARS = 2 * 1024 * 1024;
    static final int MAX_LOG_MESSAGE_CHARS = 200;
    static final int MAX_BROADCAST_CONTENT_LENGTH = 2000;
    static final int MAX_BROADCAST_SENDER_LENGTH = 64;
    static final int SCREENSHOT_TIMEOUT_SECONDS = 30;
    static final int SCREENSHOT_MAX_CHUNKS = 64;
    static final Duration WEBSOCKET_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final int MAX_EVENT_TEXT_LENGTH = 512;
    static final int MAX_NATIVE_CHAT_TARGET_LANGUAGES = 8;
    static final int MAX_NATIVE_CHAT_PACKET_LENGTH = 256;
    static final int MIN_NATIVE_CHAT_TIMEOUT_MS = 1_000;
    static final int MAX_NATIVE_CHAT_TIMEOUT_MS = 65_000;
    static final int DEFAULT_NATIVE_CHAT_TIMEOUT_MS = 20_000;
    static final int MAX_PENDING_NATIVE_CHATS = 256;
    static final int MAX_NATIVE_CHAT_RATE = 8;
    static final long NATIVE_CHAT_RATE_WINDOW_MS = 10_000L;
    static final double SIGN_ADMIN_TARGET_DISTANCE = 8.0D;
}
