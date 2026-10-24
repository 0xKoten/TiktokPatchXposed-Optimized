package com.golda.patchertiktok;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SeekbarPolicyTest {
    @Test
    public void makesHiddenBarDraggable() {
        assertEquals(4, SeekbarPolicy.normalizeShowType(3));
        assertEquals(4, SeekbarPolicy.normalizeShowType(4));
        assertEquals(0, SeekbarPolicy.normalizeShowType(0));
        assertEquals(1, SeekbarPolicy.normalizeShowType(1));
        assertEquals(2, SeekbarPolicy.normalizeShowType(2));
        assertEquals(100, SeekbarPolicy.normalizeShowType(100));
    }

    @Test
    public void forcesOnlyRegularVideoModels() {
        assertTrue(SeekbarPolicy.shouldForceShow(false, true, false, false, false));
        assertFalse(SeekbarPolicy.shouldForceShow(false, false, false, false, false));
        assertFalse(SeekbarPolicy.shouldForceShow(false, true, true, false, false));
        assertFalse(SeekbarPolicy.shouldForceShow(false, true, false, true, false));
        assertFalse(SeekbarPolicy.shouldForceShow(false, true, false, false, true));
    }

    @Test
    public void liftsOnlyShortClipAndDragReasons() {
        assertTrue(SeekbarPolicy.liftable("can not show seekbar, state: 2, can not drag"));
        assertTrue(SeekbarPolicy.liftable("can not show seekbar, state: 4, can not show progressbar"));
        assertTrue(SeekbarPolicy.liftable("can not show seekbar, state: 6, hit inverse expriment"));
        assertFalse(SeekbarPolicy.liftable("can not show seekbar, state: 1, not in resume"));
        assertFalse(SeekbarPolicy.liftable("can not show seekbar, state: 3, not current aweme"));
        assertFalse(SeekbarPolicy.liftable("can not show seekbar, in story immersiveFeed"));
        assertFalse(SeekbarPolicy.liftable("can not show seekbar, state: 14, prompt overlay"));
    }

    @Test
    public void preservesAnExistingPositiveDecision() {
        assertTrue(SeekbarPolicy.shouldForceShow(true, false, true, true, true));
    }
}
