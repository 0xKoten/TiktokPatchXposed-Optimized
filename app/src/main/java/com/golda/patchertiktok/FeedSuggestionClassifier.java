package com.golda.patchertiktok;

import java.util.Locale;

final class FeedSuggestionClassifier {
    private FeedSuggestionClassifier() {
    }

    static boolean shouldRemove(
            boolean familiar,
            boolean hasRelationInfo,
            boolean hasAcquaintanceMarker
    ) {
        return hasAcquaintanceMarker || (familiar && hasRelationInfo);
    }

    static boolean hasAcquaintanceMarker(Object value) {
        if (!(value instanceof CharSequence)) return false;

        String normalized = value.toString().trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return false;

        return "maf".equals(normalized)
                || normalized.contains("people_you_may_know")
                || normalized.contains("people you may know")
                || normalized.contains("may_know")
                || normalized.contains("acquaint")
                || (normalized.contains("вероятн") && normalized.contains("знаком"))
                || (normalized.contains("возможн") && normalized.contains("знаком"))
                || (normalized.contains("personen") && normalized.contains("kennen"));
    }
}
