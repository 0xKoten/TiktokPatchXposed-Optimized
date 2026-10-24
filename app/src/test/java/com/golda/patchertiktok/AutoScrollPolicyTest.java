package com.golda.patchertiktok;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AutoScrollPolicyTest {
    @Test public void endingLoopCounts() {
        assertTrue(AutoScrollPolicy.looped(14_600, 200, 15_000));
        assertTrue(AutoScrollPolicy.looped(5_000, 0, 5_200));
    }

    @Test public void seekingBackFromTheMiddleDoesNot() {
        assertFalse(AutoScrollPolicy.looped(8_000, 300, 15_000));
    }

    @Test public void normalPlaybackAndUnknownsDoNot() {
        assertFalse(AutoScrollPolicy.looped(14_600, 14_900, 15_000));
        assertFalse(AutoScrollPolicy.looped(-1, 0, 15_000));
        assertFalse(AutoScrollPolicy.looped(14_600, 200, 0));
        assertFalse(AutoScrollPolicy.looped(14_600, 4_000, 15_000));
    }

    @Test public void photoPostsGetSecondsPerPhotoWithinTheirMusic() {
        org.junit.Assert.assertEquals(10_000L, AutoScrollPolicy.photoTime(3, 3_000L, 46_000L));
        org.junit.Assert.assertEquals(5_000L, AutoScrollPolicy.photoTime(1, 3_000L, 0L));
        org.junit.Assert.assertEquals(30_000L, AutoScrollPolicy.photoTime(35, 3_000L, 30_000L));
    }
}
