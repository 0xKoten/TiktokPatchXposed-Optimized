package com.golda.patchertiktok;

import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scrolling back to a video starts it where you left it instead of from the beginning. While a
 * feed video plays, its position is noted once a second; when the feed renders the first frame of
 * a video seen earlier in this session, the player seeks to that point.
 */
final class ResumePosition {
    private static final String PANEL = "com.ss.android.ugc.aweme.feed.panel.BaseListFragmentPanel";
    private static final String ABILITY = "com.ss.android.ugc.feed.platform.panel.player.IPlayerComponentAbility";
    /** Too close to either end, starting over is what the user expects. */
    private static final long MARGIN_MS = 3_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Long> POSITIONS = Collections.synchronizedMap(
            new LinkedHashMap<String, Long>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > 100;
                }
            });

    private static Method currentAweme;
    private static Method playerAbility;
    private static WeakReference<Object> panel = new WeakReference<>(null);
    private static String playing;

    private ResumePosition() { }

    static void install(ClassLoader loader) {
        Class<?> panelType = Hooks.findClass(PANEL, loader);
        if (panelType == null) {
            RuntimeLog.log("resume position unavailable: feed panel not found");
            return;
        }
        currentAweme = RenderedAdSkip.currentAweme(panelType);
        for (Method method : panelType.getDeclaredMethods()) {
            if (method.getParameterTypes().length == 0 && ABILITY.equals(method.getReturnType().getName())) {
                method.setAccessible(true);
                playerAbility = method;
                break;
            }
        }
        if (currentAweme == null || playerAbility == null) {
            RuntimeLog.log("resume position unavailable: player not found");
            return;
        }
        currentAweme.setAccessible(true);
        int hooks = 0;
        for (Method method : panelType.getDeclaredMethods()) {
            if (!"onRenderFirstFrame".equals(method.getName()) || method.getReturnType() != void.class) continue;
            Hooks.hook(method, new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    if (Prefs.on(Prefs.RESUME_POSITION)) started(param.thisObject);
                }
            });
            hooks++;
        }
        RuntimeLog.log("resume position: hooks=" + hooks);
    }

    private static void started(Object feedPanel) {
        Object aweme = invoke(currentAweme, feedPanel);
        String id = aweme == null ? null : string(Reflect.get(aweme, "aid"));
        if (id == null || id.equals(playing) && panel.get() == feedPanel) return;
        panel = new WeakReference<>(feedPanel);
        playing = id;
        Long saved = POSITIONS.get(id);
        long duration = duration(aweme);
        if (saved != null && duration > 0 && saved > MARGIN_MS && saved < duration - MARGIN_MS) {
            Object manager = Reflect.call(feedPanel, "getPlayerManager");
            Method seek = seek(manager);
            if (seek != null) {
                try {
                    seek.invoke(manager, saved * 100f / duration);
                } catch (Throwable ignored) {
                    // The video simply starts over.
                }
            }
        }
        MAIN.removeCallbacks(SAMPLE);
        MAIN.postDelayed(SAMPLE, 1_000L);
    }

    /** Notes where the current video is, as long as it is still the one playing. */
    private static final Runnable SAMPLE = new Runnable() {
        @Override public void run() {
            Object feedPanel = panel.get();
            String id = playing;
            if (feedPanel == null || id == null || !Prefs.on(Prefs.RESUME_POSITION)) return;
            Object aweme = invoke(currentAweme, feedPanel);
            if (aweme == null || !id.equals(string(Reflect.get(aweme, "aid")))) return;
            Object position = Reflect.call(invoke(playerAbility, feedPanel), "getCurrentPosition");
            if (position instanceof Number) POSITIONS.put(id, ((Number) position).longValue());
            MAIN.postDelayed(this, 1_000L);
        }
    };

    static long duration(Object aweme) {
        Object video = Reflect.call(aweme, "getVideo");
        if (video == null) video = Reflect.get(aweme, "video");
        Object value = Reflect.call(video, "getDuration");
        if (!(value instanceof Number) || ((Number) value).longValue() <= 0) value = Reflect.call(video, "getVideoLength");
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static Method seek(Object manager) {
        if (manager == null) return null;
        try {
            Method method = manager.getClass().getMethod("seek", float.class);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException missing) {
            return null;
        }
    }

    private static Object invoke(Method method, Object target) {
        try {
            return method.invoke(target);
        } catch (Throwable error) {
            return null;
        }
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
