package com.mineastr;

/** Pixel bounds shared by the layout and thumbnail renderer. No rendering or IO. */
public final class MineAstrChatGeometry {
    private MineAstrChatGeometry() {}

    public static int senderColumn(int width) {
        return Math.max(24, Math.min(96, width / 3));
    }

    public static int imageWidth(int bodyWidth) {
        return Math.max(1, Math.min(96, bodyWidth * 3 / 5));
    }

    public static int imageHeight(int visibleLines, int lineHeight) {
        return Math.max(1, Math.min(54, Math.max(1, visibleLines / 3) * lineHeight));
    }

    public static Size fit(int sourceWidth, int sourceHeight, int maxWidth, int maxHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0) return new Size(1, 1);
        double factor = Math.min(1.0, Math.min((double) maxWidth / sourceWidth, (double) maxHeight / sourceHeight));
        return new Size(Math.max(1, (int) Math.round(sourceWidth * factor)),
                Math.max(1, (int) Math.round(sourceHeight * factor)));
    }

    public record Size(int width, int height) {}
}
