package com.golda.patchertiktok;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.Window;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the screen at the chosen refresh rate while TikTok is in front. TikTok asks its window for
 * the frame rate of the video that is playing, so a 30 fps clip pulls a 120 Hz screen down for
 * everything, scrolling included. With a rate chosen, every window asks for that display mode
 * instead and video surfaces stop voting for their own rate. Only modes the screen lists at its
 * current resolution are used, so nothing is forced that the panel cannot do. A new choice applies
 * when TikTok's screen comes back to the front; 0 leaves TikTok's own requests alone.
 */
final class RefreshRate {
    /** Set on a window whose attributes carry our rate, so turning it off can clear them. */
    private static final String APPLIED = "tpx_refresh_rate";
    /** Display modes below this are LTPO idle steps, not something to hold the screen at. */
    private static final float LOWEST = 48f;

    private RefreshRate() { }

    static void install() {
        hookSetAttributes(Window.class);
        Hooks.findAndHook(Activity.class, "onResume", new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                Window window = ((Activity) param.thisObject).getWindow();
                if (window == null) return;
                // Some ROMs give the window a class with its own setAttributes; hook that one too.
                hookSetAttributes(window.getClass());
                if (chosen() <= 0 && Hooks.getExtra(window, APPLIED) == null) return;
                WindowManager.LayoutParams params = window.getAttributes();
                adjust(window, params);
                window.setAttributes(params);
            }
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // The player asks the video surface for the clip's frame rate; zero means no preference.
            Hooks.hookAll(Surface.class, "setFrameRate", new Hooks.Hook() {
                @Override protected void before(Hooks.Call param) {
                    if (chosen() > 0 && param.args.length > 0 && param.args[0] instanceof Float) param.args[0] = 0f;
                }
            });
            Hooks.hookAll(SurfaceControl.Transaction.class, "setFrameRate", new Hooks.Hook() {
                @Override protected void before(Hooks.Call param) {
                    if (chosen() > 0 && param.args.length > 1 && param.args[1] instanceof Float) param.args[1] = 0f;
                }
            });
        }
    }

    /** Declaring classes whose setAttributes is already hooked. */
    private static final java.util.Set<Class<?>> HOOKED = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private static void hookSetAttributes(Class<?> type) {
        java.lang.reflect.Method method;
        try {
            method = type.getMethod("setAttributes", WindowManager.LayoutParams.class);
        } catch (NoSuchMethodException missing) {
            return;
        }
        if (!HOOKED.add(method.getDeclaringClass())) return;
        Hooks.hook(method, new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                if (param.args[0] instanceof WindowManager.LayoutParams) {
                    adjust((Window) param.thisObject, (WindowManager.LayoutParams) param.args[0]);
                }
            }
        });
    }

    private static long chosen() {
        return Prefs.number(Prefs.REFRESH_RATE);
    }

    private static void adjust(Window window, WindowManager.LayoutParams params) {
        long rate = chosen();
        if (rate <= 0) {
            // TikTok's own values are left alone unless ours are still in there.
            if (Hooks.getExtra(window, APPLIED) == null) return;
            Hooks.setExtra(window, APPLIED, null);
            params.preferredDisplayModeId = 0;
            params.preferredRefreshRate = 0;
            range(params, 0);
            return;
        }
        Display.Mode mode = mode(window.getContext(), rate);
        if (mode == null) return;
        float hz = mode.getRefreshRate();
        params.preferredDisplayModeId = mode.getModeId();
        params.preferredRefreshRate = hz;
        // Without a floor, LTPO screens still drop to the video's rate inside the chosen mode.
        range(params, hz);
        Hooks.setExtra(window, APPLIED, Boolean.TRUE);
    }

    /** The min/max rate fields are hidden from the SDK, but public in the platform since Android 12. */
    private static void range(WindowManager.LayoutParams params, float hz) {
        for (String name : new String[]{"preferredMinDisplayRefreshRate", "preferredMaxDisplayRefreshRate"}) {
            try {
                WindowManager.LayoutParams.class.getField(name).setFloat(params, hz);
            } catch (ReflectiveOperationException | RuntimeException missing) {
                // Older Android: the display mode alone has to do.
            }
        }
    }

    /** Refresh rates the screen offers at its current resolution, fastest first, in whole Hz. */
    static List<Integer> rates(Context context) {
        List<Integer> rates = new ArrayList<>();
        for (Display.Mode mode : modes(context)) {
            int hz = Math.round(mode.getRefreshRate());
            if (!rates.contains(hz)) rates.add(hz);
        }
        rates.sort((a, b) -> b - a);
        return rates;
    }

    private static Display.Mode mode(Context context, long rate) {
        Display.Mode best = null;
        for (Display.Mode mode : modes(context)) {
            float distance = Math.abs(mode.getRefreshRate() - rate);
            if (distance < 1.5f && (best == null || distance < Math.abs(best.getRefreshRate() - rate))) best = mode;
        }
        return best;
    }

    /** TikTok sets its window's attributes on every video; the screen's modes are asked a few seconds apart at most. */
    private static volatile List<Display.Mode> cached;
    private static volatile long cachedAt;

    private static List<Display.Mode> modes(Context context) {
        long now = android.os.SystemClock.elapsedRealtime();
        List<Display.Mode> recent = cached;
        if (recent != null && now - cachedAt < 5_000L) return recent;
        List<Display.Mode> modes = read(context);
        if (!modes.isEmpty()) {
            cached = modes;
            cachedAt = now;
        }
        return modes;
    }

    private static List<Display.Mode> read(Context context) {
        List<Display.Mode> modes = new ArrayList<>();
        try {
            DisplayManager displays = context == null ? null : context.getSystemService(DisplayManager.class);
            Display display = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) return modes;
            Display.Mode current = display.getMode();
            for (Display.Mode mode : display.getSupportedModes()) {
                if (mode.getPhysicalWidth() == current.getPhysicalWidth()
                        && mode.getPhysicalHeight() == current.getPhysicalHeight()
                        && mode.getRefreshRate() >= LOWEST) modes.add(mode);
            }
        } catch (RuntimeException unavailable) {
            // No display information: nothing is forced.
        }
        return modes;
    }
}
