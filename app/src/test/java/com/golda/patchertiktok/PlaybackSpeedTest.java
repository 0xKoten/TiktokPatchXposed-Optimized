package com.golda.patchertiktok;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class PlaybackSpeedTest {
    private static final List<Float> DEFAULT = Arrays.asList(0.5f, 1f, 1.5f, 2f);

    @Test public void emptyInputUsesStandardSpeeds() {
        assertEquals(DEFAULT, PlaybackSpeed.parseSpeeds(null));
        assertEquals(DEFAULT, PlaybackSpeed.parseSpeeds(""));
        assertEquals(DEFAULT, PlaybackSpeed.parseSpeeds(" \t\n"));
        assertEquals(DEFAULT, PlaybackSpeed.parseSpeeds(", ; ,"));
    }

    @Test public void parsesRequestedCustomMenu() {
        assertEquals(Arrays.asList(0.75f, 1f, 1.25f, 1.5f, 2f, 3f),
                PlaybackSpeed.parseSpeeds("0.75, 1, 1.25, 1.5, 2, 3"));
    }

    @Test public void sortsAndDeduplicatesNumericValues() {
        assertEquals(Arrays.asList(0.75f, 1f, 1.5f, 2f),
                PlaybackSpeed.parseSpeeds("2, 1.50, 1, 0.75, 1.5, 1.0, 2"));
    }

    @Test public void clampsBeforeDeduplication() {
        assertEquals(Arrays.asList(0.25f, 1f, 3f),
                PlaybackSpeed.parseSpeeds("-1, -0.0, 0, 0.1, 0.25, 1, 3, 4, 100"));
    }

    @Test public void includesBothBounds() {
        assertEquals(Arrays.asList(0.25f, 3f), PlaybackSpeed.parseSpeeds("3,0.25"));
    }

    @Test public void skipsInvalidAndNonfiniteTokens() {
        assertEquals(Arrays.asList(0.75f, 1.25f),
                PlaybackSpeed.parseSpeeds("NaN,Infinity,-Infinity,1e100,broken,,0.75,1.25"));
        assertEquals(DEFAULT, PlaybackSpeed.parseSpeeds("NaN,Infinity,-Infinity,invalid"));
    }

    @Test public void acceptsWhitespaceSemicolonsAndScientificNotation() {
        assertEquals(Arrays.asList(0.5f, 1f, 1.25f, 2f),
                PlaybackSpeed.parseSpeeds("5e-1; 1\n1.25\t2"));
    }

    @Test public void returnsIndependentMutableLists() {
        List<Float> first = PlaybackSpeed.parseSpeeds("");
        first.clear();
        assertEquals(DEFAULT, PlaybackSpeed.parseSpeeds(""));
        List<Float> custom = PlaybackSpeed.parseSpeeds("1,2");
        custom.add(3f);
        assertEquals(Arrays.asList(1f, 2f), PlaybackSpeed.parseSpeeds("1,2"));
    }
}
