package com.golda.patchertiktok;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class FilterRulesTest {
    @Test public void emptyRulesHideNothing() {
        FilterRules rules = FilterRules.parse(" , \n", "", 0, 0);
        assertTrue(rules.isEmpty());
        assertFalse(rules.hides("anything", Collections.singletonList("tag"), "user", "Name", 1, 1));
    }

    @Test public void wordsMatchDescriptionAndHashtagsIgnoringCase() {
        FilterRules rules = FilterRules.parse("Прогноз, crypto", "", 0, 0);
        assertTrue(rules.hides("Мой ПРОГНОЗ на матч", null, "u", "n", -1, -1));
        assertTrue(rules.hides("no words here", Arrays.asList("CryptoNews"), "u", "n", -1, -1));
        assertFalse(rules.hides("weather today", Arrays.asList("fyp"), "u", "n", -1, -1));
    }

    @Test public void hashtagEntryMatchesOnlyThatTag() {
        FilterRules rules = FilterRules.parse("#ad", "", 0, 0);
        assertTrue(rules.hides("new drop", Arrays.asList("#Ad"), "u", "n", -1, -1));
        assertTrue(rules.hides("new drop #ad", null, "u", "n", -1, -1));
        assertFalse(rules.hides("new drop #adventure", Arrays.asList("adventure"), "u", "n", -1, -1));
        assertFalse(rules.hides("a bad day", null, "u", "n", -1, -1));
    }

    @Test public void authorsByUsernameOrNickname() {
        FilterRules rules = FilterRules.parse("", "@Spammer\nSome Name", 0, 0);
        assertTrue(rules.hides("", null, "spammer", "x", -1, -1));
        assertTrue(rules.hides("", null, "other", "some name", -1, -1));
        assertFalse(rules.hides("", null, "spammer2", "x", -1, -1));
    }

    @Test public void minimumLikesAndViewsSkipUnknownStatistics() {
        FilterRules rules = FilterRules.parse("", "", 100, 1000);
        assertTrue(rules.hides("", null, "u", "n", 99, 5000));
        assertTrue(rules.hides("", null, "u", "n", 500, 999));
        assertFalse(rules.hides("", null, "u", "n", 100, 1000));
        assertFalse(rules.hides("", null, "u", "n", -1, -1));
    }
}
