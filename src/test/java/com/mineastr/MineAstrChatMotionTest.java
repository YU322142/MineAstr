package com.mineastr;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MineAstrChatMotionTest {
    @Test void scrollingStartsAtCurrentPositionAndSettlesExactly() {
        var motion = new MineAstrChatMotion(MineAstrChatMotion::gentle);
        motion.target(8, 1000, 180);
        assertEquals(0, motion.value(1000));
        assertTrue(motion.value(1090) > 4);
        assertEquals(8, motion.value(1180));
        assertEquals(8, motion.value(5000));
    }

    @Test void rapidWheelReversalRetargetsWithoutJumping() {
        var motion = new MineAstrChatMotion(MineAstrChatMotion::gentle);
        motion.target(12, 0, 200);
        double before = motion.value(60);
        motion.target(2, 60, 200);
        assertEquals(before, motion.value(60));
        assertTrue(motion.value(80) < before);
        assertEquals(2, motion.value(260));
    }

    @Test void frameRateDoesNotChangeAnimationPosition() {
        var fast = new MineAstrChatMotion(MineAstrChatMotion::gentle);
        var slow = new MineAstrChatMotion(MineAstrChatMotion::gentle);
        fast.target(9, 100, 240);
        slow.target(9, 100, 240);
        for (int time = 101; time < 220; time++) fast.value(time);
        assertEquals(slow.value(220), fast.value(220));
    }

    @Test void arrivingRowsPreserveHistoryPositionDuringAnExistingScroll() {
        var motion = new MineAstrChatMotion(MineAstrChatMotion::gentle);
        motion.snap(10);
        motion.target(20, 0, 200);
        double before = motion.value(80);
        motion.offset(7);
        assertEquals(before + 7, motion.value(80));
        assertEquals(27, motion.value(200));
    }

    @Test void disabledMotionSnapsAndCannotResumeOldTransitions() {
        var motion = new MineAstrChatMotion(MineAstrChatMotion::gentle);
        motion.target(100, 0, 200);
        motion.snap(3);
        assertEquals(3, motion.value(10));
        assertEquals(3, motion.value(1000));
    }

    @Test void gentleCurveNeverOvershootsAndSlowsNearTheEnd() {
        double previous = 0;
        for (int step = 0; step <= 100; step++) {
            double value = MineAstrChatMotion.gentle(step / 100.0);
            assertTrue(value >= previous && value <= 1);
            previous = value;
        }
        assertTrue(MineAstrChatMotion.gentle(.1) > 1 - MineAstrChatMotion.gentle(.9));
    }
}
