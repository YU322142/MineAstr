package com.mineastr;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MineAstrChatInsertionMotionTest {
    @Test void oldRowsStayAtTheirVisiblePositionsWhenAnotherMessageArrives() {
        var shift = new MineAstrChatInsertionMotion();
        shift.push(2, 0, 200);
        double oldY = -10 * 9 + shift.value(60) * 9;
        double oldSpeed = shift.velocity(60);
        shift.push(3, 60, 200);
        assertEquals(oldY, -13 * 9 + shift.value(60) * 9, 1e-9);
        assertEquals(oldSpeed, shift.velocity(60), 1e-9);
    }

    @Test void longImagesAndBurstsDoNotClipTheAnimationShiftToOnePage() {
        var shift = new MineAstrChatInsertionMotion();
        shift.push(18, 0, 200);
        double before = shift.value(10);
        shift.push(14, 10, 200);
        assertEquals(before + 14, shift.value(10), 1e-9);
        assertTrue(shift.value(10) > 20);
    }

    @Test void gentleQueueMovementNeverOvershootsAndSettles() {
        var shift = new MineAstrChatInsertionMotion();
        shift.push(8, 0, 200);
        double previous = 8;
        for (int time = 0; time <= 600; time++) {
            double value = shift.value(time);
            assertTrue(value >= 0 && value <= previous);
            previous = value;
        }
        assertEquals(0, shift.value(600));
    }

    @Test void samplingAtDifferentFrameRatesDoesNotChangeQueueMotion() {
        var fast = new MineAstrChatInsertionMotion();
        var slow = new MineAstrChatInsertionMotion();
        fast.push(5, 0, 240); slow.push(5, 0, 240);
        for (int time = 0; time < 80; time++) fast.value(time);
        fast.push(2, 80, 240); slow.push(2, 80, 240);
        assertEquals(fast.value(180), slow.value(180));
    }

    @Test void clearingAnimationDropsAllPreviousMotion() {
        var shift = new MineAstrChatInsertionMotion();
        shift.push(10, 0, 200); shift.clear();
        assertEquals(0, shift.value(30));
        assertEquals(0, shift.velocity(30));
        shift.push(1, 40, 200);
        assertEquals(1, shift.value(40));
    }
}
