package com.golda.patchertiktok;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The user's own feed filter: words or #hashtags, @authors, minimum likes and views.
 * Pure logic, so the matching can be tested without TikTok.
 */
final class FilterRules {
    static final FilterRules NONE = new FilterRules(Collections.emptyList(), Collections.emptyList(), 0, 0);

    private final List<String> words;
    private final List<String> authors;
    final long minLikes;
    final long minViews;

    private FilterRules(List<String> words, List<String> authors, long minLikes, long minViews) {
        this.words = words;
        this.authors = authors;
        this.minLikes = minLikes;
        this.minViews = minViews;
    }

    /** Entries are separated by commas or new lines; case does not matter. */
    static FilterRules parse(String words, String authors, long minLikes, long minViews) {
        return new FilterRules(split(words, false), split(authors, true), Math.max(0, minLikes), Math.max(0, minViews));
    }

    boolean isEmpty() {
        return words.isEmpty() && authors.isEmpty() && minLikes == 0 && minViews == 0;
    }

    /**
     * A plain word hides videos whose description or hashtags contain it; "#tag" matches only that
     * hashtag. Statistics below zero mean TikTok did not send them, and are not judged.
     */
    boolean hides(String description, List<String> hashtags, String username, String nickname, long likes, long views) {
        if (minLikes > 0 && likes >= 0 && likes < minLikes) return true;
        if (minViews > 0 && views >= 0 && views < minViews) return true;
        if (!authors.isEmpty()) {
            String user = normalize(username);
            String name = normalize(nickname);
            for (String author : authors) {
                if (author.equals(user) || author.equals(name)) return true;
            }
        }
        if (words.isEmpty()) return false;
        String text = normalize(description);
        List<String> tags = new ArrayList<>();
        if (hashtags != null) for (String tag : hashtags) tags.add(stripPrefix(normalize(tag), '#'));
        for (String word : words) {
            if (word.startsWith("#")) {
                String tag = word.substring(1);
                if (tags.contains(tag) || containsWord(text, word)) return true;
            } else if (text.contains(word)) {
                return true;
            } else {
                for (String tag : tags) if (tag.contains(word)) return true;
            }
        }
        return false;
    }

    private static boolean containsWord(String text, String hashtag) {
        int from = 0;
        while (true) {
            int at = text.indexOf(hashtag, from);
            if (at < 0) return false;
            int end = at + hashtag.length();
            if (end == text.length() || !Character.isLetterOrDigit(text.charAt(end)) && text.charAt(end) != '_') return true;
            from = at + 1;
        }
    }

    private static List<String> split(String value, boolean author) {
        if (value == null || value.trim().isEmpty()) return Collections.emptyList();
        List<String> entries = new ArrayList<>();
        for (String part : value.split("[,\\n]")) {
            String entry = normalize(part);
            if (author) entry = stripPrefix(entry, '@');
            if (!entry.isEmpty() && !"#".equals(entry) && !entries.contains(entry)) entries.add(entry);
        }
        return entries;
    }

    private static String stripPrefix(String value, char prefix) {
        return value.length() > 0 && value.charAt(0) == prefix ? value.substring(1) : value;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
