package com.mineastr;

/** Readable sRGB colors against the chat's dark background reference (#303030). */
public final class MineAstrThemeColor {
    public static final int DEFAULT = 0xFFFFFF;
    private static final double MIN_LUMINANCE = 4.5 * (luminance(0x303030) + .05) - .05;
    private MineAstrThemeColor() {}

    public static double luminance(int rgb) {
        return .2126 * linear((rgb >>> 16) & 255) + .7152 * linear((rgb >>> 8) & 255) + .0722 * linear(rgb & 255);
    }
    private static double linear(int value) {
        double channel = value / 255.0;
        return channel <= .04045 ? channel / 12.92 : Math.pow((channel + .055) / 1.055, 2.4);
    }
    public static double contrast(int rgb) { return (luminance(rgb) + .05) / (luminance(0x303030) + .05); }

    public static boolean isReadable(int rgb) { return rgb >= 0 && rgb <= 0xFFFFFF && luminance(rgb) >= MIN_LUMINANCE; }
    /** Linear-light interpolation retains luminance; round upward to retain the contrast floor. */
    public static int blend(int a, int b, double position) {
        double t = Math.clamp(position, 0, 1);
        int result = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            double value = linear((a >>> shift) & 255) * (1 - t) + linear((b >>> shift) & 255) * t;
            double srgb = value <= .0031308 ? 12.92 * value : 1.055 * Math.pow(value, 1 / 2.4) - .055;
            result |= Math.clamp((int) Math.ceil(srgb * 255 - 1e-10), 0, 255) << shift;
        }
        return result;
    }
    public static int gradient(int a, int b, int c, int count, double phase) {
        if (count == 1) return a;
        double cycle = (phase - Math.floor(phase)) * count;
        int segment = (int) cycle;
        double t = (1 - Math.cos(Math.PI * (cycle - segment))) / 2;
        return switch (segment) {
            case 0 -> blend(a, b, t);
            case 1 -> blend(b, count == 2 ? a : c, t);
            default -> blend(c, a, t);
        };
    }
    public static String hex(int rgb) { return String.format(java.util.Locale.ROOT, "#%06X", rgb & 0xFFFFFF); }
    public static int parse(String text) {
        String value = text.startsWith("#") ? text.substring(1) : text;
        if (!value.matches("[0-9a-fA-F]{6}")) throw new IllegalArgumentException("theme RGB");
        return Integer.parseInt(value, 16);
    }
}
