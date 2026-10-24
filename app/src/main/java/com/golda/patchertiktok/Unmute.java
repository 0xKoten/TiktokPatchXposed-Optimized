package com.golda.patchertiktok;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Plays videos TikTok muted over a copyright claim. The two answers behind that decision, "the
 * sound was pulled" and "this video is muted", are changed only while the player is deciding, so
 * labels and share/export rules elsewhere stay as TikTok has them. Audio that the server no longer
 * delivers cannot come back. Install after Discovery.init.
 */
final class Unmute {
    private static final String PLAYER = "com.ss.android.ugc.aweme.feed.controller.PlayerController";
    private static final String MUSIC = "com.ss.android.ugc.aweme.music.model.Music";
    private static final String MUTE_INFO = "com.ss.android.ugc.aweme.feed.model.AwemeStatus$VideoMuteInfo";
    private static final String AWEME = "com.ss.android.ugc.aweme.feed.model.Aweme";
    private static final String SEARCH_MUTE =
            "com.ss.android.ugc.aweme.search.pages.result.common.copyrightmute.core.communicate.SearchCopyrightMuteServiceImpl";
    /** Music.status of a sound removed from the library; 1 is available. */
    private static final int PULLED = 0;
    private static final int AVAILABLE = 1;

    /** How deep this thread is inside the player's mute decision. */
    private static final ThreadLocal<int[]> DECIDING = ThreadLocal.withInitial(() -> new int[1]);

    private Unmute() { }

    static void install(ClassLoader loader) {
        Class<?> player = Hooks.findClass(PLAYER, loader);
        Class<?> music = Hooks.findClass(MUSIC, loader);
        Class<?> muteInfo = Hooks.findClass(MUTE_INFO, loader);
        Class<?> aweme = Hooks.findClass(AWEME, loader);
        if (player == null || music == null || muteInfo == null || aweme == null) {
            RuntimeLog.log("unmute: models missing");
            return;
        }
        int deciders = hookDecision(player, aweme);
        if (deciders > 0) {
            Hooks.hookAll(music, "getMusicStatus", new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    if (active() && param.getResult() instanceof Integer && (Integer) param.getResult() == PULLED) {
                        param.setResult(AVAILABLE);
                    }
                }
            });
            Hooks.hookAll(muteInfo, "isMute", new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    if (active()) param.setResult(false);
                }
            });
        }
        int search = hookSearch(loader, aweme);
        RuntimeLog.log("unmute: player " + deciders + ", search " + search);
    }

    private static boolean active() {
        return DECIDING.get()[0] > 0 && Prefs.on(Prefs.UNMUTE);
    }

    /**
     * The player method that reads both answers: (Aweme, player, boolean) -> void on PlayerController,
     * calling Music.getMusicStatus and VideoMuteInfo.isMute.
     */
    private static int hookDecision(Class<?> player, Class<?> aweme) {
        Set<String> status = owners(Discovery.methodsCalling(MUSIC, "getMusicStatus"), PLAYER);
        Set<String> mute = owners(Discovery.methodsCalling(MUTE_INFO, "isMute"), PLAYER);
        status.retainAll(mute);
        int count = 0;
        for (Method method : player.getDeclaredMethods()) {
            Class<?>[] p = method.getParameterTypes();
            if (!status.contains(method.getName()) || Modifier.isAbstract(method.getModifiers())
                    || method.getReturnType() != void.class || p.length != 3 || p[0] != aweme
                    || p[2] != boolean.class || p[1].isPrimitive()) continue;
            try {
                Hooks.hook(method, new Hooks.Hook() {
                    @Override protected void before(Hooks.Call param) { DECIDING.get()[0]++; }

                    @Override protected void after(Hooks.Call param) {
                        int[] depth = DECIDING.get();
                        if (depth[0] > 0) depth[0]--;
                    }
                });
                count++;
            } catch (Throwable ignored) { }
        }
        return count;
    }

    /** Method names of {@code owner} among "class#method" hits. */
    private static Set<String> owners(List<String> hits, String owner) {
        Set<String> names = new HashSet<>();
        for (String hit : hits) {
            int split = hit.indexOf('#');
            if (split > 0 && hit.substring(0, split).equals(owner)) names.add(hit.substring(split + 1));
        }
        return names;
    }

    /** Search results ask a separate copyright service; it answers (Aweme, page) -> muted. */
    private static int hookSearch(ClassLoader loader, Class<?> aweme) {
        Class<?> service = Hooks.findClass(SEARCH_MUTE, loader);
        if (service == null) return 0;
        int count = 0;
        for (Method method : service.getDeclaredMethods()) {
            Class<?>[] p = method.getParameterTypes();
            if (method.getReturnType() != boolean.class || p.length != 2 || p[0] != aweme || p[1] != String.class
                    || Modifier.isAbstract(method.getModifiers())) continue;
            try {
                Hooks.hook(method, new Hooks.Hook() {
                    @Override protected void before(Hooks.Call param) {
                        if (Prefs.on(Prefs.UNMUTE)) param.setResult(false);
                    }
                });
                count++;
            } catch (Throwable ignored) { }
        }
        return count;
    }
}
