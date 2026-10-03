package com.mineastr;

/** Analytic critical damping: arriving rows preserve both position and velocity. */
public final class MineAstrChatInsertionMotion {
    private double rows, speed, startedAt, rate = .03;

    public double value(double now) {
        double elapsed = Math.max(0, now - startedAt);
        double value = (rows + (speed + rate * rows) * elapsed) * Math.exp(-rate * elapsed);
        return value < .0001 ? 0 : value;
    }

    public double velocity(double now) {
        double elapsed = Math.max(0, now - startedAt);
        return (speed - rate * (speed + rate * rows) * elapsed) * Math.exp(-rate * elapsed);
    }

    public void push(int addedRows, double now, int milliseconds) {
        if (addedRows <= 0) return;
        double current = value(now), currentSpeed = velocity(now);
        rate = 6.0 / Math.max(1, milliseconds);
        rows = current + addedRows;
        speed = Math.max(-rate * rows, currentSpeed);
        startedAt = now;
    }

    public void clear() { rows = speed = 0; }
}
