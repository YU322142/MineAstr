package com.mineastr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

final class MineAstrBotImageClient {
    private static final Map<String, Assembly> ASSEMBLIES = new ConcurrentHashMap<>();
    private static final Duration ASSEMBLY_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration CACHE_RETENTION = Duration.ofDays(7);
    private static volatile long nextCacheCleanupAtMs;

    private MineAstrBotImageClient() {
    }

    static void handle(MineAstrPayloads.BotImageChunk chunk) {
        if (!MineAstrClient.isChatImageAvailable()
                || !MineAstrClientConfig.ACCEPT_BOT_IMAGES.getAsBoolean()) {
            return;
        }
        cleanupAssemblies();
        String messageId = safeToken(chunk.messageId(), 64);
        String senderName = safeLabel(chunk.senderName(), 64, "AstrBot");
        String imageName = safeLabel(chunk.imageName(), MineAstrPayloads.MAX_BOT_IMAGE_NAME_LENGTH, "image");
        String sourceUrl = safeHttpUrl(chunk.sourceUrl());

        if (chunk.totalChunks() == 0) {
            if (!sourceUrl.isBlank()) {
                display(senderName, imageName, sourceUrl);
            }
            return;
        }
        if (messageId.isBlank()
                || chunk.index() < 0
                || chunk.totalChunks() < 1
                || chunk.totalChunks() > MineAstrPayloads.MAX_BOT_IMAGE_CHUNKS
                || chunk.index() >= chunk.totalChunks()
                || chunk.totalBytes() < 1
                || chunk.totalBytes() > MineAstrPayloads.MAX_BOT_IMAGE_BYTES
                || chunk.bytes() == null
                || chunk.bytes().length == 0
                || chunk.bytes().length > MineAstrPayloads.MAX_CHUNK_BYTES) {
            ASSEMBLIES.remove(messageId);
            return;
        }

        Assembly assembly = ASSEMBLIES.compute(messageId, (ignored, current) -> {
            if (current == null || !current.matches(chunk, senderName, imageName, sourceUrl)) {
                return new Assembly(chunk, senderName, imageName, sourceUrl);
            }
            return current;
        });
        if (!assembly.accept(chunk)) {
            ASSEMBLIES.remove(messageId, assembly);
            return;
        }
        byte[] image = assembly.complete();
        if (image == null || !ASSEMBLIES.remove(messageId, assembly)) {
            return;
        }
        if (!validImage(image, chunk.mimeType())) {
            MineAstr.LOGGER.warn("MineAstr 拒绝了格式无效的 Bot 图片：{}", imageName);
            return;
        }
        String actualSha = sha256(image);
        String declaredSha = safeSha256(chunk.sha256());
        if (!declaredSha.isBlank() && !declaredSha.equals(actualSha)) {
            MineAstr.LOGGER.warn("MineAstr 拒绝了哈希不匹配的 Bot 图片：{}", imageName);
            return;
        }
        try {
            Path cached = writeCache(image, actualSha, chunk.mimeType());
            display(senderName, imageName, cached.toUri().toASCIIString());
        } catch (IOException exc) {
            MineAstr.LOGGER.warn("MineAstr 写入 Bot 图片缓存失败：{}", exc.getMessage());
        }
    }

    static void clear() {
        ASSEMBLIES.clear();
    }

