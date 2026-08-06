package com.mineastr;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class MineAstrPayloads {
    public static final int MAX_REASON_LENGTH = 512;
    public static final int MAX_ERROR_LENGTH = 512;
    public static final int MAX_MIME_LENGTH = 64;
    public static final int MAX_CHUNK_BYTES = 24 * 1024;
    public static final int MAX_SIGN_FINGERPRINT_LENGTH = 128;
    public static final int MAX_SIGN_TRANSLATION_ENTRIES = 32;
    public static final int MAX_SIGN_TRANSLATION_TEXT_LENGTH = 512;
    public static final int MAX_IMAGE_TRANSLATION_BYTES = 768 * 1024;
    public static final int MAX_IMAGE_TRANSLATION_CONTEXT_LENGTH = 2048;
    public static final int MAX_IMAGE_TRANSLATION_PROMPT_LENGTH = 4096;

    private MineAstrPayloads() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(MineAstr.MODID, path));
    }

    public record ClientHello(String modVersion, boolean screenshotSupported) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ClientHello> TYPE = MineAstrPayloads.type("client_hello");
        public static final StreamCodec<RegistryFriendlyByteBuf, ClientHello> CODEC =
                StreamCodec.ofMember(ClientHello::write, ClientHello::read);

        private static ClientHello read(RegistryFriendlyByteBuf buffer) {
            return new ClientHello(buffer.readUtf(64), buffer.readBoolean());
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(modVersion, 64);
            buffer.writeBoolean(screenshotSupported);
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record TranslationPreferences(boolean translationsEnabled, boolean showOriginal)
            implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<TranslationPreferences> TYPE =
                MineAstrPayloads.type("translation_preferences");
        public static final StreamCodec<RegistryFriendlyByteBuf, TranslationPreferences> CODEC =
                StreamCodec.ofMember(TranslationPreferences::write, TranslationPreferences::read);

        private static TranslationPreferences read(RegistryFriendlyByteBuf buffer) {
            return new TranslationPreferences(buffer.readBoolean(), buffer.readBoolean());
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeBoolean(translationsEnabled);
            buffer.writeBoolean(showOriginal);
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SignTranslationQuery(
            BlockPos pos,
            boolean front,
            String sourceFingerprint) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SignTranslationQuery> TYPE =
                MineAstrPayloads.type("sign_translation_query");
        public static final StreamCodec<RegistryFriendlyByteBuf, SignTranslationQuery> CODEC =
                StreamCodec.ofMember(SignTranslationQuery::write, SignTranslationQuery::read);

        private static SignTranslationQuery read(RegistryFriendlyByteBuf buffer) {
            return new SignTranslationQuery(
                    BlockPos.STREAM_CODEC.decode(buffer),
                    buffer.readBoolean(),
                    buffer.readUtf(MAX_SIGN_FINGERPRINT_LENGTH));
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            BlockPos.STREAM_CODEC.encode(buffer, pos);
            buffer.writeBoolean(front);
            buffer.writeUtf(sourceFingerprint, MAX_SIGN_FINGERPRINT_LENGTH);
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SignTranslationResult(
            BlockPos pos,
            boolean front,
            String sourceFingerprint,
            Map<String, String> translations,
            boolean showOriginal,
            boolean ok) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SignTranslationResult> TYPE =
                MineAstrPayloads.type("sign_translation_result");
        public static final StreamCodec<RegistryFriendlyByteBuf, SignTranslationResult> CODEC =
                StreamCodec.ofMember(SignTranslationResult::write, SignTranslationResult::read);

        private static SignTranslationResult read(RegistryFriendlyByteBuf buffer) {
            return new SignTranslationResult(
                    BlockPos.STREAM_CODEC.decode(buffer),
                    buffer.readBoolean(),
                    buffer.readUtf(MAX_SIGN_FINGERPRINT_LENGTH),
                    readTranslations(buffer),
                    buffer.readBoolean(),
                    buffer.readBoolean());
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            BlockPos.STREAM_CODEC.encode(buffer, pos);
            buffer.writeBoolean(front);
            buffer.writeUtf(sourceFingerprint, MAX_SIGN_FINGERPRINT_LENGTH);
            writeTranslations(buffer, translations);
            buffer.writeBoolean(showOriginal);
            buffer.writeBoolean(ok);
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ImageTranslationQuery(
            String requestId,
            String mimeType,
            String targetLanguages,
            String context,
            String prompt,
            byte[] imageBytes) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ImageTranslationQuery> TYPE =
                MineAstrPayloads.type("image_translation_query");
        public static final StreamCodec<RegistryFriendlyByteBuf, ImageTranslationQuery> CODEC =
                StreamCodec.ofMember(ImageTranslationQuery::write, ImageTranslationQuery::read);

        private static ImageTranslationQuery read(RegistryFriendlyByteBuf buffer) {
            return new ImageTranslationQuery(
                    buffer.readUtf(64),
                    buffer.readUtf(MAX_MIME_LENGTH),
                    buffer.readUtf(256),
                    buffer.readUtf(MAX_IMAGE_TRANSLATION_CONTEXT_LENGTH),
                    buffer.readUtf(MAX_IMAGE_TRANSLATION_PROMPT_LENGTH),
                    buffer.readByteArray(MAX_IMAGE_TRANSLATION_BYTES));
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(requestId, 64);
            buffer.writeUtf(mimeType, MAX_MIME_LENGTH);
            buffer.writeUtf(targetLanguages, 256);
            buffer.writeUtf(context, MAX_IMAGE_TRANSLATION_CONTEXT_LENGTH);
            buffer.writeUtf(prompt, MAX_IMAGE_TRANSLATION_PROMPT_LENGTH);
            buffer.writeByteArray(imageBytes);
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ImageTranslationResult(
            String requestId,
            String sourceLanguage,
            String sourceText,
            Map<String, String> translations,
            boolean showOriginal,
            boolean ok,
            String error) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ImageTranslationResult> TYPE =
                MineAstrPayloads.type("image_translation_result");
        public static final StreamCodec<RegistryFriendlyByteBuf, ImageTranslationResult> CODEC =
                StreamCodec.ofMember(ImageTranslationResult::write, ImageTranslationResult::read);

        private static ImageTranslationResult read(RegistryFriendlyByteBuf buffer) {
            return new ImageTranslationResult(
                    buffer.readUtf(64),
                    buffer.readUtf(32),
                    buffer.readUtf(MAX_SIGN_TRANSLATION_TEXT_LENGTH),
                    readTranslations(buffer),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readUtf(MAX_ERROR_LENGTH));
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(requestId, 64);
            buffer.writeUtf(sourceLanguage, 32);
            buffer.writeUtf(sourceText, MAX_SIGN_TRANSLATION_TEXT_LENGTH);
            writeTranslations(buffer, translations);
            buffer.writeBoolean(showOriginal);
            buffer.writeBoolean(ok);
            buffer.writeUtf(error, MAX_ERROR_LENGTH);
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ScreenshotRequest(
            String requestId,
            String reason,
            int maxWidth,
            int maxHeight,
            int maxBytes,
            String format) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ScreenshotRequest> TYPE = MineAstrPayloads.type("screenshot_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, ScreenshotRequest> CODEC =
                StreamCodec.ofMember(ScreenshotRequest::write, ScreenshotRequest::read);

        private static ScreenshotRequest read(RegistryFriendlyByteBuf buffer) {
            return new ScreenshotRequest(
                    buffer.readUtf(64),
                    buffer.readUtf(MAX_REASON_LENGTH),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readUtf(16));
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(requestId, 64);
            buffer.writeUtf(reason, MAX_REASON_LENGTH);
            buffer.writeVarInt(maxWidth);
            buffer.writeVarInt(maxHeight);
            buffer.writeVarInt(maxBytes);
            buffer.writeUtf(format, 16);
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ScreenshotChunk(
            String requestId,
            int index,
            int totalChunks,
            int width,
            int height,
            int totalBytes,
            long capturedAtMs,
            String mimeType,
            byte[] bytes) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ScreenshotChunk> TYPE = MineAstrPayloads.type("screenshot_chunk");
        public static final StreamCodec<RegistryFriendlyByteBuf, ScreenshotChunk> CODEC =
                StreamCodec.ofMember(ScreenshotChunk::write, ScreenshotChunk::read);

        private static ScreenshotChunk read(RegistryFriendlyByteBuf buffer) {
            return new ScreenshotChunk(
                    buffer.readUtf(64),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readLong(),
                    buffer.readUtf(MAX_MIME_LENGTH),
                    buffer.readByteArray(MAX_CHUNK_BYTES));
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(requestId, 64);
            buffer.writeVarInt(index);
            buffer.writeVarInt(totalChunks);
            buffer.writeVarInt(width);
            buffer.writeVarInt(height);
            buffer.writeVarInt(totalBytes);
            buffer.writeLong(capturedAtMs);
            buffer.writeUtf(mimeType, MAX_MIME_LENGTH);
            buffer.writeByteArray(bytes);
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ScreenshotError(String requestId, String code, String message) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ScreenshotError> TYPE = MineAstrPayloads.type("screenshot_error");
        public static final StreamCodec<RegistryFriendlyByteBuf, ScreenshotError> CODEC =
                StreamCodec.ofMember(ScreenshotError::write, ScreenshotError::read);

        private static ScreenshotError read(RegistryFriendlyByteBuf buffer) {
            return new ScreenshotError(buffer.readUtf(64), buffer.readUtf(64), buffer.readUtf(MAX_ERROR_LENGTH));
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(requestId, 64);
            buffer.writeUtf(code, 64);
            buffer.writeUtf(message, MAX_ERROR_LENGTH);
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static Map<String, String> readTranslations(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_SIGN_TRANSLATION_ENTRIES) {
            throw new IllegalArgumentException("告示牌翻译条目数量超出限制");
        }
        Map<String, String> translations = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            String language = buffer.readUtf(32).strip().replace('-', '_').toLowerCase(java.util.Locale.ROOT);
            String text = buffer.readUtf(MAX_SIGN_TRANSLATION_TEXT_LENGTH);
            if (!language.isBlank() && !text.isBlank()) {
                translations.put(language, text);
            }
        }
        return Map.copyOf(translations);
    }

    private static void writeTranslations(RegistryFriendlyByteBuf buffer, Map<String, String> translations) {
        Map<String, String> safe = translations == null ? Map.of() : translations;
        int count = Math.min(MAX_SIGN_TRANSLATION_ENTRIES, safe.size());
        buffer.writeVarInt(count);
        int written = 0;
        for (var entry : safe.entrySet()) {
            if (written++ >= count) {
                break;
            }
            buffer.writeUtf(entry.getKey(), 32);
            buffer.writeUtf(entry.getValue(), MAX_SIGN_TRANSLATION_TEXT_LENGTH);
        }
    }
}
