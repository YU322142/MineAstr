package com.mineastr;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.http.WebSocket;
import java.util.UUID;

public final class MineAstrProtocol {
    private static final Gson GSON = new Gson();
    private static final int MAX_LOG_MESSAGE_CHARS = 200;

    private MineAstrProtocol() {
    }

    public static void sendQueryResult(WebSocket socket, String messageId, String query, JsonObject data) {
        JsonObject payload = queryEnvelope(messageId, query, true);
        payload.add("data", data);
        sendJson(socket, payload);
    }

    public static void sendQueryError(WebSocket socket, String messageId, String query, String error) {
        JsonObject payload = queryEnvelope(messageId, query, false);
        payload.addProperty("error", error);
        sendJson(socket, payload);
    }

    public static JsonObject queryEnvelope(String messageId, String query, boolean ok) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "query_result");
        payload.addProperty("message_id", messageId);
        payload.addProperty("query", query);
        payload.addProperty("ok", ok);
        payload.addProperty("time_ms", System.currentTimeMillis());
        payload.addProperty("server_id", MineAstrConfig.SERVER_ID.get());
        payload.addProperty("server_name", MineAstrConfig.SERVER_NAME.get());
        return payload;
    }

    public static JsonObject eventEnvelope(String event) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "event");
        payload.addProperty("event", event);
        payload.addProperty("message_id", UUID.randomUUID().toString());
        payload.addProperty("time_ms", System.currentTimeMillis());
        payload.addProperty("server_id", MineAstrConfig.SERVER_ID.get());
        payload.addProperty("server_name", MineAstrConfig.SERVER_NAME.get());
        return payload;
    }

    public static void sendJson(WebSocket socket, JsonObject payload) {
        if (socket == null || socket.isOutputClosed()) {
            return;
        }
        try {
            socket.sendText(GSON.toJson(payload), true).whenComplete((ignored, throwable) -> {
                if (throwable != null) {
                    MineAstr.LOGGER.warn("MineAstr 发送 WebSocket 数据失败：{}", throwable.getMessage());
                }
            });
        } catch (RuntimeException exc) {
            MineAstr.LOGGER.warn("MineAstr 发送 WebSocket 数据失败：{}", exc.getMessage());
        }
    }

    public static String getString(JsonObject payload, String key, String defaultValue) {
        if (!payload.has(key) || payload.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return payload.get(key).getAsString();
        } catch (RuntimeException exc) {
            return defaultValue;
        }
    }

    public static int getInt(JsonObject payload, String key, int defaultValue, int min, int max) {
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

    public static long getLong(JsonObject payload, String key, long defaultValue) {
        if (!payload.has(key) || payload.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return payload.get(key).getAsLong();
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }

    public static double getDouble(JsonObject payload, String key, double defaultValue, double min, double max) {
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

    public static boolean getBoolean(JsonObject payload, String key, boolean defaultValue) {
        if (!payload.has(key) || payload.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return payload.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }

    public static String trimContent(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String trimmed = text.replace("\r", "").strip();
        if (trimmed.length() > maxLength) {
            return trimmed.substring(0, maxLength);
        }
        return trimmed;
    }

    public static String trimFlatContent(String text, int maxLength) {
        String trimmed = trimContent(text, maxLength).replace('\n', ' ').strip();
        if (trimmed.length() > maxLength) {
            return trimmed.substring(0, maxLength);
        }
        return trimmed;
    }

    public static String shortenForLog(String message) {
        if (message == null) {
            return "";
        }
        String flattened = message.replace('\r', ' ').replace('\n', ' ');
        if (flattened.length() <= MAX_LOG_MESSAGE_CHARS) {
            return flattened;
        }
        return flattened.substring(0, MAX_LOG_MESSAGE_CHARS) + "...";
    }

    public static boolean looksLikeJpeg(byte[] imageBytes) {
        return imageBytes != null
                && imageBytes.length >= 4
                && (imageBytes[0] & 0xFF) == 0xFF
                && (imageBytes[1] & 0xFF) == 0xD8
                && (imageBytes[imageBytes.length - 2] & 0xFF) == 0xFF
                && (imageBytes[imageBytes.length - 1] & 0xFF) == 0xD9;
    }

    public static String safeErrorMessage(Throwable throwable) {
        String message = throwable == null ? "未知错误" : throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable == null ? "未知错误" : throwable.getClass().getSimpleName();
        }
        return trimFlatContent(message, 256);
    }

    public static String screenshotErrorMessage(String code, String message) {
        String detail = message == null || message.isBlank() ? "客户端未提供详细原因。" : message;
        if ("denied".equals(code)) {
            return "玩家拒绝发送截图。";
        }
        if ("disabled".equals(code)) {
            return "客户端已在 MineAstr 配置中禁用截图发送。";
        }
        if ("not_in_game".equals(code)) {
            return "客户端尚未进入游戏，无法截图。";
        }
        return detail;
    }
}
