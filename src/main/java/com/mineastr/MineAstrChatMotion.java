package com.mineastr;

import java.util.function.DoubleUnaryOperator;

/**
 * Frame-rate independent, retargetable animation; no Minecraft or UI dependency.
 * <p>与帧率无关的动画状态；可重新指定目标，不依赖游戏对象。<br>
 * Maintain retargetable, frame-rate independent animation without game dependencies.</p>
 */
public final class MineAstrChatMotion {
    private final DoubleUnaryOperator curve;
    private double start, target, startedAt, duration;

    public MineAstrChatMotion(DoubleUnaryOperator curve) { this.curve = curve; }

    public static double gentle(double progress) {
        double remaining = 1 - Math.clamp(progress, 0, 1);
        return 1 - remaining * remaining * remaining;
    }

    public double value(double now) {
        if (duration <= 0 || now >= startedAt + duration) return target;
        double progress = Math.clamp((now - startedAt) / duration, 0, 1);
        return start + (target - start) * curve.applyAsDouble(progress);
    }

    public void target(double value, double now, double milliseconds) {
        if (value == target) return;
        start = value(now);
        target = value;
        startedAt = now;
        duration = Math.max(0, milliseconds);
    }

    public void snap(double value) { start = target = value; duration = 0; }

    /** Preserve a reader's position when rows arrive while viewing older history. */
    public void offset(double rows) { start += rows; target += rows; }
}
