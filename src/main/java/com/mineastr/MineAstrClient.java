package com.mineastr;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.InputConstants;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import com.mineastr.api.MineAstrDisplayApi;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import org.lwjgl.glfw.GLFW;

public final class MineAstrClient implements ClientModInitializer {
    private static final String MIME_TYPE = "image/jpeg";
    private static final int MAX_SCREENSHOT_CHUNKS = 64;
    private static final ExecutorService SCREENSHOT_ENCODER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "MineAstr-ScreenshotEncoder");
        thread.setDaemon(true);
        return thread;
    });
    private static String pendingPromptRequestId;
    private static final ConcurrentMap<SignCacheKey, MineAstrPayloads.SignTranslationResult> SIGN_TRANSLATIONS =
            new ConcurrentHashMap<>();
    private static final Set<SignCacheKey> PENDING_SIGN_TRANSLATIONS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentMap<SignCacheKey, Long> SIGN_TRANSLATION_RETRY_AT = new ConcurrentHashMap<>();
    private static final long SIGN_TRANSLATION_RETRY_DELAY_MS = 5_000L;
    private static final ConcurrentMap<String, CompletableFuture<MineAstrPayloads.ImageTranslationResult>>
            IMAGE_TRANSLATION_REQUESTS = new ConcurrentHashMap<>();
    private static final int MAX_OVERLAY_WIDTH = 180;
    private static final float OVERLAY_SCALE = 0.025F;
    private static volatile TargetedSign TARGETED_SIGN;
    private static final KeyMapping OPEN_CONFIG_KEY = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.mineastr.open_config",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            KeyMapping.Category.MISC));

    @Override
    public void onInitializeClient() {
        MineAstrClientConfig.load();
        ClientPlayNetworking.registerGlobalReceiver(MineAstrPayloads.ScreenshotRequest.TYPE, (request, context) ->
                context.client().execute(() -> handleScreenshotRequestOnClientThread(context.client(), request)));
        ClientPlayNetworking.registerGlobalReceiver(MineAstrPayloads.SignTranslationResult.TYPE, (result, context) ->
                context.client().execute(() -> applySignTranslationResult(context.client(), result)));
        ClientPlayNetworking.registerGlobalReceiver(MineAstrPayloads.ImageTranslationResult.TYPE, (result, context) ->
                context.client().execute(() -> applyImageTranslationResult(result)));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            SIGN_TRANSLATIONS.clear();
            PENDING_SIGN_TRANSLATIONS.clear();
            SIGN_TRANSLATION_RETRY_AT.clear();
            TARGETED_SIGN = null;
            MineAstrDisplayApi.clear();
            sendPayloadToServer(new MineAstrPayloads.ClientHello(MineAstr.MOD_VERSION, true));
            sendTranslationPreferences();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            pendingPromptRequestId = null;
            SIGN_TRANSLATIONS.clear();
            PENDING_SIGN_TRANSLATIONS.clear();
            SIGN_TRANSLATION_RETRY_AT.clear();
            TARGETED_SIGN = null;
            IMAGE_TRANSLATION_REQUESTS.values().forEach(future ->
                    future.completeExceptionally(new IllegalStateException("Minecraft connection closed")));
            IMAGE_TRANSLATION_REQUESTS.clear();
            MineAstrDisplayApi.clear();
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_CONFIG_KEY.consumeClick()) {
                client.setScreen(new MineAstrConfigScreen(client.screen));
            }
            updateTargetedSign(client);
        });
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            renderTargetedSignOverlay(context);
            MineAstrDisplayApi.render(context);
        });
    }

    /**
     * Submit an image to the server-side MineAstr bridge for AstrBot's
     * multimodal translation model. The returned future completes on the
     * client thread when the result arrives.
     */
    public static CompletableFuture<MineAstrPayloads.ImageTranslationResult> requestImageTranslation(
            byte[] imageBytes,
            String mimeType,
            List<String> targetLanguages,
            String context,
            String prompt) {
        CompletableFuture<MineAstrPayloads.ImageTranslationResult> future = new CompletableFuture<>();
        if (imageBytes == null
                || imageBytes.length == 0
                || imageBytes.length > MineAstrPayloads.MAX_IMAGE_TRANSLATION_BYTES) {
            future.completeExceptionally(new IllegalArgumentException("imageBytes exceeds MineAstr limit"));
            return future;
        }
        Minecraft minecraft = Minecraft.getInstance();
        String requestId = java.util.UUID.randomUUID().toString();
        IMAGE_TRANSLATION_REQUESTS.put(requestId, future);
        future.orTimeout(45, TimeUnit.SECONDS)
                .whenComplete((ignored, throwable) -> IMAGE_TRANSLATION_REQUESTS.remove(requestId, future));
        minecraft.execute(() -> {
            if (minecraft.getConnection() == null
                    || !ClientPlayNetworking.canSend(MineAstrPayloads.ImageTranslationQuery.TYPE)) {
                future.completeExceptionally(new IllegalStateException("MineAstr image translation channel unavailable"));
                return;
            }
            try {
                String languages = targetLanguages == null
                        ? ""
                        : targetLanguages.stream()
                                .filter(value -> value != null && !value.isBlank())
                                .map(MineAstrClient::normalizeLanguage)
                                .filter(value -> !value.isBlank())
                                .distinct()
                                .limit(32)
                                .reduce((left, right) -> left + "\n" + right)
                                .orElse("");
                ClientPlayNetworking.send(new MineAstrPayloads.ImageTranslationQuery(
                        requestId,
                        trimPublicText(mimeType, MineAstrPayloads.MAX_MIME_LENGTH, "image/jpeg"),
                        trimPublicText(languages, 256, ""),
                        trimPublicText(context, MineAstrPayloads.MAX_IMAGE_TRANSLATION_CONTEXT_LENGTH, ""),
                        trimPublicText(prompt, MineAstrPayloads.MAX_IMAGE_TRANSLATION_PROMPT_LENGTH, ""),
                        Arrays.copyOf(imageBytes, imageBytes.length)));
            } catch (RuntimeException exc) {
                future.completeExceptionally(exc);
            }
        });
        return future;
    }

    public static boolean shouldShowOriginalTranslatedMessages() {
        return MineAstrClientConfig.SHOW_ORIGINAL_TRANSLATED_MESSAGES.getAsBoolean();
    }

    public static boolean areFloatingTranslationOverlaysEnabled() {
        return MineAstrClientConfig.SIGN_TRANSLATIONS_ENABLED.getAsBoolean();
    }

    public static double floatingTranslationMaxDistance() {
        return MineAstrClientConfig.SIGN_TRANSLATION_MAX_DISTANCE.getAsInt();
    }

    public static float floatingTranslationScale() {
        return (float) MineAstrClientConfig.SIGN_TRANSLATION_SCALE.getAsDouble();
    }

    private static void applyImageTranslationResult(MineAstrPayloads.ImageTranslationResult result) {
        CompletableFuture<MineAstrPayloads.ImageTranslationResult> future =
                IMAGE_TRANSLATION_REQUESTS.get(result.requestId());
        if (future == null) {
            return;
        }
        if (result.ok()) {
            future.complete(result);
        } else {
            future.completeExceptionally(new IllegalStateException(
                    result.error() == null || result.error().isBlank()
                            ? "image translation failed"
                            : result.error()));
        }
    }

    private static String trimPublicText(String value, int maxLength, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.strip();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    public static void handleScreenshotRequest(MineAstrPayloads.ScreenshotRequest request) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> handleScreenshotRequestOnClientThread(minecraft, request));
    }

    public static void applyLocalWorldServerSettings(boolean enabled) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.hasSingleplayerServer() || minecraft.getSingleplayerServer() == null) {
            return;
        }
        var integratedServer = minecraft.getSingleplayerServer();
        integratedServer.execute(() -> {
            MineAstrBridge bridge = MineAstr.bridge();
            if (!enabled) {
                if (bridge.isStarted()) {
                    bridge.stop();
                }
                return;
            }
            if (bridge.isStarted()) {
                bridge.reconnect();
            } else {
                bridge.start(integratedServer);
            }
            minecraft.execute(() -> {
                sendPayloadToServer(new MineAstrPayloads.ClientHello(MineAstr.MOD_VERSION, true));
                sendTranslationPreferences();
            });
        });
    }

    /**
     * Only the sign currently under the crosshair is eligible for translation.
     * The original sign render state is deliberately left untouched.
     */
    private static void updateTargetedSign(Minecraft minecraft) {
        if (!MineAstrClientConfig.GAME_TRANSLATIONS_ENABLED.getAsBoolean()
                || !MineAstrClientConfig.SIGN_TRANSLATIONS_ENABLED.getAsBoolean()
                || minecraft.level == null
                || minecraft.player == null
                || minecraft.screen != null
                || !(findTargetedBlock(minecraft) instanceof BlockHitResult hit)
                || !(minecraft.level.getBlockState(hit.getBlockPos()).getBlock() instanceof SignBlock)
                || !(minecraft.level.getBlockEntity(hit.getBlockPos()) instanceof SignBlockEntity sign)) {
            TARGETED_SIGN = null;
            return;
        }

        boolean front = sign.isFacingFrontText(minecraft.player);
        String source = signSource(sign.getText(front));
        /*
         * 1.21.11 stores front_text and back_text independently for every
         * standing, wall, and hanging sign. If the side selected by the
         * vanilla orientation helper is empty, fall back to the other side.
         * This is important for commands/data packs that only populate
         * front_text (the common case for wall signs).
         */
        if (source.isBlank()) {
            String alternate = signSource(sign.getText(!front));
            if (!alternate.isBlank()) {
                front = !front;
                source = alternate;
            }
        }
        if (source.isBlank()) {
            TARGETED_SIGN = null;
            return;
        }

        SignCacheKey key = new SignCacheKey(
                signId(sign, front),
                sha256(source),
                front);
        MineAstrPayloads.SignTranslationResult result = SIGN_TRANSLATIONS.get(key);
        if (result == null) {
            requestSignTranslation(sign, front, key);
            TARGETED_SIGN = null;
            return;
        }

        String translated = selectTranslation(
                result.translations(),
                normalizeLanguage(minecraft.getLanguageManager().getSelected()));
        if (translated.isBlank() || sameSignText(source, translated)) {
            TARGETED_SIGN = null;
            return;
        }

        TARGETED_SIGN = new TargetedSign(sign.getBlockPos(), front, key, translated);
    }

    private static BlockHitResult findTargetedBlock(Minecraft minecraft) {
        Entity cameraEntity = minecraft.getCameraEntity();
        if (cameraEntity == null || minecraft.level == null) {
            return null;
        }
        double distance = MineAstrClientConfig.SIGN_TRANSLATION_MAX_DISTANCE.getAsInt();
        Vec3 start = cameraEntity.getEyePosition();
        Vec3 end = start.add(cameraEntity.getViewVector(1.0F).scale(distance));
        BlockHitResult result = minecraft.level.clip(new ClipContext(
                start,
                end,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                cameraEntity));
        return result.getType() == HitResult.Type.BLOCK ? result : null;
    }

    private static void requestSignTranslation(SignBlockEntity sign, boolean front, SignCacheKey key) {
        long now = System.currentTimeMillis();
        Long retryAt = SIGN_TRANSLATION_RETRY_AT.get(key);
        if (retryAt != null && retryAt > now) {
            return;
        }
        if (!PENDING_SIGN_TRANSLATIONS.add(key)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null
                || !ClientPlayNetworking.canSend(MineAstrPayloads.SignTranslationQuery.TYPE)) {
            PENDING_SIGN_TRANSLATIONS.remove(key);
            SIGN_TRANSLATION_RETRY_AT.put(key, now + SIGN_TRANSLATION_RETRY_DELAY_MS);
            MineAstr.LOGGER.debug(
                    "MineAstr sign translation query unavailable: connected={} channel={}",
                    minecraft.getConnection() != null,
                    ClientPlayNetworking.canSend(MineAstrPayloads.SignTranslationQuery.TYPE));
            return;
        }
        MineAstr.LOGGER.info(
                "MineAstr sign translation query: pos={} front={} fingerprint={}",
                sign.getBlockPos(),
                front,
                key.fingerprint());
        sendPayloadToServer(new MineAstrPayloads.SignTranslationQuery(
                sign.getBlockPos(),
                front,
                key.fingerprint()));
    }

    private static void applySignTranslationResult(
            Minecraft minecraft,
            MineAstrPayloads.SignTranslationResult result) {
        if (minecraft.level == null || result == null) {
            return;
        }
        String signId = signId(minecraft.level.dimension().identifier().toString(), result.pos(), result.front());
        SignCacheKey key = new SignCacheKey(signId, result.sourceFingerprint(), result.front());
        PENDING_SIGN_TRANSLATIONS.remove(key);
        if (result.ok() && result.translations() != null && !result.translations().isEmpty()) {
            SIGN_TRANSLATIONS.put(key, result);
            SIGN_TRANSLATION_RETRY_AT.remove(key);
        } else {
            SIGN_TRANSLATION_RETRY_AT.put(
                    key,
                    System.currentTimeMillis() + SIGN_TRANSLATION_RETRY_DELAY_MS);
        }
    }

    private static void renderTargetedSignOverlay(WorldRenderContext context) {
        TargetedSign targeted = TARGETED_SIGN;
        Minecraft minecraft = Minecraft.getInstance();
        if (targeted == null
                || minecraft.level == null
                || minecraft.player == null
                || minecraft.screen != null
                || !(minecraft.level.getBlockEntity(targeted.pos()) instanceof SignBlockEntity)) {
            return;
        }

        BlockState state = minecraft.level.getBlockState(targeted.pos());
        if (!(state.getBlock() instanceof SignBlock signBlock)) {
            return;
        }
        var camera = minecraft.gameRenderer.getMainCamera();
        Vec3 cameraPosition = camera.position();
        var left = camera.leftVector();
        Vec3 sideOffset = new Vec3(-left.x(), -left.y(), -left.z()).scale(0.72D);
        Vec3 anchor = new Vec3(
                targeted.pos().getX(),
                targeted.pos().getY(),
                targeted.pos().getZ())
                .add(signBlock.getSignHitboxCenterPosition(state))
                .add(sideOffset);

        Font font = minecraft.font;
        List<net.minecraft.util.FormattedCharSequence> lines = wrapOverlayText(font, targeted.translation());
        if (lines.isEmpty()) {
            return;
        }

        PoseStack matrices = context.matrices();
        MultiBufferSource buffers = context.consumers();
        matrices.pushPose();
        matrices.translate(
                anchor.x() - cameraPosition.x(),
                anchor.y() - cameraPosition.y(),
                anchor.z() - cameraPosition.z());
        matrices.mulPose(camera.rotation());
        float overlayScale = OVERLAY_SCALE * floatingTranslationScale();
        matrices.scale(-overlayScale, -overlayScale, overlayScale);

        int totalHeight = lines.size() * font.lineHeight;
        int y = -totalHeight / 2;
        for (var line : lines) {
            int width = font.width(line);
            font.drawInBatch(
                    line,
                    -width / 2.0F,
                    y,
                    0xFFFFFFFF,
                    false,
                    matrices.last().pose(),
                    buffers,
                    Font.DisplayMode.NORMAL,
                    0xA0000000,
                    LightTexture.FULL_BRIGHT);
            y += font.lineHeight;
        }
        matrices.popPose();
    }

    private static List<net.minecraft.util.FormattedCharSequence> wrapOverlayText(Font font, String translation) {
        List<net.minecraft.util.FormattedCharSequence> lines = new java.util.ArrayList<>();
        String normalized = normalizeSignText(translation);
        for (String rawLine : normalized.split("\n", -1)) {
            List<net.minecraft.util.FormattedCharSequence> wrapped =
                    font.split(Component.literal(rawLine), MAX_OVERLAY_WIDTH);
            if (wrapped.isEmpty()) {
                lines.add(Component.literal("").getVisualOrderText());
            } else {
                lines.addAll(wrapped);
            }
        }
        return lines;
    }

    private static boolean sameSignText(String source, String translated) {
        return normalizeSignText(source).equals(normalizeSignText(translated));
    }

    private static String normalizeSignText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String[] lines = value.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int last = lines.length - 1;
        while (last >= 0 && lines[last].strip().isEmpty()) {
            last--;
        }
        if (last < 0) {
            return "";
        }
        StringBuilder normalized = new StringBuilder();
        for (int index = 0; index <= last; index++) {
            if (index > 0) {
                normalized.append('\n');
            }
            normalized.append(lines[index].strip());
        }
        return normalized.toString();
    }

    private static String selectTranslation(Map<String, String> translations, String language) {
        if (translations == null || translations.isEmpty()) {
            return "";
        }
        String exact = translations.get(language);
        if (exact != null && !exact.isBlank()) {
            return exact;
        }
        int separator = language.indexOf('_');
        if (separator > 0) {
            String family = language.substring(0, separator);
            for (var entry : translations.entrySet()) {
                String candidate = normalizeLanguage(entry.getKey());
                if ((candidate.equals(family) || candidate.startsWith(family + "_"))
                        && !entry.getValue().isBlank()) {
                    return entry.getValue();
                }
            }
        }
        return "";
    }

    private static String signSource(SignText text) {
        Component[] messages = text.getMessages(false);
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < messages.length; index++) {
            if (index > 0) {
                builder.append('\n');
            }
            Component message = messages[index];
            builder.append(message == null ? "" : message.getString().replace('\r', ' ').replace('\u0000', ' '));
        }
        return builder.toString().strip();
    }

    private static String signId(SignBlockEntity sign, boolean front) {
        if (sign.getLevel() == null) {
            return "";
        }
        return signId(sign.getLevel().dimension().identifier().toString(), sign.getBlockPos(), front);
    }

    private static String signId(String dimension, BlockPos pos, boolean front) {
        return dimension + "/"
                + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "/"
                + (front ? "front" : "back");
    }

    private static String normalizeLanguage(String language) {
        return language == null
                ? ""
                : language.strip().replace('-', '_').toLowerCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exc) {
            throw new IllegalStateException("SHA-256 不可用", exc);
        }
    }

    private static void handleScreenshotRequestOnClientThread(Minecraft minecraft, MineAstrPayloads.ScreenshotRequest request) {
        if (minecraft.player == null || minecraft.getConnection() == null) {
            sendError(request.requestId(), "not_in_game", "客户端尚未进入游戏，无法截图。");
            return;
        }

        MineAstrClientConfig.ScreenshotMode mode = MineAstrClientConfig.SCREENSHOT_MODE.get();
        if (mode == MineAstrClientConfig.ScreenshotMode.DISABLED) {
            sendError(request.requestId(), "disabled", "玩家已在 MineAstr 客户端配置中禁用截图发送。");
            return;
        }
        if (mode == MineAstrClientConfig.ScreenshotMode.AUTO) {
            captureAndSend(minecraft, request);
            return;
        }
        if (pendingPromptRequestId != null) {
            sendError(request.requestId(), "busy", "已有一个 MineAstr 截图确认窗口正在等待处理。");
            return;
        }

        pendingPromptRequestId = request.requestId();
        Screen previous = minecraft.screen;
        MineAstrScreenshotConsentScreen screen = new MineAstrScreenshotConsentScreen(
                previous,
                trimReason(request.reason()),
                confirmed -> {
                    pendingPromptRequestId = null;
                    if (confirmed) {
                        captureAndSend(minecraft, request);
                    } else {
                        sendError(request.requestId(), "denied", "玩家拒绝发送截图或确认超时。");
                    }
                });
        minecraft.setScreen(screen);
    }

    private static void captureAndSend(Minecraft minecraft, MineAstrPayloads.ScreenshotRequest request) {
        try {
            Screenshot.takeScreenshot(minecraft.getMainRenderTarget(), nativeImage -> {
                RawScreenshot screenshot;
                try (nativeImage) {
                    screenshot = new RawScreenshot(
                            nativeImage.getWidth(), nativeImage.getHeight(), nativeImage.getPixelsABGR().clone());
                } catch (Exception exc) {
                    MineAstr.LOGGER.warn("MineAstr 客户端截图读取失败：{}", exc.getMessage());
                    sendError(request.requestId(), "capture_failed", "客户端截图读取失败：" + exc.getMessage());
                    return;
                }

                CompletableFuture
                        .supplyAsync(() -> encodeLowResolutionScreenshot(screenshot, request), SCREENSHOT_ENCODER)
                        .whenComplete((image, throwable) -> minecraft.execute(() -> {
                            if (throwable != null) {
                                MineAstr.LOGGER.warn("MineAstr 客户端截图编码失败：{}", throwable.getMessage());
                                sendError(request.requestId(), "encode_failed", "客户端截图编码失败：" + throwable.getMessage());
                                return;
                            }
                            sendChunks(request.requestId(), image);
                        }));
            });
        } catch (Exception exc) {
            MineAstr.LOGGER.warn("MineAstr 客户端截图失败：{}", exc.getMessage());
            sendError(request.requestId(), "capture_failed", "客户端截图失败：" + exc.getMessage());
        }
    }

    private static ScreenshotImage encodeLowResolutionScreenshot(RawScreenshot screenshot, MineAstrPayloads.ScreenshotRequest request) {
        try {
            return encodeLowResolutionScreenshotOrThrow(screenshot, request);
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        }
    }

    private static ScreenshotImage encodeLowResolutionScreenshotOrThrow(
            RawScreenshot screenshot,
            MineAstrPayloads.ScreenshotRequest request) throws IOException {
        BufferedImage source = toBufferedImage(screenshot);

        int maxWidth = clampPositive(Math.min(request.maxWidth(), MineAstrClientConfig.SCREENSHOT_MAX_WIDTH.getAsInt()), 64);
        int maxHeight = clampPositive(Math.min(request.maxHeight(), MineAstrClientConfig.SCREENSHOT_MAX_HEIGHT.getAsInt()), 36);
        int maxBytes = clampPositive(Math.min(request.maxBytes(), MineAstrClientConfig.SCREENSHOT_MAX_BYTES.getAsInt()), 8192);
        float quality = (float) MineAstrClientConfig.SCREENSHOT_JPEG_QUALITY.getAsDouble();

        int width = targetWidth(source.getWidth(), source.getHeight(), maxWidth, maxHeight);
        int height = Math.max(1, Math.round(source.getHeight() * (width / (float) source.getWidth())));
        byte[] encoded = new byte[0];

        for (int attempt = 0; attempt < 8; attempt++) {
            BufferedImage scaled = scaleToJpegImage(source, width, height);
            encoded = encodeJpeg(scaled, quality);
            if (encoded.length <= maxBytes || (width <= 64 && height <= 36)) {
                return new ScreenshotImage(width, height, encoded, System.currentTimeMillis());
            }
            quality = Math.max(0.12F, quality * 0.82F);
            width = Math.max(64, Math.round(width * 0.82F));
            height = Math.max(36, Math.round(height * 0.82F));
        }
        if (encoded.length > maxBytes) {
            throw new IOException("截图压缩后仍超过允许大小。");
        }
        return new ScreenshotImage(width, height, encoded, System.currentTimeMillis());
    }

    private static BufferedImage toBufferedImage(RawScreenshot screenshot) throws IOException {
        if (screenshot.width <= 0 || screenshot.height <= 0 || screenshot.pixels == null
                || screenshot.pixels.length != screenshot.width * screenshot.height) {
            throw new IOException("Minecraft 截图像素数据无效。");
        }
        BufferedImage image = new BufferedImage(screenshot.width, screenshot.height, BufferedImage.TYPE_INT_RGB);
        int[] argb = new int[screenshot.pixels.length];
        for (int index = 0; index < screenshot.pixels.length; index++) {
            int abgr = screenshot.pixels[index];
            int red = abgr & 0xFF;
            int green = (abgr >>> 8) & 0xFF;
            int blue = (abgr >>> 16) & 0xFF;
            argb[index] = (red << 16) | (green << 8) | blue;
        }
        image.setRGB(0, 0, screenshot.width, screenshot.height, argb, 0, screenshot.width);
        return image;
    }

    private static int targetWidth(int sourceWidth, int sourceHeight, int maxWidth, int maxHeight) {
        float scale = Math.min(maxWidth / (float) sourceWidth, maxHeight / (float) sourceHeight);
        scale = Math.min(scale, 1.0F);
        return Math.max(1, Math.round(sourceWidth * scale));
    }

    private static BufferedImage scaleToJpegImage(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setColor(Color.BLACK);
        graphics.fillRect(0, 0, width, height);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return target;
    }

    private static byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("当前 Java 运行环境没有可用的 JPEG 编码器。");
        }
        ImageWriter writer = writers.next();
        ImageWriteParam params = writer.getDefaultWriteParam();
        params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        params.setCompressionQuality(Math.max(0.10F, Math.min(0.95F, quality)));
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                MemoryCacheImageOutputStream imageOutput = new MemoryCacheImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            writer.write(null, new IIOImage(image, null, null), params);
            imageOutput.flush();
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static void sendChunks(String requestId, ScreenshotImage image) {
        int totalChunks = Math.max(1, (image.bytes.length + MineAstrPayloads.MAX_CHUNK_BYTES - 1) / MineAstrPayloads.MAX_CHUNK_BYTES);
        if (totalChunks > MAX_SCREENSHOT_CHUNKS) {
            sendError(requestId, "too_large", "截图分片数量超过安全上限。");
            return;
        }
        for (int index = 0; index < totalChunks; index++) {
            int start = index * MineAstrPayloads.MAX_CHUNK_BYTES;
            int end = Math.min(image.bytes.length, start + MineAstrPayloads.MAX_CHUNK_BYTES);
            byte[] chunk = Arrays.copyOfRange(image.bytes, start, end);
            sendPayloadToServer(new MineAstrPayloads.ScreenshotChunk(
                    requestId,
                    index,
                    totalChunks,
                    image.width,
                    image.height,
                    image.bytes.length,
                    image.capturedAtMs,
                    MIME_TYPE,
                    chunk));
        }
    }

    private static void sendError(String requestId, String code, String message) {
        sendPayloadToServer(new MineAstrPayloads.ScreenshotError(requestId, code, trimError(message)));
    }

    public static void sendTranslationPreferences() {
        sendPayloadToServer(new MineAstrPayloads.TranslationPreferences(
                MineAstrClientConfig.GAME_TRANSLATIONS_ENABLED.getAsBoolean(),
                MineAstrClientConfig.SHOW_ORIGINAL_TRANSLATED_MESSAGES.getAsBoolean()));
    }

    private static void sendPayloadToServer(CustomPacketPayload payload) {
        try {
            if (ClientPlayNetworking.canSend(payload.type())) {
                ClientPlayNetworking.send(payload);
            }
        } catch (RuntimeException exc) {
            MineAstr.LOGGER.debug("MineAstr 客户端发送可选网络包失败：{}", exc.getMessage());
        }
    }

    private static int clampPositive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static String trimReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "AstrBot 请求查看当前 Minecraft 画面。";
        }
        return reason.length() > 120 ? reason.substring(0, 120) : reason;
    }

    private static String trimError(String message) {
        if (message == null || message.isBlank()) {
            return "未知错误。";
        }
        return message.length() > MineAstrPayloads.MAX_ERROR_LENGTH
                ? message.substring(0, MineAstrPayloads.MAX_ERROR_LENGTH)
                : message;
    }

    private record ScreenshotImage(int width, int height, byte[] bytes, long capturedAtMs) {
    }

    private record RawScreenshot(int width, int height, int[] pixels) {
    }

    private record SignCacheKey(String signId, String fingerprint, boolean front) {
    }

    private record TargetedSign(
            BlockPos pos,
            boolean front,
            SignCacheKey key,
            String translation) {
    }
}
