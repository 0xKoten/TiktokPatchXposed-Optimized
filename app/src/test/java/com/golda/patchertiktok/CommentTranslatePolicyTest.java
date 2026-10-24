package com.golda.patchertiktok;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;

import static org.junit.Assert.*;

public class CommentTranslatePolicyTest {
    @Test public void parsesLanguageTagsAliasesAndIsoThreeLetterCodes() {
        assertEquals(new HashSet<>(Arrays.asList("en", "uk", "pt", "id", "he", "ru")),
                CommentTranslatePolicy.exclusions(" EN-us, uk_UA;pt-BR\nin iw rus eng "));
        assertTrue(CommentTranslatePolicy.exclusions(null).isEmpty());
    }

    @Test public void unknownLanguagesNeverTriggerTranslation() {
        for (String unknown : Arrays.asList("", "und", "unknown", "auto", "zxx", "mul", "xx", "en/ru")) {
            assertEquals("", CommentTranslatePolicy.language(unknown));
            assertFalse(CommentTranslatePolicy.eligible(unknown, "ru", CommentTranslatePolicy.exclusions("")));
            assertFalse(CommentTranslatePolicy.eligible("ru", unknown, CommentTranslatePolicy.exclusions("")));
        }
    }

    @Test public void comparesPrimaryLanguagesAndHonorsExclusions() {
        assertFalse(CommentTranslatePolicy.eligible("en-GB", "en_US", CommentTranslatePolicy.exclusions("")));
        assertFalse(CommentTranslatePolicy.eligible("uk", "ru", CommentTranslatePolicy.exclusions("en, uk")));
        assertTrue(CommentTranslatePolicy.eligible("de-DE", "ru", CommentTranslatePolicy.exclusions("en, uk")));
    }

    @Test public void keysKeepNativeTargetVariantsSeparate() {
        assertEquals(CommentTranslatePolicy.key("123", "EN_us"), CommentTranslatePolicy.key("123", "en-US"));
        assertNotEquals(CommentTranslatePolicy.key("123", "zh-Hans"), CommentTranslatePolicy.key("123", "zh-Hant"));
        assertEquals("", CommentTranslatePolicy.key(null, "en"));
        assertEquals("", CommentTranslatePolicy.key("123", "und"));
    }

    @Test public void dispatchesAreSpacedWithoutBurstingAtWindowBoundaries() {
        CommentTranslatePolicy.Gate gate = new CommentTranslatePolicy.Gate(10, 10000);
        assertTrue(gate.acquire("first", 0, 2));
        assertEquals(501, gate.delay(0));
        assertFalse(gate.acquire("second", 500, 2));
        assertTrue(gate.acquire("second", 501, 2));
        assertFalse(gate.acquire("third", 1000, 2));
        assertTrue(gate.acquire("third", 1002, 2));
        assertFalse(gate.acquire("third", 2000, 2));
    }

    @Test public void failedAttemptsStayDeduplicatedUntilExpiry() {
        CommentTranslatePolicy.Gate gate = new CommentTranslatePolicy.Gate(3, 5000);
        assertTrue(gate.acquire("id", 100, 2));
        assertTrue(gate.seen("id", 5099));
        assertFalse(gate.seen("id", 5100));
        assertTrue(gate.acquire("id", 5100, 2));
    }

    @Test public void capacityDoesNotEvictRecentProtection() {
        CommentTranslatePolicy.Gate gate = new CommentTranslatePolicy.Gate(2, 10000);
        assertTrue(gate.acquire("a", 0, 5));
        assertTrue(gate.acquire("b", 201, 5));
        assertTrue(gate.seen("c", 500));
        assertFalse(gate.acquire("a", 500, 5));
        assertTrue(gate.acquire("c", 10000, 5));
        assertTrue(gate.seen("b", 10000));
    }

    @Test public void manualActionsRefreshDeduplicationWithoutConsumingRate() {
        CommentTranslatePolicy.Gate gate = new CommentTranslatePolicy.Gate(2, 1000);
        gate.remember("a", 0);
        gate.remember("b", 100);
        gate.remember("a", 200);
        assertFalse(gate.seen("b", 1100));
        assertTrue(gate.seen("a", 1100));
        assertTrue(gate.acquire("c", 1100, 1));
        assertEquals(1001, gate.delay(1100));
    }

    @Test public void ratesHaveFiniteBounds() {
        assertEquals(1, CommentTranslatePolicy.rate(Integer.MIN_VALUE));
        assertEquals(2, CommentTranslatePolicy.rate(2));
        assertEquals(5, CommentTranslatePolicy.rate(Integer.MAX_VALUE));
    }

    @Test public void reducingRateAlsoDelaysAlreadyQueuedWork() {
        CommentTranslatePolicy.Gate gate = new CommentTranslatePolicy.Gate(4, 10000);
        assertTrue(gate.acquire("a", 0, 5));
        assertEquals(701, gate.delay(300, 1));
        assertFalse(gate.acquire("b", 300, 1));
        assertTrue(gate.acquire("b", 1001, 1));
    }
}
