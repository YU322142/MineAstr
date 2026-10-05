package com.mineastr;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MineAstrScrollStateTest {
    @Test void fractionalWheelPositionSurvivesAnimationAndRest() {
        var state = new MineAstrScrollState();
        state.bounds(900, 180, 0);
        state.wheel(22.5, 10, 180);
        assertEquals(22.5, state.value(190), 1e-9);
        assertEquals(22.5, state.value(10000), 1e-9);
        assertEquals(2.5, state.value(10000) / 9, 1e-9);
    }
    @Test void smallTrackpadDeltasAccumulateWithoutBeingTruncated() {
        var state = new MineAstrScrollState(); state.bounds(900, 180, 0);
        for (int i = 0; i < 10; i++) state.wheel(.125, i, 100);
        assertEquals(1.25, state.value(200), 1e-9);
    }
    @Test void draggingPreservesGrabPointAndChangesPositionContinuously() {
        var state = new MineAstrScrollState(); state.bounds(1000, 200, 0);
        state.to(160.25, 0, 0);
        double top = state.thumbTop(20, 200, 1000, false, 0);
        state.beginDrag(top + 7, top, state.thumbSize(200, 1000));
        state.drag(top + 7, 20, 200, 1000, false, 0);
        assertEquals(160.25, state.value(0), 1e-9);
        state.drag(top + 7.5, 20, 200, 1000, false, 0);
        assertEquals(162.75, state.value(0), 1e-9);
        assertTrue(state.endDrag()); assertFalse(state.endDrag());
    }
    @Test void chatScrollbarHasNewestAtBottomAndCanReachBothEnds() {
        var state = new MineAstrScrollState(); state.bounds(1000, 200, 0);
        double size = state.thumbSize(200, 1000);
        double bottom = state.thumbTop(20, 200, 1000, true, 0);
        assertEquals(180, bottom);
        state.beginDrag(bottom + 5, bottom, size);
        state.drag(25, 20, 200, 1000, true, 0);
        assertEquals(800, state.value(0));
        state.drag(1000, 20, 200, 1000, true, 0);
        assertEquals(0, state.value(0));
    }
    @Test void newMessagesAnchorReadersWithoutLosingFractionalOffset() {
        var state = new MineAstrScrollState(); state.bounds(900, 180, 0);
        state.to(45.25, 0, 0); state.offset(27); state.bounds(927, 180, 1);
        assertEquals(72.25, state.value(1000));
        assertEquals(72.25, state.target());
    }
    @Test void shrinkingViewportOrHistoryClampsWithoutNaN() {
        var state = new MineAstrScrollState(); state.bounds(1000, 200, 0); state.to(800, 0, 0);
        state.bounds(90, 200, 1);
        assertEquals(0, state.value(200));
        assertEquals(200, state.thumbSize(200, 90));
        assertEquals(20, state.thumbTop(20, 200, 90, false, 0));
    }
    @Test void partiallyVisibleRowsAndControlsIntersectViewport() {
        assertTrue(MineAstrScrollState.intersects(90.5, 20, 100, 200));
        assertTrue(MineAstrScrollState.intersects(199.5, 20, 100, 200));
        assertFalse(MineAstrScrollState.intersects(80, 20, 100, 200));
        assertFalse(MineAstrScrollState.intersects(200, 20, 100, 200));
    }
}
