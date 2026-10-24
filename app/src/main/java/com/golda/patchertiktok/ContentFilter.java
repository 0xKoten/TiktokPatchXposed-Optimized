package com.golda.patchertiktok;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * What a video is, read from TikTok's own (unobfuscated) video model: paid or shop content,
 * an AI label, and the fields the user's own filter looks at. Search results get the same
 * treatment for their ad cards.
 */
final class ContentFilter {
    private static final String SEARCH_ITEM = "com.ss.android.ugc.aweme.search.pages.result.topsearch.core.model.SearchMixFeed";

    private ContentFilter() { }

    /** Paid series and videos selling products (TikTok Shop showcases, product links). */
    static boolean isPromo(Object aweme) {
        if (aweme == null) return false;
        if (Reflect.callBoolean(aweme, "isPaidContent")) return true;
        Object products = Reflect.call(aweme, "getProductsInfo");
        if (products instanceof Collection && !((Collection<?>) products).isEmpty()) return true;
        Object count = Reflect.call(aweme, "getProductsCount");
        if (count instanceof Number && ((Number) count).longValue() > 0) return true;
        return Reflect.call(aweme, "getOutflowProductData") != null
                || Reflect.call(aweme, "getEcomAnchorViewObject") != null;
    }

    /** Labelled as AI-generated, by the creator or by TikTok's moderation. */
    static boolean isAi(Object aweme) {
        if (aweme == null) return false;
        Object info = Reflect.call(aweme, "getAigcInfo");
        if (info != null) {
            if (Reflect.getBoolean(info, "createByAI")) return true;
            Object label = Reflect.call(info, "getAIGCLabelType");
            if (label instanceof Number && ((Number) label).intValue() > 0) return true;
        }
        Object moderation = Reflect.call(aweme, "getModerationAigcInfo");
        if (moderation != null) {
            Object label = Reflect.get(moderation, "moderationAigcLabelType");
            if (label instanceof Number && ((Number) label).intValue() > 0) return true;
        }
        return false;
    }

    static boolean hiddenByRules(Object aweme, FilterRules rules) {
        if (aweme == null || rules.isEmpty()) return false;
        long likes = -1;
        long views = -1;
        Object statistics = Reflect.call(aweme, "getStatistics");
        if (statistics != null) {
            likes = number(Reflect.call(statistics, "getDiggCount"));
            views = number(Reflect.call(statistics, "getPlayCount"));
        }
        Object author = Reflect.call(aweme, "getAuthor");
        String username = author == null ? null : string(Reflect.call(author, "getUniqueId"));
        String nickname = author == null ? null : string(Reflect.call(author, "getNickname"));
        return rules.hides(string(Reflect.call(aweme, "getDesc")), hashtags(aweme), username, nickname, likes, views);
    }

    private static List<String> hashtags(Object aweme) {
        List<String> tags = new ArrayList<>();
        Object extras = Reflect.call(aweme, "getTextExtra");
        if (!(extras instanceof Collection)) return tags;
        for (Object extra : (Collection<?>) extras) {
            String tag = string(Reflect.call(extra, "getHashTagName"));
            if (tag != null && !tag.isEmpty()) tags.add(tag);
        }
        return tags;
    }

    /**
     * Search results mix videos with ad cards (sponsored results, brand zones, AI ad cards).
     * Their getters return nothing, so TikTok skips those cards; sponsored or selling videos stop
     * counting as videos.
     */
    static void installSearch(ClassLoader loader) {
        Class<?> item = Hooks.findClass(SEARCH_ITEM, loader);
        if (item == null) {
            RuntimeLog.log("search ads filter unavailable");
            return;
        }
        Hooks.Hook none = new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                if (Prefs.on(Prefs.PROMO)) param.setResult(null);
            }
        };
        int hooks = 0;
        for (String name : new String[]{"getPreciseAd", "getAiAdCard", "getBrandZoneCard"}) {
            try {
                Hooks.hookAll(item, name, none);
                hooks++;
            } catch (Throwable ignored) { }
        }
        Hooks.Hook video = new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                if (!Prefs.on(Prefs.PROMO)) return;
                Object aweme = Reflect.get(param.thisObject, "aweme");
                if (FeedFilter.isAd(aweme) || isPromo(aweme)) {
                    param.setResult(param.getResult() instanceof Boolean ? Boolean.FALSE : null);
                }
            }
        };
        for (String name : new String[]{"getAweme", "isAweme", "isVisualSearchAweme"}) {
            try {
                Hooks.hookAll(item, name, video);
                hooks++;
            } catch (Throwable ignored) { }
        }
        RuntimeLog.log("search ads filter: hooks=" + hooks);
    }

    private static long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : -1;
    }

    private static String string(Object value) {
        return value instanceof String ? (String) value : null;
    }
}
