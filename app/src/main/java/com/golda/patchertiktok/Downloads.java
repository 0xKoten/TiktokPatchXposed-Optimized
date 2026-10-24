package com.golda.patchertiktok;

import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;


/** Download permissions, watermark-free saving and screenshots. */
final class Downloads {
    private static final String ACL = "com.ss.android.ugc.aweme.feed.model.ACLCommonShare";

    private Downloads() { }

    static void install(ClassLoader loader) {
        Class<?> acl = Hooks.findClass(ACL, loader);
        if (acl != null) {
            // code 0 = allowed, show type 2 = show the download button.
            override(acl, "getCode", Prefs.DOWNLOAD_ANY, 0);
            override(acl, "getShowType", Prefs.DOWNLOAD_ANY, 2);
            override(acl, "getTranscode", Prefs.NO_WATERMARK, 1);
        }
        installScreenshots();
    }

    private static void override(Class<?> type, String method, String key, int value) {
        Hooks.hookAll(type, method, new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                if (Prefs.on(key)) param.setResult(value);
            }
        });
    }

    private static void installScreenshots() {
        Hooks.findAndHook(Window.class, "setFlags", int.class, int.class, new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                if (!Prefs.on(Prefs.SCREENSHOTS)) return;
                param.args[0] = (Integer) param.args[0] & ~WindowManager.LayoutParams.FLAG_SECURE;
            }
        });
        Hooks.findAndHook(SurfaceView.class, "setSecure", boolean.class, new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                if (Prefs.on(Prefs.SCREENSHOTS)) param.args[0] = false;
            }
        });
        // Dialogs, popups and pages that pass whole window parameters instead of single flags.
        Hooks.Hook parameters = new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                if (!Prefs.on(Prefs.SCREENSHOTS)) return;
                for (Object arg : param.args) {
                    if (arg instanceof WindowManager.LayoutParams) {
                        ((WindowManager.LayoutParams) arg).flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
                    }
                }
            }
        };
        Hooks.hookAll(Window.class, "setAttributes", parameters);
        Class<?> global = Hooks.findClass("android.view.WindowManagerGlobal", Downloads.class.getClassLoader());
        if (global != null) {
            Hooks.hookAll(global, "addView", parameters);
            Hooks.hookAll(global, "updateViewLayout", parameters);
        }
        // Screens that blank themselves in the recent apps.
        Hooks.hookAll(android.app.Activity.class, "setRecentsScreenshotEnabled", new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                if (Prefs.on(Prefs.SCREENSHOTS)) param.args[0] = true;
            }
        });
    }
}
