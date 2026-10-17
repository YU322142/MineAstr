package com.mineastr;

import com.google.gson.JsonObject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

import static com.mineastr.MineAstrLimits.MAX_LOG_MESSAGE_CHARS;

/**
 * 协议输入的有界读取与媒体签名检查；无连接状态、无 I/O。
 * Bounded protocol values and media signature validation; stateless and I/O-free.
 */
final class MineAstrProtocolValues {
    private MineAstrProtocolValues() {}

    static String getString(JsonObject payload, String key, String defaultValue) {
        if (!payload.has(key) || payload.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return payload.get(key).getAsString();
        } catch (RuntimeException exc) {
            return defaultValue;
        }
    }

    static int getInt(JsonObject payload, String key, int defaultValue, int min, int max) {
        int value = defaultValue;
        if (payload.has(key) && !payload.get(key).isJsonNull()) {
            try {
                value = payload.get(key).getAsInt();
            } catch (RuntimeException ignored) {
                value = defaultValue;
            }
        }
        return Math.max(min, Math.min(max, value));
    }

    static long getLong(JsonObject payload, String key, long defaultValue) {
        if (!payload.has(key) || payload.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return payload.get(key).getAsLong();
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }

    static double getDouble(JsonObject payload, String key, double defaultValue, double min, double max) {
        double value = defaultValue;
        if (payload.has(key) && !payload.get(key).isJsonNull()) {
            try {
                value = payload.get(key).getAsDouble();
            } catch (RuntimeException ignored) {
                value = defaultValue;
            }
        }
        if (!Double.isFinite(value)) {
            value = defaultValue;
        }
        return Math.max(min, Math.min(max, value));
    }

    static boolean getBoolean(JsonObject payload, String key, boolean defaultValue) {
        if (!payload.has(key) || payload.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return payload.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }

    static String trimContent(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String trimmed = text.replace("\r", "").strip();
        if (trimmed.length() > maxLength) {
            return trimmed.substring(0, maxLength);
        }
        return trimmed;
    }

    static String trimFlatContent(String text, int maxLength) {
        String trimmed = trimContent(text, maxLength).replace('\n', ' ').strip();
        if (trimmed.length() > maxLength) {
            return trimmed.substring(0, maxLength);
        }
        return trimmed;
    }

    static String safeErrorMessage(Throwable throwable) {
        String message = throwable == null ? "未知错误" : throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable == null ? "未知错误" : throwable.getClass().getSimpleName();
        }
        return trimFlatContent(message, 256);
    }

    static String shortenForLog(String message) {
        if (message == null) {
            return "";
        }
        String flattened = message.replace('\r', ' ').replace('\n', ' ');
        if (flattened.length() <= MAX_LOG_MESSAGE_CHARS) {
            return flattened;
        }
        return flattened.substring(0, MAX_LOG_MESSAGE_CHARS) + "...";
    }

    static String sha256(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exc) {
            throw new IllegalStateException("SHA-256 不可用", exc);
        }
    }

    static String sha256(byte[] value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value));
        } catch (java.security.NoSuchAlgorithmException exc) {
            throw new IllegalStateException("SHA-256 不可用", exc);
        }
    }

    static String safePublicImageUrl(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(value.strip());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    || host == null
                    || host.isBlank()) {
                return "";
            }
            String result = uri.toASCIIString();
            return result.length() <= MineAstrPayloads.MAX_BOT_IMAGE_URL_LENGTH ? result : "";
        } catch (IllegalArgumentException exc) {
            return "";
        }
    }

    static boolean looksLikeJpeg(byte[] imageBytes) {
        return imageBytes != null
                && imageBytes.length >= 4
                && (imageBytes[0] & 0xFF) == 0xFF
                && (imageBytes[1] & 0xFF) == 0xD8
                && (imageBytes[imageBytes.length - 2] & 0xFF) == 0xFF
                && (imageBytes[imageBytes.length - 1] & 0xFF) == 0xD9;
    }

    static boolean isSupportedImage(byte[] imageBytes, String mimeType) {
        if (imageBytes == null || imageBytes.length < 4 || mimeType == null
                || !mimeType.strip().toLowerCase(Locale.ROOT).startsWith("image/")) {
            return false;
        }
        return looksLikeJpeg(imageBytes)
                || startsWith(imageBytes, new int[] {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})
                || startsWith(imageBytes, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                || startsWith(imageBytes, "GIF89a".getBytes(StandardCharsets.US_ASCII))
                || startsWith(imageBytes, new int[] {0x42, 0x4D})
                || startsWith(imageBytes, new int[] {0x00, 0x00, 0x01, 0x00})
                || (imageBytes.length >= 12
                        && startsWith(imageBytes, "RIFF".getBytes(StandardCharsets.US_ASCII))
                        && imageBytes[8] == 'W'
                        && imageBytes[9] == 'E'
                        && imageBytes[10] == 'B'
                        && imageBytes[11] == 'P');
    }

    static boolean startsWith(byte[] value, byte[] prefix) {
        if (value == null || value.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    static boolean startsWith(byte[] value, int[] prefix) {
        if (value == null || value.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if ((value[index] & 0xFF) != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    static String sanitizeBotContent(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        String cleaned = IMAGE_PLACEHOLDER_PATTERN.matcher(content).replaceAll("");
        return cleaned.replaceAll("[ \\t]{2,}", " ").strip();
    }

    /**
     * 剥离 AstrBot 聊天 content 中的图片占位符及服务器端文件路径。
     * 匹配 "[图片]" 标记及其后紧跟的绝对路径（Linux / Windows）、HTTP URL 或 file URL，
     * 防止 AstrBot 服务器端临时路径（如 /opt/AstrBot/data/temp/...）泄露到游戏聊天栏。
     * 剥离后压缩多余空白。若 content 原本仅为图片占位符，则返回空字符串。
     */
    private static final Pattern IMAGE_PLACEHOLDER_PATTERN = Pattern.compile(
            "\\[图片\\]\\s*(?:/\\S+|[A-Za-z]:[\\\\/]\\S+|https?://\\S+|file://\\S+)?");
}
