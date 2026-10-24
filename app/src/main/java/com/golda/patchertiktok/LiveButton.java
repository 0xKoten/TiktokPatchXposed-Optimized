package com.golda.patchertiktok;

import android.view.View;
import android.widget.ImageView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Manages the LIVE entry in the top-left corner of the home feed toolbar.
 * By default (HIDE_LIVE_ICON is false), leaves TikTok's native LIVE icon untouched and fully working.
 * When HIDE_LIVE_ICON is enabled by the user, hides the LIVE entry and icon views.
 */
final class LiveButton {
    private static final String GENERATOR = "com.bytedance.tiktok.homepage.mainfragment.toolbar.LiveIconGenerator";

    private LiveButton() { }

    static void install(ClassLoader loader) {
        Class<?> generator = Hooks.findClass(GENERATOR, loader);
        if (generator == null) {
            RuntimeLog.log("LIVE button: generator not found");
            return;
        }
        for (Method method : generator.getDeclaredMethods()) {
            Class<?>[] p = method.getParameterTypes();
            String mname = method.getName();
            if ("enabled".equals(mname) && method.getReturnType() == boolean.class && p.length == 0) {
                Hooks.hook(method, new Hooks.Hook() {
                    @Override protected void after(Hooks.Call param) {
                        if (Prefs.on(Prefs.HIDE_LIVE_ICON)) {
                            param.setResult(false);
                        }
                    }
                });
            } else if (("LIZLLL".equals(mname) || "LJIIJJI".equals(mname)) && method.getReturnType() == boolean.class && p.length == 0) {
                Hooks.hook(method, new Hooks.Hook() {
                    @Override protected void after(Hooks.Call param) {
                        if (Prefs.on(Prefs.HIDE_LIVE_ICON)) {
                            param.setResult(false);
                        }
                    }
                });
            } else if (View.class.isAssignableFrom(method.getReturnType()) && p.length == 1
                    && "android.content.Context".equals(p[0].getName())) {
                Hooks.hook(method, new Hooks.Hook() {
                    @Override protected void after(Hooks.Call param) {
                        if (Prefs.on(Prefs.HIDE_LIVE_ICON)) {
                            hide(param.getResult());
                            hideIcons(param.thisObject);
                        }
                    }
                });
            } else if (method.getReturnType() == void.class && p.length == 1 && p[0] == boolean.class) {
                Hooks.hook(method, new Hooks.Hook() {
                    @Override protected void before(Hooks.Call param) {
                        if (Prefs.on(Prefs.HIDE_LIVE_ICON)) {
                            param.args[0] = false;
                        }
                    }

                    @Override protected void after(Hooks.Call param) {
                        if (Prefs.on(Prefs.HIDE_LIVE_ICON)) {
                            hideIcons(param.thisObject);
                        }
                    }
                });
            }
        }
        Hooks.Hook stateUpdater = new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                if (Prefs.on(Prefs.HIDE_LIVE_ICON)) {
                    hideIcons(param.thisObject);
                }
            }
        };
        Hooks.hookAll(generator, "onCreate", stateUpdater);
        Hooks.hookAll(generator, "onResume", stateUpdater);
        Hooks.hookAll(generator, "onLiveIconEntranceEnable", stateUpdater);
    }

    private static void hideIcons(Object generator) {
        if (generator == null) return;
        for (Class<?> type = generator.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!ImageView.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    hide(field.get(generator));
                } catch (Throwable ignored) { }
            }
        }
    }

    private static void hide(Object value) {
        if (!(value instanceof View)) return;
        View view = (View) value;
        view.setVisibility(View.GONE);
        view.setClickable(false);
        view.setLongClickable(false);
    }
}
