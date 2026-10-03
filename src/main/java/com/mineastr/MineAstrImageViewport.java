package com.mineastr;

/** Cursor-anchored zoom and constrained panning; contains no rendering or image IO. */
public final class MineAstrImageViewport {
    private double zoom = 1, panX, panY;
    public double zoom() { return zoom; }
    public double panX() { return panX; }
    public double panY() { return panY; }
    public void reset() { zoom = 1; panX = panY = 0; }
    public void scroll(double amount, double cursorX, double cursorY, double fittedWidth, double fittedHeight, double availableWidth, double availableHeight) {
        double previous = zoom;
        zoom = Math.clamp(zoom * Math.pow(1.2, Math.clamp(amount, -8, 8)), .25, 16);
        double ratio = zoom / previous;
        panX = cursorX - (cursorX - panX) * ratio;
        panY = cursorY - (cursorY - panY) * ratio;
        constrain(fittedWidth, fittedHeight, availableWidth, availableHeight);
    }
    public void drag(double dx, double dy, double fittedWidth, double fittedHeight, double availableWidth, double availableHeight) {
        panX += dx; panY += dy;
        constrain(fittedWidth, fittedHeight, availableWidth, availableHeight);
    }
    public void constrain(double fittedWidth, double fittedHeight, double availableWidth, double availableHeight) {
        double limitX = Math.max(0, (fittedWidth * zoom + availableWidth) / 2 - 24);
        double limitY = Math.max(0, (fittedHeight * zoom + availableHeight) / 2 - 24);
        panX = Math.clamp(panX, -limitX, limitX);
        panY = Math.clamp(panY, -limitY, limitY);
    }
}
