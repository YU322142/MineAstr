package com.mineastr;

/** Pixel bounds shared by the layout and thumbnail renderer. No rendering or IO. */
public final class MineAstrChatGeometry {
    private MineAstrChatGeometry() {}

    public static int senderColumn(int width) {
        return Math.max(24, Math.min(96, width / 3));
    }

    public static int imageWidth(int bodyWidth) {
        return imageWidth(bodyWidth, 100);
    }

    public static int imageHeight(int visibleLines, int lineHeight) {
        return imageHeight(visibleLines, lineHeight, 100);
    }

    public static int imageWidth(int bodyWidth, int percent) {
        int scale = Math.clamp(percent, 50, 300);
        return Math.max(1, Math.min(Math.max(1, bodyWidth - 4), Math.min(96 * scale / 100, bodyWidth * 3 * scale / 500)));
    }

    public static int imageHeight(int visibleLines, int lineHeight, int percent) {
        int scale = Math.clamp(percent, 50, 300);
        return Math.max(1, Math.min(Math.min(54, Math.max(1, visibleLines / 3) * lineHeight) * scale / 100,
                Math.max(1, visibleLines * lineHeight * 2 / 3)));
    }

    public static Size fit(int sourceWidth, int sourceHeight, int maxWidth, int maxHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0) return new Size(1, 1);
        double factor = Math.min(1.0, Math.min((double) maxWidth / sourceWidth, (double) maxHeight / sourceHeight));
        return new Size(Math.max(1, (int) Math.round(sourceWidth * factor)),
                Math.max(1, (int) Math.round(sourceHeight * factor)));
    }

    public static int heightLimit(int vanillaHeight, int screenHeight, double chatScale, int percent, int lineHeight) {
        if (percent <= 0) return vanillaHeight;
        int available = Math.max(lineHeight, screenHeight - 48);
        int limit = Math.max(lineHeight, (int) (available * Math.clamp(percent, 1, 100) / 100.0 / Math.max(.01, chatScale)));
        return Math.min(vanillaHeight, limit);
    }

    public record Size(int width, int height) {}
}
