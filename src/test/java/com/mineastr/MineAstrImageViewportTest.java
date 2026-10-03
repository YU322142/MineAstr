package com.mineastr;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MineAstrImageViewportTest {
    @Test void cursorPointRemainsUnderTheCursorAfterZoom() {
        var viewport = new MineAstrImageViewport();
        viewport.drag(12, -7, 800, 600, 1000, 700);
        double imageX = (80 - viewport.panX()) / viewport.zoom();
        double imageY = (-40 - viewport.panY()) / viewport.zoom();
        viewport.scroll(2, 80, -40, 800, 600, 1000, 700);
        assertEquals(80, imageX * viewport.zoom() + viewport.panX(), 1e-9);
        assertEquals(-40, imageY * viewport.zoom() + viewport.panY(), 1e-9);
    }
    @Test void rapidScrollingIsBoundedAndResetRestoresTheFittedView() {
        var viewport = new MineAstrImageViewport();
        for (int i=0;i<100;i++) viewport.scroll(1000, 0, 0, 800, 600, 1000, 700);
        assertEquals(16, viewport.zoom());
        for (int i=0;i<100;i++) viewport.scroll(-1000, 0, 0, 800, 600, 1000, 700);
        assertEquals(.25, viewport.zoom());
        viewport.reset();
        assertEquals(1, viewport.zoom()); assertEquals(0, viewport.panX()); assertEquals(0, viewport.panY());
    }
    @Test void DragAndWindowResizeCannotLoseTheImageOutsideTheViewport() {
        var viewport = new MineAstrImageViewport();
        viewport.drag(100000, -100000, 800, 600, 1000, 700);
        assertTrue(viewport.panX() <= (800 + 1000)/2.0 - 24);
        assertTrue(-viewport.panY() <= (600 + 700)/2.0 - 24);
        viewport.constrain(160,120,200,180);
        assertTrue(viewport.panX() <= (160 + 200)/2.0 - 24);
        assertTrue(-viewport.panY() <= (120 + 180)/2.0 - 24);
    }
}
