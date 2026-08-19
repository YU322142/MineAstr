package com.mineastr.api;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Shared client-side floating translation display surface.
 *
 * <p>External mods submit translated text here; MineAstr owns the actual
 * world-space rendering. Entries are intentionally keyed by a caller-owned
 * id so a painting or other mod can update/remove its display without
 * touching MineAstr's renderer.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class MineAstrDisplayApi {
    private static final ConcurrentMap<String, DisplayEntry> ENTRIES = new ConcurrentHashMap<>();
    private static final int MAX_OVERLAY_WIDTH = 180;
    private static final float OVERLAY_SCALE = 0.025F;
    private static final MultiBufferSource.BufferSource OVERLAY_BUFFERS =
            MultiBufferSource.immediate(new ByteBufferBuilder(256 * 1024));
    private static String lastHudEntryId = "";
    private static long lastHudLogAt;
    private static boolean createOverlayResolved;
    private static Method createDrawHoveringText;

    static {
        NeoForge.EVENT_BUS.addListener(MineAstrDisplayApi::renderHud);
    }

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

    /**
     * HUD fallback for external translations. Shader pipelines can suppress
     * AFTER_LEVEL world-space text even though the public display entry was
     * accepted. Rendering the selected entry through the normal GUI pass gives
     * paintings the same reliable crosshair-driven presentation as signs.
     */
    public static void renderHud(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null
                || minecraft.player == null
                || minecraft.screen != null
                || minecraft.options.hideGui
                || !com.mineastr.MineAstrClient.areFloatingTranslationOverlaysEnabled()
                || ENTRIES.isEmpty()) {
            lastHudEntryId = "";
            return;
        }

        DisplayEntry selected = null;
        String selectedId = "";
        double selectedDistance = Double.POSITIVE_INFINITY;
        double maxDistance = com.mineastr.MineAstrClient.floatingTranslationMaxDistance();
        double maxDistanceSquared = maxDistance * maxDistance;
        for (var candidate : ENTRIES.entrySet()) {
            DisplayEntry entry = candidate.getValue();
            Vec3 anchor = entry.resolveAnchor(minecraft);
            if (anchor == null) {
                continue;
            }
            double distance = minecraft.player.position().distanceToSqr(anchor);
            if (distance > maxDistanceSquared
                    || (entry.onlyWhenTargeted() && !isTargeted(minecraft, entry, anchor))) {
                continue;
            }
            if (distance < selectedDistance) {
                selected = entry;
                selectedId = candidate.getKey();
                selectedDistance = distance;
            }
        }
        if (selected == null) {
            lastHudEntryId = "";
            return;
        }

        // Public callers own target lifetime.  This surface deliberately shows
        // the translated result only; MineAstr's global "show original" chat
        // preference must not duplicate OCR source text in an image overlay.
        String text = selected.translated() == null ? "" : selected.translated().strip();
        List<Component> createLines = wrapComponents(minecraft.font, text);
        List<net.minecraft.util.FormattedCharSequence> lines = wrap(minecraft.font, text);
        if (lines.isEmpty()) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        if (renderWithCreateOverlay(graphics, minecraft.font, createLines)) {
            logHudRender(selectedId, lines.size(), selected.translated().length());
            return;
        }

        // Fail-soft fallback for installations without Create.  Motiquies has
        // Create, so its normal path is the renderer above.
        float scale = Math.max(
                0.50F,
                Math.min(2.0F, com.mineastr.MineAstrClient.floatingTranslationScale()));
        int padding = 5;
        int textWidth = 0;
        for (var line : lines) {
            textWidth = Math.max(textWidth, minecraft.font.width(line));
        }
        int panelWidth = textWidth + padding * 2;
        int panelHeight = lines.size() * minecraft.font.lineHeight + padding * 2;
        int renderedWidth = Math.round(panelWidth * scale);
        int renderedHeight = Math.round(panelHeight * scale);
        int x = Math.max(4, Math.min(
                graphics.guiWidth() / 2 + 18,
                graphics.guiWidth() - renderedWidth - 4));
        int y = Math.max(4, Math.min(
                graphics.guiHeight() / 2 + 10,
                graphics.guiHeight() - renderedHeight - 4));

        var pose = graphics.pose();
        pose.pushPose();
        try {
            pose.translate(x, y, 0.0F);
            pose.scale(scale, scale, 1.0F);
            graphics.fill(0, 0, panelWidth, panelHeight, 0xE0100010);
            graphics.renderOutline(0, 0, panelWidth, panelHeight, 0xFF8977C9);
            graphics.fill(1, panelHeight - 2, panelWidth - 1, panelHeight - 1, 0xFF392A6A);
            int lineY = padding;
            for (var line : lines) {
                graphics.drawString(minecraft.font, line, padding, lineY, 0xFFFFFFFF, true);
                lineY += minecraft.font.lineHeight;
            }
        } finally {
            pose.popPose();
        }

        logHudRender(selectedId, lines.size(), selected.translated().length());
    }

    private static void logHudRender(String selectedId, int lines, int characters) {
        long now = System.currentTimeMillis();
        if (!selectedId.equals(lastHudEntryId) || now - lastHudLogAt >= 5_000L) {
            lastHudEntryId = selectedId;
            lastHudLogAt = now;
            com.mineastr.MineAstr.LOGGER.info(
                    "MineAstr public Create-style HUD render: id={} lines={} chars={}",
                    selectedId,
                    lines,
                    characters);
        }
    }

    /**
     * Create's goggle renderer only gathers text from block entities; it has no
     * public arbitrary-entity submission API.  Its public hovering-text helper
     * is the reusable presentation surface, so invoke that when Create exists
     * without making MineAstr hard-depend on Create.
     */
    private static boolean renderWithCreateOverlay(
            GuiGraphics graphics,
            Font font,
            List<Component> lines) {
        if (!createOverlayResolved) {
            createOverlayResolved = true;
            try {
                Class<?> utility = Class.forName(
                        "com.simibubi.create.foundation.gui.RemovedGuiUtils",
                        false,
                        MineAstrDisplayApi.class.getClassLoader());
                createDrawHoveringText = utility.getMethod(
                        "drawHoveringText",
                        GuiGraphics.class,
                        List.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        Font.class);
                com.mineastr.MineAstr.LOGGER.info(
                        "MineAstr public display is using Create's hovering-text renderer");
            } catch (ReflectiveOperationException | LinkageError exception) {
                createDrawHoveringText = null;
                com.mineastr.MineAstr.LOGGER.info(
                        "Create hovering-text renderer unavailable; using MineAstr HUD fallback");
            }
        }
        if (createDrawHoveringText == null) {
            return false;
        }
        try {
            int x = graphics.guiWidth() / 2 + 20;
            int y = graphics.guiHeight() / 2 + 20;
            createDrawHoveringText.invoke(
                    null,
                    graphics,
                    lines,
                    x,
                    y,
                    graphics.guiWidth(),
                    graphics.guiHeight(),
                    -1,
                    0xD0100010,
                    0xFF8977C9,
                    0xFF392A6A,
                    font);
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            createDrawHoveringText = null;
            com.mineastr.MineAstr.LOGGER.warn(
                    "Create hovering-text renderer failed; using MineAstr HUD fallback",
                    exception);
            return false;
        }
    }

    public static void render(RenderLevelStageEvent event) {
        // Public translations are GUI-only.  The former world-space renderer
        // produced giant duplicate text and could outlive the caller's target.
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

    private static List<Component> wrapComponents(Font font, String text) {
        List<Component> lines = new ArrayList<>();
        String normalized = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        for (String rawLine : normalized.split("\n", -1)) {
            if (rawLine.isEmpty()) {
                lines.add(Component.empty());
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (int offset = 0; offset < rawLine.length();) {
                int codePoint = rawLine.codePointAt(offset);
                String unit = new String(Character.toChars(codePoint));
                String candidate = line + unit;
                if (!line.isEmpty() && font.width(Component.literal(candidate)) > MAX_OVERLAY_WIDTH) {
                    lines.add(Component.literal(line.toString()));
                    line.setLength(0);
                }
                line.append(unit);
                offset += Character.charCount(codePoint);
            }
            lines.add(Component.literal(line.toString()));
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
                    && !dimension.equals(minecraft.level.dimension().location().toString())) {
                return null;
            }
            return anchor;
        }
    }
}
