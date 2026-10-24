package com.golda.patchertiktok;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FeedSuggestionClassifierTest {
    @Test
    public void detectsServerAndLocalizedMarkers() {
        assertTrue(FeedSuggestionClassifier.hasAcquaintanceMarker("people_you_may_know"));
        assertTrue(FeedSuggestionClassifier.hasAcquaintanceMarker("People you may know"));
        assertTrue(FeedSuggestionClassifier.hasAcquaintanceMarker("Ваши вероятные знакомые"));
        assertTrue(FeedSuggestionClassifier.hasAcquaintanceMarker("Возможные знакомые"));
        assertTrue(FeedSuggestionClassifier.hasAcquaintanceMarker("maf"));
    }

    @Test
    public void keepsOrdinaryRecommendationSignals() {
        assertFalse(FeedSuggestionClassifier.hasAcquaintanceMarker("recommended_for_you"));
        assertFalse(FeedSuggestionClassifier.hasAcquaintanceMarker("trending"));
        assertFalse(FeedSuggestionClassifier.hasAcquaintanceMarker(null));
        assertFalse(FeedSuggestionClassifier.shouldRemove(false, true, false));
    }

    @Test
    public void removesFamiliarItemsOnlyWithRelationMetadata() {
        assertTrue(FeedSuggestionClassifier.shouldRemove(true, true, false));
        assertFalse(FeedSuggestionClassifier.shouldRemove(true, false, false));
        assertTrue(FeedSuggestionClassifier.shouldRemove(false, false, true));
    }
}
