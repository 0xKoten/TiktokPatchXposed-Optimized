package com.golda.patchertiktok;

import android.app.Application;
import android.content.res.Resources;

import java.util.ArrayList;
import java.util.Collections;


/**
 * Keeps the content language on the user's language while the region spoof is active,
 * so a German SIM does not switch recommendations to German videos. TikTok's own app
 * language wins over the system language when the user picked one in TikTok.
 */
final class Recommendation {
    private static volatile Application app;

    private Recommendation() { }

    static String language() {
        Application context = app;
        Resources resources = context != null ? context.getResources() : Resources.getSystem();
        String language = resources.getConfiguration().getLocales().get(0).getLanguage();
        if (language.isEmpty()) language = Resources.getSystem().getConfiguration().getLocales().get(0).getLanguage();
        return language.isEmpty() ? "en" : language;
    }

    static void install(Application context, ClassLoader loader) {
        app = context;
        Hooks.Replace language = new Hooks.Replace() {
            @Override protected Object replace(Hooks.Call param) {
                return language();
            }
        };
        Class<?> service = Hooks.findClass(
                "com.ss.android.ugc.aweme.contentlanguage.ContentLanguageServiceImpl", loader);
        if (service != null) {
            try {
                Hooks.findAndHook(service, "getContentLanguage", language);
                Hooks.findAndHook(service, "getLanguage", new Hooks.Replace() {
                    @Override protected Object replace(Hooks.Call param) {
                        return new ArrayList<>(Collections.singletonList(language()));
                    }
                });
            } catch (Throwable error) {
                RuntimeLog.log("content language unavailable: " + error.getClass().getSimpleName());
            }
        }
        Class<?> guide = Hooks.findClass(
                "com.ss.android.ugc.aweme.contentlanguage.api.ContentLanguageGuideServiceImpl", loader);
        if (guide != null) {
            try {
                Hooks.findAndHook(guide, "getContentLanguage", language);
            } catch (Throwable ignored) { }
        }
    }
}
