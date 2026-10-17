package com.mineastr;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Uses ModernUI's actual cubic deceleration interpolator when installed.
 * <p>可选 ModernUI 缓动适配；缺少依赖时保留本地插值回退。<br>
 * Adapt optional ModernUI easing with a local interpolation fallback.</p>
 */
public final class MineAstrChatEasing {
    private static MethodHandle modernCurve = load();

    private MineAstrChatEasing() {}

    private static MethodHandle load() {
        try {
            Class<?> api = Class.forName("icyllis.modernui.animation.TimeInterpolator");
            Object curve = api.getField("DECELERATE_CUBIC").get(null);
            return MethodHandles.publicLookup().findVirtual(api, "getInterpolation",
                    MethodType.methodType(float.class, float.class)).bindTo(curve);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    public static double gentle(double progress) {
        progress = Math.clamp(progress, 0, 1);
        MethodHandle curve = modernCurve;
        if (curve != null) {
            try { return (float) curve.invokeExact((float) progress); }
            catch (Throwable error) { modernCurve = null; }
        }
        return MineAstrChatMotion.gentle(progress);
    }

    public static boolean usesModernUI() { return modernCurve != null; }
    public static double now() { return System.nanoTime() / 1_000_000.0; }
}