    private static void display(String senderName, String imageName, String url) {
        String safeUrl = url.replace(",", "%2C").replace("[", "%5B").replace("]", "%5D");
        String code = "[[CICode,url=" + safeUrl + ",name=" + imageName + "]]";
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui != null) {
            minecraft.gui.getChat().addMessage(Component.literal("[" + senderName + "] " + code));
        }
    }

    private static Path writeCache(byte[] image, String sha256, String mimeType) throws IOException {
        Minecraft minecraft = Minecraft.getInstance();
        Path directory = minecraft.gameDirectory.toPath()
                .resolve("cache")
                .resolve("mineastr")
                .resolve("chat-images");
        Files.createDirectories(directory);
        cleanupCache(directory);
        Path target = directory.resolve(sha256 + extension(mimeType));
        if (Files.isRegularFile(target) && Files.size(target) == image.length) {
            return target;
        }
        Path temporary = directory.resolve(sha256 + ".tmp");
        Files.write(temporary, image);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exc) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private static void cleanupCache(Path directory) {
        long now = System.currentTimeMillis();
        if (now < nextCacheCleanupAtMs) {
            return;
        }
        nextCacheCleanupAtMs = now + Duration.ofHours(6).toMillis();
        Instant cutoff = Instant.ofEpochMilli(now).minus(CACHE_RETENTION);
        try (var paths = Files.list(directory)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                try {
                    if (Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(path);
                    }
                } catch (IOException ignored) {
                    // Cache cleanup is best-effort and never blocks chat rendering.
                }
            });
        } catch (IOException ignored) {
            // Cache cleanup is best-effort and never blocks chat rendering.
        }
    }

    private static void cleanupAssemblies() {
        long cutoff = System.currentTimeMillis() - ASSEMBLY_TIMEOUT.toMillis();
        ASSEMBLIES.entrySet().removeIf(entry -> entry.getValue().createdAtMs < cutoff);
    }

    private static boolean validImage(byte[] data, String mimeType) {
        String mime = mimeType == null ? "" : mimeType.strip().toLowerCase(Locale.ROOT);
        if (!mime.startsWith("image/")) {
            return false;
        }
        return startsWith(data, new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})
                || startsWith(data, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})
                || startsWith(data, "GIF87a".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                || startsWith(data, "GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                || startsWith(data, new byte[] {0x42, 0x4D})
                || startsWith(data, new byte[] {0x00, 0x00, 0x01, 0x00})
                || (data.length >= 12
                        && startsWith(data, "RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                        && Arrays.equals(
                                Arrays.copyOfRange(data, 8, 12),
                                "WEBP".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data == null || data.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (data[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static String extension(String mimeType) {
        return switch ((mimeType == null ? "" : mimeType).strip().toLowerCase(Locale.ROOT)) {
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/bmp" -> ".bmp";
            case "image/webp" -> ".webp";
            case "image/x-icon", "image/vnd.microsoft.icon" -> ".ico";
            default -> ".jpg";
        };
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exc) {
            throw new IllegalStateException("SHA-256 unavailable", exc);
        }
    }

    private static String safeSha256(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return normalized.matches("[0-9a-f]{64}") ? normalized : "";
    }

    private static String safeHttpUrl(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.strip());
            String scheme = uri.getScheme();
            if (("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    && uri.getHost() != null) {
                return uri.toASCIIString();
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid URLs are silently ignored; never render the raw value.
        }
        return "";
    }

    private static String safeToken(String value, int maxLength) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() > maxLength) {
            normalized = normalized.substring(0, maxLength);
        }
        return normalized.matches("[A-Za-z0-9._:-]+") ? normalized : "";
    }

    private static String safeLabel(String value, int maxLength, String fallback) {
        String normalized = value == null ? "" : value.replaceAll("[\\p{Cntrl},\\[\\]]+", "_").strip();
        if (normalized.isBlank()) {
            normalized = fallback;
        }
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private static final class Assembly {
        private final String senderName;
        private final String imageName;
        private final String mimeType;
        private final String sourceUrl;
        private final String sha256;
        private final int totalChunks;
        private final int totalBytes;
        private final byte[][] chunks;
        private final long createdAtMs = System.currentTimeMillis();
        private int receivedChunks;
        private int receivedBytes;

        private Assembly(
                MineAstrPayloads.BotImageChunk first,
                String senderName,
                String imageName,
                String sourceUrl) {
            this.senderName = senderName;
            this.imageName = imageName;
            this.mimeType = first.mimeType();
            this.sourceUrl = sourceUrl;
            this.sha256 = safeSha256(first.sha256());
            this.totalChunks = first.totalChunks();
            this.totalBytes = first.totalBytes();
            this.chunks = new byte[totalChunks][];
        }

        private synchronized boolean matches(
                MineAstrPayloads.BotImageChunk chunk,
                String senderName,
                String imageName,
                String sourceUrl) {
            return this.senderName.equals(senderName)
                    && this.imageName.equals(imageName)
                    && this.mimeType.equals(chunk.mimeType())
                    && this.sourceUrl.equals(sourceUrl)
                    && this.sha256.equals(safeSha256(chunk.sha256()))
                    && this.totalChunks == chunk.totalChunks()
                    && this.totalBytes == chunk.totalBytes();
        }

        private synchronized boolean accept(MineAstrPayloads.BotImageChunk chunk) {
            if (chunks[chunk.index()] != null) {
                return Arrays.equals(chunks[chunk.index()], chunk.bytes());
            }
            if (receivedBytes + chunk.bytes().length > totalBytes) {
                return false;
            }
            chunks[chunk.index()] = Arrays.copyOf(chunk.bytes(), chunk.bytes().length);
            receivedChunks++;
            receivedBytes += chunk.bytes().length;
            return true;
        }

        private synchronized byte[] complete() {
            if (receivedChunks != totalChunks || receivedBytes != totalBytes) {
                return null;
            }
            try (ByteArrayOutputStream output = new ByteArrayOutputStream(totalBytes)) {
                for (byte[] chunk : chunks) {
                    if (chunk == null) {
                        return null;
                    }
                    output.write(chunk);
                }
                byte[] result = output.toByteArray();
                return result.length == totalBytes ? result : null;
            } catch (IOException impossible) {
                return null;
            }
        }
    }
}
