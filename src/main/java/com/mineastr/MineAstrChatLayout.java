package com.mineastr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

/** Reflows the display copy only; vanilla retains the original message/history. */
public final class MineAstrChatLayout {
    private static final ResourceLocation ICON_FONT = ResourceLocation.fromNamespaceAndPath("mineastr", "platforms");
    private static final ResourceLocation SPACE_FONT = ResourceLocation.fromNamespaceAndPath("mineastr", "spacing");
    private static final String CHAT_KEY = "message.mineastr.chat_sender";
    private static final String IMAGE_KEY = "message.mineastr.image_preview";
    private static final String IMAGE_GLYPH = "\uE200";
    private static final Map<FormattedCharSequence, Optional<ImageRow>> IMAGE_ROWS = new WeakHashMap<>();

    private MineAstrChatLayout() {}

    public static Component message(MineAstrPayloads.ChatPresentation payload) {
        var hover = Component.translatable(CHAT_KEY, Component.literal(payload.platform()), Component.literal(payload.senderName()));
        var result = Component.literal("[" + payload.senderName() + "] ")
                .withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover)))
                .append(payload.content().isBlank() && payload.images().isEmpty()
                        ? Component.translatable("message.mineastr.image_disabled") : Component.literal(payload.content()));
        for (var image : payload.images()) {
            MineAstrChatImages.reserve(image.id(), image.name());
            result.append(Component.literal("\n"));
            // The original message/history and log contain a readable image label.
            result.append(Component.translatable(IMAGE_KEY, Component.literal(image.name()))
                    .withStyle(style -> style.withHoverEvent(
                            imageMarker(image.name(), image.id(), 0, 0, 0, 0).getStyle().getHoverEvent())));
        }
        return result;
    }

    public static Component format(Component original, int width, int lineHeight, int visibleLines) {
        if (width < 72) return original;
        Component name;
        String platform = "minecraft";
        MutableComponent body = Component.empty();
        TranslatableContents metadata = hoverMetadata(original.getStyle(), CHAT_KEY);
        if (metadata != null && metadata.getArgs().length == 2) {
            platform = argument(metadata, 0);
            name = Component.literal(argument(metadata, 1)).withStyle(style -> style.withHoverEvent(
                    new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(argument(metadata, 1)))));
            original.getSiblings().forEach(part -> body.append(part.copy()));
        } else if (original.getContents() instanceof TranslatableContents contents
                && contents.getKey().equals("chat.type.text") && contents.getArgs().length >= 2) {
            name = asComponent(contents.getArgs()[0]);
            body.append(asComponent(contents.getArgs()[1]));
            original.getSiblings().forEach(part -> body.append(part.copy()));
        } else {
            return original;
        }
        Font font = Minecraft.getInstance().font;
        name = name.copy().withStyle(net.minecraft.ChatFormatting.BOLD);
        int column = MineAstrChatGeometry.senderColumn(width);
        int bodyWidth = Math.max(24, width - column - 2);
        var clippedName = font.substrByWidth(name, Math.max(1, column - 18));
        var clippedComponent = Component.empty();
        clippedName.visit((style, textValue) -> {
            clippedComponent.append(Component.literal(textValue).setStyle(style));
            return java.util.Optional.empty();
        }, Style.EMPTY);
        var header = Component.empty().append(icon(platform)).append(spaces(4)).append(clippedComponent);
        header.append(spaces(Math.max(1, column - font.width(header))));
        List<MutableComponent> rows = new ArrayList<>();
        MutableComponent text = Component.empty();
        // Image anchors remain separate siblings and are never parsed as text or links.
        for (Component part : body.getSiblings()) {
            TranslatableContents image = hoverMetadata(part.getStyle(), IMAGE_KEY);
            if (image != null && image.getArgs().length == 6) {
                flushText(rows, text, font, bodyWidth);
                text = Component.empty();
                int maxWidth = MineAstrChatGeometry.imageWidth(bodyWidth);
                int maxHeight = MineAstrChatGeometry.imageHeight(visibleLines, lineHeight);
                int count = (maxHeight + 4 + lineHeight - 1) / lineHeight;
                for (int row = 0; row < count; row++) {
                    rows.add(imageMarker(argument(image, 0), argument(image, 1), row, column, maxWidth, maxHeight));
                }
            } else {
                text.append(part.copy());
            }
        }
        flushText(rows, text, font, bodyWidth);
        if (rows.isEmpty()) rows.add(Component.empty());
        MutableComponent result = Component.empty().append(header).append(rows.getFirst());
        for (int row = 1; row < rows.size(); row++) {
            result.append(Component.literal("\n")).append(spaces(column)).append(rows.get(row));
        }
        return result;
    }

    private static void flushText(List<MutableComponent> rows, Component text, Font font, int width) {
        if (text.getString().isBlank()) return;
        // Drop only the separator before an image, without flattening styles/events.
        // Work on logical text: reusing visual-order glyph sequences would reorder
        // bidi text twice and discard ModernUI's Unicode shaping/line-breaking data.
        for (var sequence : font.getSplitter().splitLines(text, width, Style.EMPTY)) {
            MutableComponent line = Component.empty();
            sequence.visit((style, value) -> {
                line.append(Component.literal(value).setStyle(style));
                return Optional.empty();
            }, Style.EMPTY);
            rows.add(line);
        }
    }

    private static Component asComponent(Object argument) {
        return argument instanceof Component component ? component.copy() : Component.literal(String.valueOf(argument));
    }

    private static Component icon(String platform) {
        String glyph = switch (platform) {
            case "qq" -> "\uE002";
            case "discord" -> "\uE001";
            default -> "\uE000";
        };
        return Component.literal(glyph).withStyle(style -> style.withFont(ICON_FONT)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.translatable("message.mineastr.platform." + switch (platform) {
                            case "qq" -> "qq";
                            case "discord" -> "discord";
                            default -> "minecraft";
                        }))));
    }

    private static MutableComponent spaces(int pixels) {
        StringBuilder glyphs = new StringBuilder();
        for (int bit = 0; bit < 12; bit++) if ((pixels & (1 << bit)) != 0) glyphs.append((char) (0xE100 + bit));
        return Component.literal(glyphs.toString()).withStyle(style -> style.withFont(SPACE_FONT));
    }

    private static MutableComponent imageMarker(String name, String id, int row, int column, int width, int height) {
        return Component.literal(IMAGE_GLYPH).withStyle(style -> style.withFont(SPACE_FONT)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.translatable(IMAGE_KEY, Component.literal(name), Component.literal(id),
                                Component.literal(Integer.toString(row)), Component.literal(Integer.toString(column)),
                                Component.literal(Integer.toString(width)), Component.literal(Integer.toString(height))))));
    }

    private static TranslatableContents hoverMetadata(Style style, String key) {
        if (style.getHoverEvent() == null) return null;
        Component value = style.getHoverEvent().getValue(HoverEvent.Action.SHOW_TEXT);
        return value != null && value.getContents() instanceof TranslatableContents contents
                && contents.getKey().equals(key) ? contents : null;
    }

    private static String argument(TranslatableContents contents, int index) {
        return index < contents.getArgs().length ? asComponent(contents.getArgs()[index]).getString() : "";
    }

    public static ImageRow imageRow(FormattedCharSequence line) {
        Optional<ImageRow> cached = IMAGE_ROWS.get(line);
        if (cached != null) return cached.orElse(null);
        ImageRow[] found = {null};
        line.accept((index, style, codepoint) -> {
            if (codepoint != 0xE200) return true;
            TranslatableContents contents = hoverMetadata(style, IMAGE_KEY);
            if (contents != null && contents.getArgs().length == 6) {
                try {
                    found[0] = new ImageRow(argument(contents, 1), Integer.parseInt(argument(contents, 2)),
                            Integer.parseInt(argument(contents, 3)), Integer.parseInt(argument(contents, 4)),
                            Integer.parseInt(argument(contents, 5)));
                } catch (NumberFormatException ignored) {}
            }
            return false;
        });
        IMAGE_ROWS.put(line, Optional.ofNullable(found[0]));
        return found[0];
    }

    public record ImageRow(String id, int row, int column, int width, int height) {}
}
