package com.golda.patchertiktok;

import android.app.Activity;
import android.app.Dialog;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;

import java.lang.ref.WeakReference;

/**
 * Where the finger is: whether it is still down, where it went down and in which window (the
 * activity, or a dialog such as the comment sheet). Long-press menus are told apart from button
 * taps by it. One hook serves every feature that asks.
 */
final class Touch {
    private static volatile boolean installed;
    private static volatile boolean pressing;
    private static volatile float downX;
    private static volatile float downY;
    private static volatile long downAt;
    private static volatile WeakReference<View> window = new WeakReference<>(null);
    private static volatile WeakReference<Activity> activity = new WeakReference<>(null);

    private Touch() { }

    static synchronized void install() {
        if (installed) return;
        installed = true;
        Hooks.Hook touch = new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                MotionEvent event = (MotionEvent) param.args[0];
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    Window owner = param.thisObject instanceof Activity
                            ? ((Activity) param.thisObject).getWindow() : ((Dialog) param.thisObject).getWindow();
                    if (owner == null) return;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    downAt = SystemClock.uptimeMillis();
                    window = new WeakReference<>(owner.getDecorView());
                    Activity host = param.thisObject instanceof Activity ? (Activity) param.thisObject
                            : ((Dialog) param.thisObject).getOwnerActivity();
                    activity = new WeakReference<>(host);
                    pressing = true;
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    pressing = false;
                }
            }
        };
        Hooks.findAndHook(Activity.class, "dispatchTouchEvent", MotionEvent.class, touch);
        Hooks.findAndHook(Dialog.class, "dispatchTouchEvent", MotionEvent.class, touch);
    }

    /** True while the finger that went down within the last {@code withinMs} is still down. */
    static boolean holding(long withinMs) {
        return pressing && SystemClock.uptimeMillis() - downAt <= withinMs;
    }

    static boolean pressing() { return pressing; }

    static float x() { return downX; }

    static float y() { return downY; }

    static View window() { return window.get(); }

    /** The activity the finger went down in, or the one that owns the dialog it went down in. */
    static Activity activity() { return activity.get(); }
}
