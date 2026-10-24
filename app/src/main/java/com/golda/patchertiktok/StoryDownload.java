package com.golda.patchertiktok;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Saves stories and profile highlights. Holding a story pauses it through the story cell's
 * long-press monitor; when the finger stays down a moment longer, the download sheet opens for the
 * story under it. The monitor keeps the bound cell model, which holds that very story, so a swipe
 * or a preloaded neighbour cannot change what is saved.
 */
final class StoryDownload {
    private static final String MONITOR = "com.ss.android.ugc.aweme.story.feed.collection.component.LongPressMonitorComponent";
    private static final String ITEM = "com.ss.android.ugc.aweme.feed.model.VideoItemParams";
    /** How long the finger stays after the story paused before the sheet opens. */
    private static final long EXTRA_HOLD_MS = 250L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static Class<?> itemType;
    private static volatile boolean open;

    private StoryDownload() { }

    static void install(ClassLoader loader) {
        Class<?> monitor = Hooks.findClass(MONITOR, loader);
        itemType = Hooks.findClass(ITEM, loader);
        if (monitor == null || itemType == null) {
            RuntimeLog.log("story download unavailable: long-press monitor not found");
            return;
        }
        Touch.install();
        int hooks = 0;
        for (Method method : monitor.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if (method.getParameterTypes().length != 0 || method.getReturnType() != void.class
                    || Modifier.isStatic(modifiers) || !Modifier.isPublic(modifiers) || method.getName().startsWith("on")) continue;
            // Pause and resume are the monitor's plain void methods; the pause is the one that runs
            // while the finger is still on the story.
            Hooks.hook(method, new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    if (!Prefs.on(Prefs.STORY_DOWNLOAD) || open || !Touch.pressing()) return;
                    Object component = param.thisObject;
                    MAIN.postDelayed(() -> held(component), EXTRA_HOLD_MS);
                }
            });
            hooks++;
        }
        RuntimeLog.log("story download: hooks=" + hooks);
    }

    private static void held(Object component) {
        if (open || !Touch.pressing()) return;
        Object story = story(component);
        Activity activity = Touch.activity();
        if (story == null || activity == null || activity.isFinishing()) return;
        open = DownloadsPlus.offer(activity, story, () -> open = false);
    }

    /** The story the monitor's cell is bound to: cellProxy → VideoItemParams → Aweme. */
    private static Object story(Object component) {
        Object proxy = Reflect.get(component, "cellProxy");
        if (proxy == null) return null;
        for (Class<?> type = proxy.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(proxy);
                    if (itemType.isInstance(value)) return Reflect.call(value, "getAweme");
                } catch (Throwable ignored) {
                    // Next field.
                }
            }
        }
        return null;
    }
}
