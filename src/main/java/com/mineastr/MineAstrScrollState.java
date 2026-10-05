package com.mineastr;

/** Continuous pixel scrolling shared by chat and settings; no row quantization. */
public final class MineAstrScrollState {
    private final MineAstrChatMotion motion = new MineAstrChatMotion(MineAstrChatEasing::gentle);
    private double target, maximum;
    private boolean dragging;
    private double grab;

    public double value(double now) { return Math.clamp(motion.value(now), 0, maximum); }
    public double target() { return target; }
    public double maximum() { return maximum; }
    public boolean dragging() { return dragging; }
    public void bounds(double content, double viewport, double now) {
        maximum = Math.max(0, content - viewport);
        double current = value(now);
        target = Math.clamp(target, 0, maximum);
        motion.snap(current);
        motion.target(target, now, 120);
    }
    public void to(double pixels, double now, double duration) {
        target = Math.clamp(pixels, 0, maximum);
        if (duration <= 0) motion.snap(target); else motion.target(target, now, duration);
    }
    public void wheel(double pixels, double now, double duration) { to(target + pixels, now, duration); }
    public void offset(double pixels) {
        target += pixels;
        motion.offset(pixels);
    }
    public void reset() { target = 0; motion.snap(0); dragging = false; }
    public double thumbSize(double viewport, double content) {
        return Math.min(viewport, Math.max(12, content <= 0 ? viewport : viewport * viewport / content));
    }
    public double thumbTop(double top, double viewport, double content, boolean bottomOrigin, double now) {
        double fraction = maximum == 0 ? 0 : value(now) / maximum;
        if (bottomOrigin) fraction = 1 - fraction;
        return top + fraction * (viewport - thumbSize(viewport, content));
    }
    public void beginDrag(double mouse, double thumbTop, double thumbSize) {
        dragging = true;
        grab = mouse >= thumbTop && mouse < thumbTop + thumbSize ? mouse - thumbTop : thumbSize / 2;
    }
    public void drag(double mouse, double trackTop, double viewport, double content, boolean bottomOrigin, double now) {
        if (!dragging) return;
        double travel = viewport - thumbSize(viewport, content);
        double fraction = travel <= 0 ? 0 : Math.clamp((mouse - trackTop - grab) / travel, 0, 1);
        to((bottomOrigin ? 1 - fraction : fraction) * maximum, now, 0);
    }
    public boolean endDrag() { boolean previous = dragging; dragging = false; return previous; }
    public static boolean intersects(double top, double height, double viewportTop, double viewportBottom) {
        return top < viewportBottom && top + height > viewportTop;
    }
}
