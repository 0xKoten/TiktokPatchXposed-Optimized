package com.golda.patchertiktok;

import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Modifier;

/**
 * Keeps TikTok's "Clear display" on while scrolling. The feed's clear-mode panel switches the mode
 * through one method taking (Aweme, boolean); the user's choice reaches it from inside the panel's
 * clear-mode event, while TikTok's reset on every new video calls it directly with false. As long as
 * the user left clear display on, the user's own event is replayed after such a reset.
 */
final class CleanScreen {
    private static final String PANEL = "com.ss.android.ugc.feed.platform.panel.clearmode.ClearModePanelComponent";
    private static final String AWEME = "com.ss.android.ugc.aweme.feed.model.Aweme";
    private static final ThreadLocal<int[]> IN_EVENT = ThreadLocal.withInitial(() -> new int[1]);
    private static final ThreadLocal<Object> LAST = new ThreadLocal<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean chosen;
    private static volatile Object enterEvent;

    private CleanScreen() { }

    static void install(ClassLoader loader) {
        Class<?> panel = Hooks.findClass(PANEL, loader);
        Class<?> aweme = Hooks.findClass(AWEME, loader);
        if (panel == null || aweme == null) {
            RuntimeLog.log("remember clear display unavailable: panel not found");
            return;
        }
        Method event = null;
        List<Method> changes = new ArrayList<>();
        for (Method method : panel.getDeclaredMethods()) {
            if (Modifier.isAbstract(method.getModifiers()) || method.isSynthetic() || method.getReturnType() != void.class) continue;
            Class<?>[] p = method.getParameterTypes();
            if (method.getName().equals("onClearModeEvent") && p.length == 1) event = method;
            else if (p.length == 2 && p[1] == boolean.class && p[0] == aweme) changes.add(method);
        }
        if (event == null || changes.size() != 1) {
            RuntimeLog.log("remember clear display unavailable: panel methods not recognised");
            return;
        }
        Method dispatch = event;
        Hooks.hook(event, new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                IN_EVENT.get()[0]++;
                LAST.set(param.args[0]);
            }
            @Override protected void after(Hooks.Call param) {
                IN_EVENT.get()[0]--;
                LAST.remove();
            }
        });
        Hooks.hook(changes.get(0), new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                boolean on = Boolean.TRUE.equals(param.args[1]);
                if (IN_EVENT.get()[0] > 0) {
                    // The user's choice, made through the panel's clear-mode event.
                    chosen = on;
                    if (on) enterEvent = LAST.get();
                } else if (!on && chosen && Prefs.on(Prefs.REMEMBER_CLEAN) && enterEvent != null) {
                    // TikTok's reset for the next video: replay the user's own event once it has settled.
                    Object owner = param.thisObject;
                    Object replay = enterEvent;
                    MAIN.postDelayed(() -> replay(dispatch, owner, replay), 200);
                }
            }
        });
        RuntimeLog.log("remember clear display installed");
    }

    private static void replay(Method dispatch, Object panel, Object event) {
        if (!chosen || !Prefs.on(Prefs.REMEMBER_CLEAN)) return;
        try {
            dispatch.setAccessible(true);
            dispatch.invoke(panel, event);
        } catch (Throwable error) {
            RuntimeLog.log("remember clear display failed: " + error.getClass().getSimpleName());
        }
    }
}
