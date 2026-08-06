package com.mineastr.api;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.vertex.PoseStack;

/**
 * Shared client-side floating translation display surface.
 *
 * <p>External mods submit translated text here; MineAstr owns the actual
 * world-space rendering. Entries are intentionally keyed by a caller-owned
 * id so a painting or other mod can update/remove its display without
 * touching MineAstr's renderer.</p>
 */
@Environment(EnvType.CLIENT)
public final class MineAstrDisplayApi {
    private static final ConcurrentMap<String, DisplayEntry> ENTRIES = new ConcurrentHashMap<>();
    private static final int MAX_OVERLAY_WIDTH = 180;
    private static final float OVERLAY_SCALE = 0.025F;
    private static final double MAX_DISTANCE_SQUARED = 32.0D * 32.0D;

    private MineAstrDisplayApi() {
    }

    public static void showEntityTranslation(
            String id,
            int entityId,
            Vec3 offset,
            String translated,
            String original) {
        showEntityTranslation(id, entityId, offset, translated, original, true);
    }

    public static void showEntityTranslation(
            String id,
            int entityId,
            Vec3 offset,
            String translated,
            String original,
            boolean onlyWhenTargeted) {
        String key = normalizeId(id);
        if (key.isBlank() || translated == null || translated.isBlank()) {
            return;
        }
        ENTRIES.put(key, DisplayEntry.entity(
                entityId,
                offset == null ? Vec3.ZERO : offset,
                translated,
                original,
                onlyWhenTargeted));
    }

    public static void showWorldTranslation(
            String id,
            String dimension,
            Vec3 anchor,
            String translated,
            String original) {
        showWorldTranslation(id, dimension, anchor, translated, original, true);
    }

    public static void showWorldTranslation(
            String id,
            String dimension,
            Vec3 anchor,
            String translated,
            String original,
            boolean onlyWhenTargeted) {
        String key = normalizeId(id);
        if (key.isBlank() || translated == null || translated.isBlank()) {
            return;
        }
        ENTRIES.put(key, DisplayEntry.world(
                dimension == null ? "" : dimension.strip(),
                anchor == null ? Vec3.ZERO : anchor,
                translated,
                original,
                onlyWhenTargeted));
    }

    public static void remove(String id) {
        String key = normalizeId(id);
        if (!key.isBlank()) {
            ENTRIES.remove(key);
        }
    }

    public static void clear() {
        ENTRIES.clear();
    }

    public static int size() {
        return ENTRIES.size();
    }

    public static void render(WorldRenderContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null
                || minecraft.player == null
                || minecraft.screen != null
                || ENTRIES.isEmpty()) {
            return;
        }
        var camera = minecraft.gameRenderer.getMainCamera();
        Vec3 cameraPosition = camera.position();
        Font font = minecraft.font;
        for (DisplayEntry entry : ENTRIES.values()) {
            Vec3 anchor = entry.resolveAnchor(minecraft);
            if (anchor == null
                    || minecraft.player.position().distanceToSqr(anchor) > MAX_DISTANCE_SQUARED
                    || (entry.onlyWhenTargeted() && !isTargeted(minecraft, entry, anchor))) {
                continue;
            }
            String text = displayText(entry.translated(), entry.original());
            List<net.minecraft.util.FormattedCharSequence> lines = wrap(font, text);
            if (lines.isEmpty()) {
                continue;
            }
            PoseStack matrices = context.matrices();
            MultiBufferSource buffers = context.consumers();
            matrices.pushPose();
            matrices.translate(
                    anchor.x() - cameraPosition.x(),
                    anchor.y() - cameraPosition.y(),
                    anchor.z() - cameraPosition.z());
            matrices.mulPose(camera.rotation());
            matrices.scale(-OVERLAY_SCALE, -OVERLAY_SCALE, OVERLAY_SCALE);

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
    }

    private static boolean isTargeted(Minecraft minecraft, DisplayEntry entry, Vec3 anchor) {
        if (entry.entityId() != null) {
            return minecraft.hitResult instanceof EntityHitResult hit
                    && hit.getEntity().getId() == entry.entityId();
        }
        HitResult hit = minecraft.hitResult;
        return hit != null
                && hit.getType() != HitResult.Type.MISS
                && hit.getLocation().distanceToSqr(anchor) <= 4.0D;
    }

    private static String displayText(String translated, String original) {
        String result = translated == null ? "" : translated.strip();
        String source = original == null ? "" : original.strip();
        if (!source.isBlank()
                && !sameText(result, source)
                && shouldShowOriginal()) {
            result += "\n" + source;
        }
        return result;
    }

    private static boolean shouldShowOriginal() {
        try {
            return com.mineastr.MineAstrClient.shouldShowOriginalTranslatedMessages();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static List<net.minecraft.util.FormattedCharSequence> wrap(Font font, String text) {
        List<net.minecraft.util.FormattedCharSequence> lines = new ArrayList<>();
        String normalized = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
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

    private static boolean sameText(String left, String right) {
        return normalizeText(left).equals(normalizeText(right));
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private static String normalizeId(String id) {
        return id == null ? "" : id.strip();
    }

    private record DisplayEntry(
            Integer entityId,
            String dimension,
            Vec3 anchor,
            Vec3 offset,
            String translated,
            String original,
            boolean onlyWhenTargeted) {
        private static DisplayEntry entity(
                int entityId,
                Vec3 offset,
                String translated,
                String original,
                boolean onlyWhenTargeted) {
            return new DisplayEntry(
                    entityId,
                    "",
                    null,
                    offset,
                    translated,
                    original,
                    onlyWhenTargeted);
        }

        private static DisplayEntry world(
                String dimension,
                Vec3 anchor,
                String translated,
                String original,
                boolean onlyWhenTargeted) {
            return new DisplayEntry(
                    null,
                    dimension,
                    anchor,
                    Vec3.ZERO,
                    translated,
                    original,
                    onlyWhenTargeted);
        }

        private Vec3 resolveAnchor(Minecraft minecraft) {
            if (entityId != null) {
                Entity entity = minecraft.level.getEntity(entityId);
                return entity == null ? null : entity.position().add(offset);
            }
            if (!dimension.isBlank()
                    && !dimension.equals(minecraft.level.dimension().identifier().toString())) {
                return null;
            }
            return anchor;
        }
    }
}
