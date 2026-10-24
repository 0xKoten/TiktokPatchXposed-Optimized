package com.golda.patchertiktok;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.golda.patchertiktok.I18n.S;

/**
 * The module's settings screen. TikTok starts its own settings activity with
 * {@link #EXTRA}; {@link SettingsEntry} swaps in this class, so the screen lives in
 * TikTok's task and theme without a manifest entry of its own.
 */
public final class ModSettingsActivity extends Activity {
    static final String EXTRA = "com.golda.patchertiktok.SETTINGS";
    static final String EXTRA_DARK = "com.golda.patchertiktok.DARK";
    static final String SOURCE_URL = "https://github.com/hik0w/TiktokPatchXposed";

    private final ArrayDeque<View> pages = new ArrayDeque<>();
    private final Runnable prefsListener = this::onPrefsChanged;
    private Palette palette;
    private Locale locale;
    private FrameLayout stack;
    private View restartBar;
    private Object backCallback;
    private FrameLayout root;
    /** The open bottom sheet's layer, or null. */
    private FrameLayout sheet;
    private Object sheetBack;
    /** Rebuilds the font sheet's rows after a file is picked. */
    private Runnable fontChanged;
    private static final int PICK_FONT = 41;
    private static final int SAVE_BACKUP = 42;
    private static final int OPEN_BACKUP = 43;
    private int topInset;
    private int bottomInset;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        try {
            Prefs.load(getApplicationContext());
            locale = Locale.getDefault();
            try {
                android.os.LocaleList locales = getResources().getConfiguration().getLocales();
                if (locales != null && !locales.isEmpty()) {
                    locale = locales.get(0);
                }
            } catch (Throwable ignored) { }
            if (locale == null) locale = Locale.ENGLISH;

            // Debug builds only: README screenshots in another language without changing TikTok's.
            if (BuildConfig.DEBUG && getIntent() != null && getIntent().hasExtra(EXTRA + ".LANG")) {
                locale = Locale.forLanguageTag(getIntent().getStringExtra(EXTRA + ".LANG"));
            }
            I18n.use(locale);

            boolean dark = true;
            try {
                dark = (getIntent() != null && getIntent().hasExtra(EXTRA_DARK))
                        ? getIntent().getBooleanExtra(EXTRA_DARK, true)
                        : (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                        == Configuration.UI_MODE_NIGHT_YES;
            } catch (Throwable ignored) { }

            int accent = Prefs.on(Prefs.ACCENT) ? 0xFF000000 | (int) Prefs.number(Prefs.ACCENT_COLOR) : 0;
            palette = Palette.settings(dark, dark && Prefs.on(Prefs.AMOLED), accent);
            setupWindow();

            root = new FrameLayout(this);
            try {
                root.setLayoutDirection("ar".equals(locale.getLanguage()) ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
                root.setTextDirection("ar".equals(locale.getLanguage()) ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
            } catch (Throwable ignored) { }
            root.setBackgroundColor(palette.background);
            stack = new FrameLayout(this);
            root.addView(stack, new FrameLayout.LayoutParams(-1, -1));
            // Each page's toolbar paints the status bar area itself, so the hero glow shows under it.
            View statusScrim = new View(this);
            root.addView(statusScrim, new FrameLayout.LayoutParams(-1, 0, Gravity.TOP));
            restartBar = restartBar();
            FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            root.addView(restartBar, barParams);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                try {
                    if (Build.VERSION.SDK_INT >= 30) {
                        android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                                | WindowInsets.Type.displayCutout());
                        topInset = bars.top;
                        bottomInset = Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
                    } else {
                        topInset = insets.getSystemWindowInsetTop();
                        bottomInset = insets.getSystemWindowInsetBottom();
                    }
                    for (int index = 0; index < stack.getChildCount(); index++) applyInsets(stack.getChildAt(index));
                    statusScrim.getLayoutParams().height = topInset;
                    statusScrim.requestLayout();
                    restartBar.setPadding(dp(12), dp(8), dp(12), dp(12) + bottomInset);
                } catch (Throwable ignored) { }
                return insets;
            });
            setContentView(root);
            push(mainPage(), false);
            updateRestartBar(false);
        } catch (Throwable t) {
            RuntimeLog.log("ModSettingsActivity crash: " + t);
            ScrollView sv = new ScrollView(this);
            TextView tv = new TextView(this);
            tv.setText("啟動錯誤 (Error):\n\n" + android.util.Log.getStackTraceString(t));
            tv.setTextColor(0xFFFF4444);
            tv.setTextSize(13);
            tv.setPadding(40, 80, 40, 40);
            sv.addView(tv);
            setContentView(sv);
        }
    }

    /**
     * TikTok serves part of its strings through its own Resources subclass, and its code asks the top
     * activity for them, e.g. for in-app message banners. With the plain activity resources such a
     * banner crashed TikTok while this screen was open.
     */
    @Override
    public Resources getResources() {
        try {
            Context app = getApplicationContext();
            if (app != null && !"com.golda.patchertiktok".equals(app.getPackageName())) {
                return app.getResources();
            }
        } catch (RuntimeException ignored) {
            // Not attached yet.
        }
        return super.getResources();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Prefs.listen(prefsListener);
    }

    @Override
    protected void onStop() {
        Prefs.unlisten(prefsListener);
        super.onStop();
    }

    /** Used when TikTok has not opted into predictive back; otherwise {@link #syncBackCallback()} applies. */
    @Override
    @SuppressWarnings("deprecation")
    @android.annotation.SuppressLint("GestureBackNavigation")
    public void onBackPressed() {
        if (sheet != null) closeSheet();
        else if (pages.size() > 1) pop();
        else super.onBackPressed();
    }

    // ---- window ----------------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private void setupWindow() {
        try {
            Window window = getWindow();
            if (window == null) return;
            window.setBackgroundDrawable(new ColorDrawable(palette.background));
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(0);
            window.setNavigationBarColor(0);
            if (Build.VERSION.SDK_INT >= 29) {
                window.setStatusBarContrastEnforced(false);
                window.setNavigationBarContrastEnforced(false);
            }
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false);
                int light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                android.view.WindowInsetsController controller = window.getInsetsController();
                if (controller != null) controller.setSystemBarsAppearance(palette.dark ? 0 : light, light);
            } else {
                int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
                if (!palette.dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                window.getDecorView().setSystemUiVisibility(flags);
            }
        } catch (Throwable ignored) { }
    }

    // ---- navigation ------------------------------------------------------------------------

    /**
     * Opens a page with a shared-axis transition: the new page comes in a little from the right
     * while it fades in, and the old one drifts a little to the left as it fades out. Short and
     * quiet, because settings pages are opened many times a session.
     */
    private void push(View page, boolean animate) {
        View previous = pages.peek();
        pages.push(page);
        stack.addView(page, new FrameLayout.LayoutParams(-1, -1));
        applyInsets(page);
        if (previous == null) {
            reveal(page);
        } else if (animate && motion()) {
            axis(page, previous, true, 0f, null);
        } else {
            previous.setVisibility(View.INVISIBLE);
        }
        syncBackCallback();
    }

    /** The first page's rows rise in one after another, once per opening of the settings. */
    private void reveal(View page) {
        if (!motion()) return;
        Object holder = page.getTag(R_ID_CONTENT);
        if (!(holder instanceof LinearLayout)) return;
        LinearLayout content = (LinearLayout) holder;
        int shown = 0;
        for (int index = 0; index < content.getChildCount() && shown < 6; index++) {
            View child = content.getChildAt(index);
            if (child.getVisibility() != View.VISIBLE) continue;
            child.setAlpha(0f);
            child.setTranslationY(dp(6));
            child.animate().alpha(1f).translationY(0).setStartDelay(20L * shown++)
                    .setDuration(200).setInterpolator(EASE).start();
        }
    }

    private void pop() {
        if (pages.size() < 2) return;
        View page = pages.pop();
        View previous = pages.peek();
        hideKeyboard(page);
        if (previous.getTag() instanceof Runnable) ((Runnable) previous.getTag()).run();
        previous.setVisibility(View.VISIBLE);
        Runnable done = () -> {
            stack.removeView(page);
            restore(previous);
        };
        if (motion()) {
            // Starts from wherever a predictive back gesture has already taken the pages.
            axis(page, previous, false, Math.abs(page.getTranslationX()) / (float) dp(SHIFT_DP), done);
        } else {
            done.run();
        }
        syncBackCallback();
    }

    /** How far, in dp, a page travels in the shared-axis transition. */
    private static final int SHIFT_DP = 32;

    /** Whether the system lets apps animate; with animations off pages simply appear. */
    private static boolean motion() {
        return ValueAnimator.areAnimatorsEnabled();
    }

    /**
     * One shared-axis step between {@code page} (on top) and {@code previous}. At {@code t = 1}
     * the top page is in place and the previous one gone; at 0 the reverse. Entering takes
     * 240 ms, leaving 180 ms: the system answering is faster than the user arriving.
     */
    private void axis(View page, View previous, boolean in, float startOffset, Runnable end) {
        float shift = dp(SHIFT_DP);
        float from = in ? 0f : 1f - Math.min(1f, startOffset);
        ValueAnimator animator = ValueAnimator.ofFloat(from, in ? 1f : 0f);
        animator.setDuration(in ? 240 : 180);
        animator.setInterpolator(EASE);
        animator.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            page.setTranslationX(shift * (1f - t));
            page.setAlpha(clamp(t * 1.4f - 0.2f));
            previous.setTranslationX(-shift * 0.5f * t);
            previous.setAlpha(1f - clamp(t * 1.6f));
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (in) {
                    restore(page);
                    previous.setVisibility(View.INVISIBLE);
                }
                if (end != null) end.run();
            }
        });
        animator.start();
    }

    private void restore(View page) {
        page.animate().cancel();
        page.setScaleX(1f);
        page.setScaleY(1f);
        page.setAlpha(1f);
        page.setTranslationX(0);
        page.setTranslationY(0);
        page.setVisibility(View.VISIBLE);
    }

    private static float lerp(float from, float to, float t) { return from + (to - from) * t; }

    private static float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }

    static int blend(int from, int to, float amount) {
        int a = (int) (((from >>> 24) & 255) + (((to >>> 24) & 255) - ((from >>> 24) & 255)) * amount);
        int r = (int) (((from >> 16) & 255) + (((to >> 16) & 255) - ((from >> 16) & 255)) * amount);
        int g = (int) (((from >> 8) & 255) + (((to >> 8) & 255) - ((from >> 8) & 255)) * amount);
        int b = (int) ((from & 255) + ((to & 255) - (from & 255)) * amount);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * The page on top always sits exactly in place once the screen is back in front: an
     * interrupted slide (a fast back, the app going to the background) would otherwise leave it
     * shifted, cutting off its left edge.
     */
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        View top = pages.peek();
        if (hasFocus && top != null && top.getAnimation() == null) {
            top.animate().cancel();
            top.setTranslationX(0);
            top.setVisibility(View.VISIBLE);
        }
    }

    /**
     * API 33+ dispatches back through callbacks when the host app opts into predictive back. On
     * 34+ the page follows the gesture along the same axis a pop uses, and a release either
     * finishes the pop from there or settles back.
     */
    private void syncBackCallback() {
        try {
            if (Build.VERSION.SDK_INT < 33) return;
            android.window.OnBackInvokedDispatcher dispatcher = getOnBackInvokedDispatcher();
            if (dispatcher == null) return;
            if (pages.size() > 1 && backCallback == null) {
                android.window.OnBackInvokedCallback callback = Build.VERSION.SDK_INT >= 34 ? predictiveBack() : this::pop;
                dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
                backCallback = callback;
            } else if (pages.size() <= 1 && backCallback != null) {
                dispatcher.unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) backCallback);
                backCallback = null;
            }
        } catch (Throwable ignored) { }
    }

    @android.annotation.TargetApi(34)
    private android.window.OnBackInvokedCallback predictiveBack() {
        return new android.window.OnBackAnimationCallback() {
            @Override public void onBackStarted(android.window.BackEvent event) {
                View previous = previousPage();
                if (previous != null) previous.setVisibility(View.VISIBLE);
            }

            @Override public void onBackProgressed(android.window.BackEvent event) {
                View page = pages.peek();
                View previous = previousPage();
                if (page == null || previous == null || !motion()) return;
                // The same shared axis as a pop, run by the finger: up to half of the way, so
                // a release still has a visible last step to finish.
                float t = 1f - 0.5f * event.getProgress();
                float shift = dp(SHIFT_DP);
                page.setTranslationX(shift * (1f - t));
                page.setAlpha(clamp(t * 1.4f - 0.2f));
                previous.setTranslationX(-shift * 0.5f * t);
                previous.setAlpha(1f - clamp(t * 1.6f));
            }

            @Override public void onBackInvoked() { pop(); }

            @Override public void onBackCancelled() {
                View page = pages.peek();
                View previous = previousPage();
                if (page != null) page.animate().translationX(0).alpha(1f).setDuration(180).setInterpolator(EASE).start();
                if (previous != null) {
                    previous.animate().translationX(-dp(SHIFT_DP) * 0.5f).alpha(0f).setDuration(180).setInterpolator(EASE)
                            .withEndAction(() -> previous.setVisibility(View.INVISIBLE)).start();
                }
            }
        };
    }

    private View previousPage() {
        if (pages.size() < 2) return null;
        java.util.Iterator<View> iterator = pages.iterator();
        iterator.next();
        return iterator.next();
    }

    private void applyInsets(View page) {
        Object holder = page.getTag(R_ID_INSETS);
        if (holder instanceof Insettable) ((Insettable) holder).apply(topInset, bottomInset);
    }

    private interface Insettable { void apply(int top, int bottom); }

    private static final int R_ID_INSETS = 0x7f0b0001;
    private static final int R_ID_CONTENT = 0x7f0b0002;
    private static final int R_ID_ORIGIN = 0x7f0b0003;
    private static final int R_ID_SHAPE = 0x7f0b0004;
    /** Strong ease-out: movement starts at once and settles softly. */
    private static final android.view.animation.Interpolator EASE =
            new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);
    /** The iOS-like drawer curve, cubic-bezier(0.32, 0.72, 0, 1): a page grows out of its tile. */
    private static final android.view.animation.Interpolator OPEN =
            new android.view.animation.PathInterpolator(0.32f, 0.72f, 0f, 1f);
    /** Even quicker start, for press feedback. */
    private static final android.view.animation.Interpolator PRESS =
            new android.view.animation.PathInterpolator(0.23f, 1f, 0.32f, 1f);
    private static final int[] AGE_DAYS = {7, 30, 90, 180, 365, 730};
    private static final S[] AGE_NAMES = {S.AGE_WEEK, S.AGE_MONTH, S.AGE_3_MONTHS, S.AGE_6_MONTHS, S.AGE_YEAR, S.AGE_2_YEARS};

    // ---- pages -----------------------------------------------------------------------------

    /** A group of settings with its own page; the main page lists the groups. */
    private enum Category {
        // Not TikTok's brand red: the accent hooks would repaint that to the user's accent.
        FEED(S.SECTION_FEED, Glyph.Kind.FEED, 0xFFF0535B),
        PLAYBACK(S.SECTION_PLAYBACK, Glyph.Kind.PLAY, 0xFF8B5CF6),
        HIDE_TRANSPARENCY(S.SECTION_HIDE_TRANSPARENCY, Glyph.Kind.EYE, 0xFF00C4B4),
        INTERFACE(S.SECTION_INTERFACE, Glyph.Kind.PALETTE, 0xFF14B8A6),
        SOCIAL(S.SECTION_SOCIAL, Glyph.Kind.COMMENT, 0xFFFF9F0A),
        SHARING(S.SECTION_SHARING, Glyph.Kind.DOWNLOAD, 0xFF2BB673),
        GHOST(S.SECTION_PRIVACY, Glyph.Kind.GHOST, 0xFF6B7280),
        REGION(S.SECTION_REGION, Glyph.Kind.GLOBE, 0xFF1FA2FF),
        STREAKS(S.SECTION_STREAKS, Glyph.Kind.FLAME, 0xFFFF6A2B),
        LAB(S.SECTION_LAB, Glyph.Kind.FLASK, 0xFF6B7280);

        final S title;
        final Glyph.Kind icon;
        final int color;

        Category(S title, Glyph.Kind icon, int color) {
            this.title = title;
            this.icon = icon;
            this.color = color;
        }
    }

    /** A plain switch; listed on its group's page and found by the search on the main page. */
    private static final class Setting {
        final Category category;
        /** The heading of the card this switch sits in on its page; null for a page with one card. */
        final S group;
        final S title;
        final S description;
        final String key;

        Setting(Category category, S group, S title, S description, String key) {
            this.category = category;
            this.group = group;
            this.title = title;
            this.description = description;
            this.key = key;
        }
    }

    private static final Setting[] SETTINGS = {
            new Setting(Category.FEED, S.GROUP_REMOVE, S.ADS, S.ADS_DESC, Prefs.ADS),
            new Setting(Category.FEED, S.GROUP_REMOVE, S.LIVE, S.LIVE_DESC, Prefs.LIVE),

            new Setting(Category.FEED, S.GROUP_REMOVE, S.ACQUAINTANCES, S.ACQUAINTANCES_DESC, Prefs.ACQUAINTANCES),
            new Setting(Category.FEED, S.GROUP_REMOVE, S.PROMO, S.PROMO_DESC, Prefs.PROMO),
            new Setting(Category.FEED, S.GROUP_FILTERS, S.AI_CONTENT, S.AI_CONTENT_DESC, Prefs.AI_CONTENT),
            new Setting(Category.FEED, S.GROUP_FILTERS, S.OLD_VIDEOS, S.OLD_VIDEOS_DESC, Prefs.OLD_VIDEOS),
            new Setting(Category.FEED, S.GROUP_FILTERS, S.CUSTOM_FILTER, S.CUSTOM_FILTER_DESC, Prefs.CUSTOM_FILTER),
            new Setting(Category.PLAYBACK, S.GROUP_PLAYBACK, S.AUTO_SCROLL, S.AUTO_SCROLL_DESC, Prefs.AUTO_SCROLL),
            new Setting(Category.PLAYBACK, S.GROUP_PLAYBACK, S.REMEMBER_SPEED, S.REMEMBER_SPEED_DESC, Prefs.REMEMBER_SPEED),
            new Setting(Category.PLAYBACK, S.GROUP_PLAYBACK, S.RESUME_POSITION, S.RESUME_POSITION_DESC, Prefs.RESUME_POSITION),
            new Setting(Category.PLAYBACK, S.GROUP_PLAYBACK, S.UNMUTE, S.UNMUTE_DESC, Prefs.UNMUTE),
            new Setting(Category.PLAYBACK, S.GROUP_PLAYBACK, S.SPEED_3X, S.SPEED_3X_DESC, Prefs.SPEED_3X),
            new Setting(Category.PLAYBACK, S.GROUP_SCREEN, S.REMEMBER_CLEAN, S.REMEMBER_CLEAN_DESC, Prefs.REMEMBER_CLEAN),
            new Setting(Category.PLAYBACK, S.GROUP_SCREEN, S.SEEKBAR, S.SEEKBAR_DESC, Prefs.SEEKBAR),
            new Setting(Category.HIDE_TRANSPARENCY, S.GROUP_SCREEN, S.REMOVE_BLACK_EDGES, S.REMOVE_BLACK_EDGES_DESC, Prefs.REMOVE_BLACK_EDGES),
            new Setting(Category.HIDE_TRANSPARENCY, S.GROUP_SCREEN, S.HIDE_LIVE_ICON, S.HIDE_LIVE_ICON_DESC, Prefs.HIDE_LIVE_ICON),
            new Setting(Category.HIDE_TRANSPARENCY, S.GROUP_SCREEN, S.HIDE_PLUS_BUTTON, S.HIDE_PLUS_BUTTON_DESC, Prefs.HIDE_PLUS_BUTTON),
            new Setting(Category.HIDE_TRANSPARENCY, S.GROUP_SCREEN, S.HIDE_BOTTOM_NAV, S.HIDE_BOTTOM_NAV_DESC, Prefs.HIDE_BOTTOM_NAV),
            new Setting(Category.HIDE_TRANSPARENCY, S.GROUP_SCREEN, S.HIDE_TOP_NAV, S.HIDE_TOP_NAV_DESC, Prefs.HIDE_TOP_NAV),
            new Setting(Category.HIDE_TRANSPARENCY, S.GROUP_SCREEN, S.HIDE_NAV_BAR, S.HIDE_NAV_BAR_DESC, Prefs.HIDE_NAV_BAR),
            new Setting(Category.HIDE_TRANSPARENCY, S.GROUP_SCREEN, S.HIDE_STATUS_BAR, S.HIDE_STATUS_BAR_DESC, Prefs.HIDE_STATUS_BAR),
            new Setting(Category.HIDE_TRANSPARENCY, S.HIDE_PARTS, S.HIDE_BUTTONS, S.HIDE_BUTTONS_DESC, Prefs.HIDE_BUTTONS),
            new Setting(Category.HIDE_TRANSPARENCY, S.HIDE_PARTS, S.HIDE_MUSIC, null, Prefs.HIDE_MUSIC),
            new Setting(Category.HIDE_TRANSPARENCY, S.HIDE_PARTS, S.HIDE_CAPTION, null, Prefs.HIDE_CAPTION),
            new Setting(Category.HIDE_TRANSPARENCY, S.HIDE_PARTS, S.HIDE_TAKO, S.HIDE_TAKO_DESC, Prefs.HIDE_TAKO),
            new Setting(Category.INTERFACE, S.GROUP_COLORS, S.ACCENT, S.ACCENT_DESC, Prefs.ACCENT),
            new Setting(Category.INTERFACE, S.GROUP_COLORS, S.AMOLED, S.AMOLED_DESC, Prefs.AMOLED),
            new Setting(Category.INTERFACE, S.GROUP_SCREEN, S.BURN_IN, S.BURN_IN_DESC, Prefs.BURN_IN),
            new Setting(Category.INTERFACE, S.GROUP_PROFILE, S.NEW_PROFILE, S.NEW_PROFILE_DESC, Prefs.NEW_PROFILE),
            new Setting(Category.INTERFACE, S.GROUP_PROFILE, S.HIDE_INBOX_STORIES, S.HIDE_INBOX_STORIES_DESC, Prefs.HIDE_INBOX_STORIES),
            new Setting(Category.INTERFACE, S.GROUP_PROFILE, S.MESSAGE_TIME, S.MESSAGE_TIME_DESC, Prefs.MESSAGE_TIME),
            new Setting(Category.INTERFACE, S.GROUP_PROFILE, S.READ_ALL, S.READ_ALL_DESC, Prefs.READ_ALL),
            new Setting(Category.SOCIAL, null, S.COPY_COMMENT, S.COPY_COMMENT_DESC, Prefs.COPY_COMMENT_TEXT),
            new Setting(Category.SOCIAL, null, S.AUTO_TRANSLATE, S.AUTO_TRANSLATE_DESC, Prefs.AUTO_TRANSLATE),
            new Setting(Category.SOCIAL, null, S.COMMENT_REPOSTS, S.COMMENT_REPOSTS_DESC, Prefs.COMMENT_REPOSTS),
            new Setting(Category.SOCIAL, null, S.SELF_REPOST, S.SELF_REPOST_DESC, Prefs.SELF_REPOST),
            new Setting(Category.SOCIAL, null, S.COMMENT_PHOTOS, S.COMMENT_PHOTOS_DESC, Prefs.COMMENT_PHOTOS),
            new Setting(Category.SOCIAL, null, S.AUTHOR_REGION, S.AUTHOR_REGION_DESC, Prefs.AUTHOR_REGION),
            new Setting(Category.SHARING, S.GROUP_MEDIA, S.NO_WATERMARK, null, Prefs.NO_WATERMARK),
            new Setting(Category.SHARING, S.GROUP_MEDIA, S.DOWNLOAD_ANY, S.DOWNLOAD_ANY_DESC, Prefs.DOWNLOAD_ANY),
            new Setting(Category.SHARING, S.GROUP_MEDIA, S.QUALITY_PICKER, S.QUALITY_PICKER_DESC, Prefs.QUALITY_PICKER),
            new Setting(Category.SHARING, S.GROUP_MEDIA, S.SAVE_ALL_PHOTOS, S.SAVE_ALL_PHOTOS_DESC, Prefs.SAVE_ALL_PHOTOS),
            new Setting(Category.SHARING, S.GROUP_MEDIA, S.COMMENT_MEDIA, S.COMMENT_MEDIA_DESC, Prefs.COMMENT_MEDIA),
            new Setting(Category.SHARING, S.GROUP_MEDIA, S.STORY_DOWNLOAD, S.STORY_DOWNLOAD_DESC, Prefs.STORY_DOWNLOAD),
            new Setting(Category.SHARING, S.GROUP_MEDIA, S.AVATAR_DOWNLOAD, S.AVATAR_DOWNLOAD_DESC, Prefs.AVATAR_DOWNLOAD),
            new Setting(Category.SHARING, S.GROUP_MEDIA, S.CHAT_STICKER, S.CHAT_STICKER_DESC, Prefs.CHAT_STICKER),
            new Setting(Category.SHARING, S.GROUP_MEDIA, S.HD_UPLOAD, S.HD_UPLOAD_DESC, Prefs.HD_UPLOAD),
            new Setting(Category.SHARING, S.GROUP_LINKS, S.CLEAN_LINKS, S.CLEAN_LINKS_DESC, Prefs.CLEAN_LINKS),
            new Setting(Category.SHARING, S.GROUP_LINKS, S.EXTERNAL_BROWSER, S.EXTERNAL_BROWSER_DESC, Prefs.EXTERNAL_BROWSER),
            new Setting(Category.SHARING, S.GROUP_LINKS, S.SCREENSHOTS, S.SCREENSHOTS_DESC, Prefs.SCREENSHOTS),
            new Setting(Category.GHOST, S.GROUP_INVISIBLE, S.GHOST_STORIES, S.GHOST_STORIES_DESC, Prefs.GHOST_STORIES),
            new Setting(Category.GHOST, S.GROUP_INVISIBLE, S.GHOST_PROFILE, S.GHOST_PROFILE_DESC, Prefs.GHOST_PROFILE),
            new Setting(Category.GHOST, S.GROUP_INVISIBLE, S.GHOST_POSTS, S.GHOST_POSTS_DESC, Prefs.GHOST_POSTS),
            new Setting(Category.GHOST, S.GROUP_INVISIBLE, S.GHOST_TYPING, S.GHOST_TYPING_DESC, Prefs.GHOST_TYPING),
            new Setting(Category.GHOST, S.GROUP_INVISIBLE, S.GHOST_ONLINE, S.GHOST_ONLINE_DESC, Prefs.GHOST_ONLINE),
            new Setting(Category.GHOST, S.GROUP_INVISIBLE, S.GHOST_READ, S.GHOST_READ_DESC, Prefs.GHOST_READ),
            new Setting(Category.GHOST, S.GROUP_DEVICE, S.BLOCK_LOCATION, S.BLOCK_LOCATION_DESC, Prefs.BLOCK_LOCATION),
            new Setting(Category.GHOST, S.GROUP_DEVICE, S.HIDE_VPN, S.HIDE_VPN_DESC, Prefs.HIDE_VPN),
            new Setting(Category.GHOST, S.GROUP_DEVICE, S.SPOOF_ANDROID_ID, S.SPOOF_ANDROID_ID_DESC, Prefs.SPOOF_ANDROID_ID),
            new Setting(Category.REGION, null, S.REGION_LANGUAGE, S.REGION_LANGUAGE_DESC, Prefs.REGION_LANGUAGE),
            new Setting(Category.STREAKS, null, S.AUTO_STREAK, S.AUTO_STREAK_DESC, Prefs.AUTO_STREAK),
            new Setting(Category.LAB, null, S.EXTRA_FEATURES, S.EXTRA_FEATURES_DESC, Prefs.EXTRA_FEATURES),
            new Setting(Category.LAB, null, S.NO_LOGIN_WALL, S.NO_LOGIN_WALL_DESC, Prefs.NO_LOGIN_WALL),
            new Setting(Category.LAB, null, S.NO_HASHTAG_LIMIT, S.NO_HASHTAG_LIMIT_DESC, Prefs.NO_HASHTAG_LIMIT),
            new Setting(Category.LAB, null, S.CAMERA_EXTRAS, S.CAMERA_EXTRAS_DESC, Prefs.CAMERA_EXTRAS),
    };

    private View mainPage() {
        Page page = new Page(I18n.get(S.APP_NAME), true);
        LinearLayout status = page.card();
        TextView statusDetail = statusRow(status);
        Runnable counter = counter(page.content);
        quickTiles(page.content);

        EditText search = searchBox(page.content);
        LinearLayout groups = new LinearLayout(this);
        groups.setOrientation(LinearLayout.VERTICAL);
        page.content.addView(groups, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setVisibility(View.GONE);
        page.content.addView(results, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, dp(12));
        LinearLayout list = card(groups);
        List<Runnable> counters = new ArrayList<>();
        for (Category category : Category.values()) counters.add(categoryRow(list, category));
        groups.addView(new View(this), gap);
        LinearLayout about = card(groups);
        navigation(about, I18n.get(S.APP_LANGUAGE_TITLE), currentLanguageDisplayName(), view -> push(languagePage(), true));
        navigation(about, I18n.get(S.VERSION), BuildConfig.VERSION_NAME, null);
        navigation(about, I18n.get(S.CREDITS_TITLE), "hik0w", view -> push(creditsPage(), true));
        navigation(about, I18n.get(S.BACKUP_EXPORT), null, view -> openSheet(exportSheet()));
        navigation(about, I18n.get(S.BACKUP_IMPORT), null, view -> push(importPage(), true));
        navigation(about, I18n.get(S.SOURCE_CODE), null, view -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)));
            } catch (RuntimeException ignored) { }
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                String query = s.toString().trim().toLowerCase(locale);
                View shown = query.isEmpty() ? groups : results;
                View hidden = query.isEmpty() ? results : groups;
                if (!query.isEmpty()) showResults(results, query);
                if (shown.getVisibility() != View.VISIBLE) {
                    hidden.setVisibility(View.GONE);
                    shown.setVisibility(View.VISIBLE);
                    shown.setAlpha(0f);
                    shown.setTranslationY(dp(8));
                    shown.animate().alpha(1f).translationY(0).setDuration(200).setInterpolator(EASE).start();
                }
            }
        });
        page.root.setTag((Runnable) () -> {
            statusDetail.setText(statusText());
            counter.run();
            for (Runnable refresh : counters) refresh.run();
        });
        return page.root;
    }

    private int enabledCount() {
        int enabled = 0;
        for (Setting setting : SETTINGS) if (Prefs.on(setting.key)) enabled++;
        return enabled;
    }

    /**
     * The hero: how many of the module's switches are on, as a large number with a thin bar
     * under it. The number counts up once when the settings open; that is the screen's one
     * moment of delight, and it is seen once per visit, not per tap.
     */
    private Runnable counter(LinearLayout content) {
        int total = SETTINGS.length;
        TextView number = new TextView(this) {
            @Override public void setText(CharSequence text, BufferType type) {
                // The total after the slash stays quieter than the count itself.
                String value = text == null ? "" : text.toString();
                int slash = value.indexOf('/');
                if (slash < 0) {
                    super.setText(text, type);
                    return;
                }
                android.text.SpannableString styled = new android.text.SpannableString(value);
                styled.setSpan(new android.text.style.ForegroundColorSpan(palette.textTertiary), slash, value.length(), 0);
                styled.setSpan(new android.text.style.RelativeSizeSpan(0.5f), slash, value.length(), 0);
                super.setText(styled, BufferType.SPANNABLE);
            }
        };
        number.setTextColor(palette.textPrimary);
        number.setTextSize(56);
        number.setLetterSpacing(-0.04f);
        number.setTypeface(Fonts.bold(this));
        number.setFontFeatureSettings("tnum");
        number.setIncludeFontPadding(false);
        LinearLayout.LayoutParams numberParams = new LinearLayout.LayoutParams(-2, -2);
        numberParams.setMargins(dp(24), dp(22), dp(24), dp(4));
        content.addView(number, numberParams);
        TextView caption = new TextView(this);
        caption.setTextColor(palette.textSecondary);
        caption.setTextSize(13.5f);
        caption.setTypeface(Fonts.regular(this));
        caption.setPadding(dp(26), 0, dp(26), dp(10));
        content.addView(caption);
        View track = new View(this);
        track.setBackground(rounded(palette.trackOff, dp(2)));
        FrameLayout bar = new FrameLayout(this);
        bar.addView(track, new FrameLayout.LayoutParams(-1, dp(3)));
        View fill = new View(this);
        fill.setBackground(rounded(palette.trackOn, dp(2)));
        // The bar grows from the start edge, which is the right one in RTL.
        fill.setPivotX(0);
        fill.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) ->
                view.setPivotX(view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? r - l : 0));
        bar.addView(fill, new FrameLayout.LayoutParams(-1, dp(3)));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, dp(3));
        barParams.setMargins(dp(26), 0, dp(26), dp(22));
        content.addView(bar, barParams);

        Runnable show = () -> {
            int enabled = enabledCount();
            number.setText(enabled + "/" + total);
            // The number above already says how many; the caption says of what.
            caption.setText(I18n.get(S.ENABLED_LABEL));
            caption.setContentDescription(String.format(locale, I18n.get(S.ENABLED_COUNT), enabled, total));
            fill.setScaleX(enabled / (float) total);
        };
        show.run();
        if (motion()) {
            int enabled = enabledCount();
            ValueAnimator count = ValueAnimator.ofFloat(0f, 1f);
            count.setDuration(700);
            count.setStartDelay(120);
            count.setInterpolator(EASE);
            count.addUpdateListener(animation -> {
                float t = (float) animation.getAnimatedValue();
                number.setText(Math.round(enabled * t) + "/" + total);
                fill.setScaleX(enabled * t / total);
            });
            number.setText("0/" + total);
            fill.setScaleX(0f);
            count.start();
        }
        return show;
    }

    /** The four switches people reach for most, as tiles: filled when on, a tap flips them. */
    private void quickTiles(LinearLayout content) {
        Object[][] tiles = {
                {S.ADS, Glyph.Kind.FEED, new String[] {Prefs.ADS}},
                {S.NO_WATERMARK, Glyph.Kind.DOWNLOAD, new String[] {Prefs.NO_WATERMARK}},
                {S.AUTO_SCROLL, Glyph.Kind.SCROLL, new String[] {Prefs.AUTO_SCROLL}},
                {S.SECTION_GHOST, Glyph.Kind.GHOST, new String[] {Prefs.GHOST_STORIES, Prefs.GHOST_PROFILE, Prefs.GHOST_POSTS}},
        };
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(-1, -2);
        gridParams.setMargins(dp(16), 0, dp(16), dp(4));
        content.addView(grid, gridParams);
        LinearLayout line = null;
        for (int index = 0; index < tiles.length; index++) {
            if (index % 2 == 0) {
                line = new LinearLayout(this);
                LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(-1, -2);
                if (index > 0) lineParams.topMargin = dp(8);
                grid.addView(line, lineParams);
            }
            View tile = quickTile((S) tiles[index][0], (Glyph.Kind) tiles[index][1], (String[]) tiles[index][2]);
            LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, dp(92), 1);
            if (index % 2 == 1) tileParams.setMarginStart(dp(8));
            line.addView(tile, tileParams);
        }
    }

    private View quickTile(S title, Glyph.Kind kind, String[] keys) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(14), dp(14), dp(14), dp(12));
        tile.setClickable(true);
        tile.setFocusable(true);
        ImageView icon = new ImageView(this);
        tile.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        View space = new View(this);
        tile.addView(space, new LinearLayout.LayoutParams(0, 0, 1));
        TextView label = new TextView(this);
        label.setText(I18n.get(title));
        label.setTextSize(14);
        label.setTypeface(Fonts.semibold(this));
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        tile.addView(label);
        GradientDrawable fill = rounded(palette.card, dp(18));
        tile.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.dark ? 0x22FFFFFF : 0x22000000), fill, null));
        tile.setContentDescription(I18n.get(title));
        float[] state = {on(keys) ? 1f : 0f};
        Runnable paint = () -> {
            int background = blend(palette.card, palette.trackOn, state[0]);
            int ink = blend(palette.textPrimary, palette.trackOn == palette.textPrimary ? palette.background : 0xFFFFFFFF, state[0]);
            fill.setColor(background);
            label.setTextColor(ink);
            icon.setImageDrawable(new Glyph(kind, blend(palette.textSecondary, ink, state[0]), dp(22)));
        };
        paint.run();
        tile.setOnClickListener(view -> {
            boolean value = !on(keys);
            for (String key : keys) Prefs.set(key, value);
            if (Build.VERSION.SDK_INT >= 34) {
                view.performHapticFeedback(value ? android.view.HapticFeedbackConstants.TOGGLE_ON
                        : android.view.HapticFeedbackConstants.TOGGLE_OFF);
            } else {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            }
            float from = state[0];
            float to = value ? 1f : 0f;
            if (!motion()) {
                state[0] = to;
                paint.run();
                return;
            }
            ValueAnimator flip = ValueAnimator.ofFloat(from, to);
            flip.setDuration(180);
            flip.setInterpolator(EASE);
            flip.addUpdateListener(animation -> {
                state[0] = (float) animation.getAnimatedValue();
                paint.run();
            });
            flip.start();
        });
        tile.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Switch");
                info.setCheckable(true);
                info.setChecked(on(keys));
            }
        });
        return tile;
    }

    private static boolean on(String[] keys) {
        for (String key : keys) if (!Prefs.on(key)) return false;
        return true;
    }

    /** First card on the main page: the module is running, with the TikTok build and region it runs with. */
    private TextView statusRow(LinearLayout card) {
        LinearLayout row = row(card);
        row.setOnClickListener(view -> {
            hideKeyboard(view);
            push(statusPage(), true);
        });
        View dot = new View(this);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(FeatureStatus.problems() == 0 ? 0xFF2BB673 : 0xFFFF9F0A);
        // The one colored mark on the page, because it states a real condition. It stays still.
        dot.setBackground(circle);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(7), dp(7));
        dotParams.setMarginEnd(dp(12));
        row.addView(dot, dotParams);
        LinearLayout texts = texts(row, I18n.get(S.STATUS_ACTIVE), statusText());
        ImageView chevron = new ImageView(this);
        chevron.setImageDrawable(new Glyph(Glyph.Kind.CHEVRON, palette.chevron, dp(20)));
        row.addView(chevron, new LinearLayout.LayoutParams(dp(20), dp(20)));
        return (TextView) texts.getChildAt(1);
    }

    /** Feature names as Module installs them, shown with the setting they belong to. */
    private static final String[][] FEATURE_TITLES = {
            {"feed filter", "ADS"}, {"search ads", "PROMO"}, {"rendered ads", "ADS"}, {"startup ads", "ADS"},
            {"LIVE button", "LIVE"}, {"seekbar", "SEEKBAR"}, {"remember clear display", "REMEMBER_CLEAN"},
            {"resume position", "RESUME_POSITION"}, {"playback speed", "REMEMBER_SPEED"}, {"downloads plus", "QUALITY_PICKER"}, {"auto-scroll", "AUTO_SCROLL"}, {"comment media", "COMMENT_MEDIA"},
            {"comment copy", "COPY_COMMENT"}, {"external browser", "EXTERNAL_BROWSER"},
            {"interface", "HIDE_INBOX_STORIES"}, {"overlay", "OVERLAY_OPACITY"}, {"accent color", "ACCENT"},
            {"author region", "AUTHOR_REGION"}, {"comment translation", "AUTO_TRANSLATE"}, {"downloads", "NO_WATERMARK"}, {"clean links", "CLEAN_LINKS"},
            {"invisible mode", "SECTION_GHOST"}, {"region", "REGION"}, {"recommendation", "REGION_LANGUAGE"},
            {"A/B overrides", "EXTRA_FEATURES"}, {"auto streaks", "AUTO_STREAK"}, {"streak sticker", "STREAK_STICKER"},
            {"friend card", "ACQUAINTANCES"}, {"unmute", "UNMUTE"}, {"font", "FONT"}, {"privacy", "SECTION_PRIVACY"},
            {"refresh rate", "REFRESH_RATE"}, {"story download", "STORY_DOWNLOAD"}, {"avatar download", "AVATAR_DOWNLOAD"},
            {"message time", "MESSAGE_TIME"}, {"chat menu", "CHAT_STICKER"}, {"hd upload", "HD_UPLOAD"},
            {"read receipts", "GHOST_READ"}, {"read all", "READ_ALL"}};

    private View statusPage() {
        Page page = new Page(I18n.get(S.STATUS_TITLE), true);
        LinearLayout card = page.card();
        for (String feature : FeatureStatus.features()) {
            String title = null;
            for (String[] entry : FEATURE_TITLES) {
                if (entry[0].equals(feature)) title = I18n.get(S.valueOf(entry[1]));
            }
            if (title == null) continue;
            String problem = FeatureStatus.problem(feature);
            LinearLayout row = row(card);
            row.setBackground(null);
            View dot = new View(this);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(problem == null ? 0xFF2BB673 : 0xFFFF9F0A);
            dot.setBackground(circle);
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
            dotParams.setMarginEnd(dp(14));
            row.addView(dot, dotParams);
            texts(row, title, (problem == null ? I18n.get(S.STATUS_OK) : I18n.get(S.STATUS_PROBLEM)) + " · " + feature);
        }
        page.footer(I18n.get(S.STATUS_FOOTER));
        return page.root;
    }

    private String statusText() {
        String version;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception error) {
            version = BuildConfig.VERSION_NAME;
        }
        int problems = FeatureStatus.problems();
        String features = problems == 0 ? I18n.get(S.STATUS_ALL_OK) : String.format(locale != null ? locale : Locale.getDefault(), I18n.get(S.STATUS_SOME), problems);
        String label = "com.golda.patchertiktok".equals(getPackageName()) ? "Module " : "TikTok ";
        return label + version + " · " + features;
    }

    private EditText searchBox(LinearLayout parent) {
        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setPadding(dp(12), 0, dp(12), 0);
        searchBox.setBackground(rounded(palette.field, dp(12)));
        ImageView glass = new ImageView(this);
        glass.setImageDrawable(new Glyph(Glyph.Kind.SEARCH, palette.textSecondary, dp(18)));
        searchBox.addView(glass, new LinearLayout.LayoutParams(dp(18), dp(18)));
        EditText search = new EditText(this);
        search.setBackground(null);
        search.setHint(I18n.get(S.SEARCH_SETTINGS));
        search.setHintTextColor(palette.textSecondary);
        search.setTextColor(palette.textPrimary);
        search.setTextSize(16);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPaddingRelative(dp(8), 0, 0, 0);
        searchBox.addView(search, new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(-1, dp(46));
        boxParams.setMargins(dp(16), dp(10), dp(16), dp(12));
        parent.addView(searchBox, boxParams);
        return search;
    }

    /**
     * A group row: a thin grey icon, the name, and a row of dots, one per switch in the group,
     * filled for those that are on. The region group shows the chosen country instead.
     */
    private Runnable categoryRow(LinearLayout card, Category category) {
        LinearLayout row = row(card);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(new Glyph(category.icon, palette.textSecondary, dp(20)));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(20), dp(20));
        iconParams.setMarginEnd(dp(14));
        row.addView(icon, iconParams);
        LinearLayout texts = texts(row, I18n.get(category.title), null);
        texts.setContentDescription(I18n.get(category.title) + ". " + categorySummary(category));
        TextView value = null;
        Meter meter = null;
        if (category == Category.REGION) {
            // A small chip: round flag + country code, or "Off".
            value = new TextView(this);
            value.setTextSize(12.5f);
            value.setBackground(rounded(palette.pressed, dp(8)));
            value.setPadding(dp(8), dp(4), dp(9), dp(4));
            value.setCompoundDrawablePadding(dp(6));
            value.setGravity(Gravity.CENTER_VERTICAL);
            value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(value);
        } else {
            meter = new Meter(this, palette.trackOn, palette.trackOff);
            row.addView(meter);
        }
        ImageView chevron = new ImageView(this);
        chevron.setImageDrawable(new Glyph(Glyph.Kind.CHEVRON, palette.chevron, dp(18)));
        LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(dp(18), dp(18));
        chevronParams.setMarginStart(dp(10));
        row.addView(chevron, chevronParams);
        row.setOnClickListener(view -> {
            hideKeyboard(view);
            push(categoryPage(category), true);
        });
        TextView regionValue = value;
        Meter dots = meter;
        Runnable refresh = () -> {
            texts.setContentDescription(I18n.get(category.title) + ". " + categorySummary(category));
            if (regionValue != null) {
                String code = Prefs.region();
                String flag = RegionFlag.of(code);
                boolean set = Regions.find(code) != null;
                regionValue.setText(set ? code.toUpperCase(Locale.ROOT) : I18n.get(S.OFF));
                regionValue.setTextColor(set ? palette.textPrimary : palette.textSecondary);
                regionValue.setTypeface(set ? Fonts.semibold(this) : Fonts.regular(this));
                regionValue.setLetterSpacing(set ? 0.04f : 0f);
                regionValue.setCompoundDrawablesRelative(set && flag != null ? new FlagDot(flag, dp(14)) : null,
                        null, null, null);
            }
            if (dots == null) return;
            int total = 0;
            int enabled = 0;
            for (Setting setting : SETTINGS) {
                if (setting.category != category) continue;
                total++;
                if (Prefs.on(setting.key)) enabled++;
            }
            dots.set(enabled, total);
        };
        refresh.run();
        return refresh;
    }

    private String categorySummary(Category category) {
        if (category == Category.REGION) return regionLabel();
        int total = 0;
        int enabled = 0;
        for (Setting setting : SETTINGS) {
            if (setting.category != category) continue;
            total++;
            if (Prefs.on(setting.key)) enabled++;
        }
        return String.format(locale, I18n.get(S.ENABLED_COUNT), enabled, total);
    }

    private void showResults(LinearLayout results, String query) {
        results.removeAllViews();
        LinearLayout card = null;
        for (Setting setting : SETTINGS) {
            String title = I18n.get(setting.title);
            String description = setting.description == null ? "" : I18n.get(setting.description);
            String group = I18n.get(setting.category.title);
            if (!(title + " " + description + " " + group).toLowerCase(locale).contains(query)) continue;
            if (card == null) card = card(results);
            toggle(card, setting.title, setting.description, setting.key);
        }
        if (card == null) {
            TextView empty = new TextView(this);
            empty.setText(I18n.get(S.NOTHING_FOUND));
            empty.setTextColor(palette.textSecondary);
            empty.setTextSize(15);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(32), dp(24), dp(32));
            results.addView(empty);
        }
    }

    private View categoryPage(Category category) {
        if (category == Category.HIDE_TRANSPARENCY) {
            return hideTransparencyPage();
        }
        Page page = new Page(I18n.get(category.title), true);
        if (category == Category.REGION) {
            buildRegion(page, page.card());
            return page.root;
        }
        if (category == Category.STREAKS) {
            Runnable sync = streaks(page);
            sync.run();
            page.root.setTag(sync);
            return page.root;
        }
        // Each group gets its own heading and card; extra rows sit right under the switch they belong to.
        List<Runnable> syncs = new ArrayList<>();
        LinearLayout card = null;
        S group = null;
        for (Setting setting : SETTINGS) {
            if (setting.category != category) continue;
            if (card == null || setting.group != group) {
                group = setting.group;
                if (group != null) page.section(I18n.get(group));
                card = page.card();
            }
            toggle(card, setting.title, setting.description, setting.key);
            extras(card, setting.key, syncs);
        }
        // Hide and transparency controls have been moved to their own dedicated category
        for (Runnable sync : syncs) sync.run();
        if (!syncs.isEmpty()) page.root.setTag((Runnable) () -> { for (Runnable sync : syncs) sync.run(); });
        return page.root;
    }

    /** Rows that refine a switch: shown under it, and only while it is on. */
    private void extras(LinearLayout card, String key, List<Runnable> syncs) {
        if (Prefs.BURN_IN.equals(key)) {
            View dim = toggle(card, S.BURN_IN_DIM, S.BURN_IN_DIM_DESC, Prefs.BURN_IN_DIM);
            subOption(dim);
            syncs.add(() -> dim.setVisibility(Prefs.on(Prefs.BURN_IN) ? View.VISIBLE : View.GONE));
            navigation(card, I18n.get(S.HIDE_PARTS), null, view -> push(hidePage(), true));
            TextView rate = navigation(card, I18n.get(S.REFRESH_RATE), refreshLabel(), view -> openSheet(refreshSheet()));
            syncs.add(() -> rate.setText(refreshLabel()));
        } else if (Prefs.ACCENT.equals(key)) {
            TextView value = navigation(card, I18n.get(S.ACCENT_PICK), null, view -> push(colorPage(), true));
            View row = (View) value.getParent();
            syncs.add(() -> {
                value.setCompoundDrawablesRelative(swatch((int) Prefs.number(Prefs.ACCENT_COLOR), dp(22)), null, null, null);
                row.setVisibility(Prefs.on(Prefs.ACCENT) ? View.VISIBLE : View.GONE);
            });
        } else if (Prefs.HD_UPLOAD.equals(key)) {
            TextView value = navigation(card, I18n.get(S.HD_BITRATE), bitrateLabel(), view -> openSheet(bitrateSheet()));
            View row = (View) value.getParent();
            syncs.add(() -> {
                value.setText(bitrateLabel());
                row.setVisibility(Prefs.on(Prefs.HD_UPLOAD) ? View.VISIBLE : View.GONE);
            });
        } else if (Prefs.AMOLED.equals(key)) {
            TextView value = navigation(card, I18n.get(S.FONT), fontLabel(), view -> openSheet(fontSheet()));
            syncs.add(() -> value.setText(fontLabel()));
        } else if (Prefs.REMEMBER_SPEED.equals(key)) {
            TextView value = navigation(card, I18n.get(S.SPEEDS), speedsLabel(), view -> push(speedsPage(), true));
            syncs.add(() -> value.setText(speedsLabel()));
        } else if (Prefs.AUTO_TRANSLATE.equals(key)) {
            TextView value = navigation(card, I18n.get(S.TRANSLATE_EXCLUDE), Prefs.text(Prefs.TRANSLATE_EXCLUDE),
                    view -> push(excludePage(), true));
            View row = (View) value.getParent();
            syncs.add(() -> {
                value.setText(Prefs.text(Prefs.TRANSLATE_EXCLUDE));
                row.setVisibility(Prefs.on(Prefs.AUTO_TRANSLATE) ? View.VISIBLE : View.GONE);
            });
        } else if (Prefs.CUSTOM_FILTER.equals(key)) {
            View setup = (View) navigation(card, I18n.get(S.FILTER_SETUP), null, view -> push(filterPage(), true)).getParent();
            syncs.add(() -> setup.setVisibility(Prefs.on(Prefs.CUSTOM_FILTER) ? View.VISIBLE : View.GONE));
        } else if (Prefs.OLD_VIDEOS.equals(key)) {
            TextView value = navigation(card, I18n.get(S.OLD_AGE), ageLabel(Prefs.oldVideoDays()),
                    view -> push(agePage(), true));
            View row = (View) value.getParent();
            syncs.add(() -> {
                value.setText(ageLabel(Prefs.oldVideoDays()));
                row.setVisibility(Prefs.on(Prefs.OLD_VIDEOS) ? View.VISIBLE : View.GONE);
            });
        }
    }

    /** A slider for the opacity of buttons and captions over videos. */
    private void opacityRow(Page page) {
        page.section(I18n.get(S.OVERLAY_OPACITY));
        LinearLayout card = page.card();
        LinearLayout row = row(card);
        row.setBackground(null);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.START);
        TextView value = new TextView(this);
        value.setTextColor(palette.textPrimary);
        value.setTextSize(17);
        value.setTypeface(Fonts.semibold(this));
        row.addView(value);
        android.widget.SeekBar slider = new android.widget.SeekBar(this);
        slider.setMax(90);
        long current = Prefs.number(Prefs.OVERLAY_OPACITY);
        int percent = current <= 0 ? 100 : (int) Math.max(10, Math.min(100, current));
        slider.setProgress(percent - 10);
        slider.setProgressTintList(ColorStateList.valueOf(palette.trackOn));
        slider.setThumbTintList(ColorStateList.valueOf(palette.trackOn));
        value.setText(percent + "%");
        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean user) {
                value.setText((progress + 10) + "%");
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar bar) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar bar) {
                Prefs.setNumber(Prefs.OVERLAY_OPACITY, bar.getProgress() + 10);
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        row.addView(slider, params);
        page.footer(I18n.get(S.OVERLAY_OPACITY_DESC));
    }

    /** A slider for the opacity of bottom navigation buttons (0% - 100%). */
    private void bottomNavOpacityRow(Page page) {
        page.section(I18n.get(S.BOTTOM_NAV_OPACITY));
        LinearLayout card = page.card();
        LinearLayout row = row(card);
        row.setBackground(null);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.START);
        TextView value = new TextView(this);
        value.setTextColor(palette.textPrimary);
        value.setTextSize(17);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(value);
        android.widget.SeekBar slider = new android.widget.SeekBar(this);
        slider.setMax(100);
        long current = Prefs.number(Prefs.BOTTOM_NAV_OPACITY);
        int percent = (int) Math.max(0, Math.min(100, current <= 0 && !Prefs.contains(Prefs.BOTTOM_NAV_OPACITY) ? 100 : current));
        slider.setProgress(percent);
        slider.setProgressTintList(ColorStateList.valueOf(palette.trackOn));
        slider.setThumbTintList(ColorStateList.valueOf(palette.trackOn));
        value.setText(percent + "%");
        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean user) {
                value.setText(progress + "%");
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar bar) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar bar) {
                Prefs.setNumber(Prefs.BOTTOM_NAV_OPACITY, bar.getProgress());
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        row.addView(slider, params);
        page.footer(I18n.get(S.BOTTOM_NAV_OPACITY_DESC));
    }

    private GradientDrawable swatch(int rgb, int size) {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xFF000000 | rgb);
        circle.setStroke(dp(1), palette.dark ? 0x33FFFFFF : 0x22000000);
        circle.setSize(size, size);
        circle.setBounds(0, 0, size, size);
        return circle;
    }

    /** A switch drawn in the given accent, to preview it before leaving the picker. */
    private Toggle sampleToggle(int rgb) {
        Palette sample = Palette.settings(palette.dark, palette.dark && Prefs.on(Prefs.AMOLED), 0xFF000000 | rgb);
        Toggle toggle = new Toggle(this, sample);
        toggle.setChecked(true, false);
        toggle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return toggle;
    }

    /** A saturation/brightness square, a hue strip and a hex field; every change is saved at once. */
    private View colorPage() {
        Page page = new Page(I18n.get(S.ACCENT_PICK), true);
        int current = (int) Prefs.number(Prefs.ACCENT_COLOR) & 0xFFFFFF;

        LinearLayout sampleCard = page.card();
        LinearLayout sampleRow = row(sampleCard);
        sampleRow.setBackground(null);
        texts(sampleRow, I18n.get(S.ACCENT_SAMPLE), null);
        FrameLayout sampleSlot = new FrameLayout(this);
        sampleRow.addView(sampleSlot, new LinearLayout.LayoutParams(-2, -2));
        sampleSlot.addView(sampleToggle(current));

        page.content.addView(new View(this), new LinearLayout.LayoutParams(-1, dp(12)));
        LinearLayout panelCard = page.card();
        ColorPanel panel = new ColorPanel(this);
        panel.setColor(current);
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(-1, -2);
        panelParams.setMargins(dp(8), dp(10), dp(8), dp(4));
        panelCard.addView(panel, panelParams);

        page.section(I18n.get(S.ACCENT_HEX));
        LinearLayout hexCard = page.card();
        LinearLayout row = row(hexCard);
        row.setBackground(null);
        ImageView preview = new ImageView(this);
        preview.setImageDrawable(swatch(current, dp(26)));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(dp(26), dp(26));
        previewParams.setMarginEnd(dp(12));
        row.addView(preview, previewParams);
        EditText hex = new EditText(this);
        hex.setBackground(null);
        hex.setPadding(0, 0, 0, 0);
        hex.setText(String.format(Locale.ROOT, "#%06X", current));
        hex.setTextColor(palette.textPrimary);
        hex.setTextSize(17);
        hex.setTypeface(Fonts.semibold(this));
        hex.setSingleLine(true);
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hex.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});
        hex.setImeOptions(EditorInfo.IME_ACTION_DONE);
        row.addView(hex, new LinearLayout.LayoutParams(0, -2, 1));
        boolean[] typing = {false};
        java.util.function.IntConsumer show = rgb -> {
            preview.setImageDrawable(swatch(rgb, dp(26)));
            sampleSlot.removeAllViews();
            sampleSlot.addView(sampleToggle(rgb));
        };
        panel.setListener((rgb, done) -> {
            show.accept(rgb);
            typing[0] = true;
            hex.setText(String.format(Locale.ROOT, "#%06X", rgb));
            typing[0] = false;
            if (done) {
                Prefs.setNumber(Prefs.ACCENT_COLOR, rgb);
                panel.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            }
        });
        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                if (typing[0]) return;
                Integer rgb = parseHex(s.toString());
                if (rgb == null) return;
                show.accept(rgb);
                panel.setColor(rgb);
            }
        });
        hex.setOnEditorActionListener((view, action, event) -> {
            Integer rgb = parseHex(hex.getText().toString());
            if (rgb != null) {
                Prefs.setNumber(Prefs.ACCENT_COLOR, rgb);
                hideKeyboard(view);
                pop();
            }
            return true;
        });
        page.footer(I18n.get(S.ACCENT_FOOTER));
        return page.root;
    }

    // ---- font ------------------------------------------------------------------------------

    private String fontLabel() {
        if (!Prefs.on(Prefs.FONT)) return I18n.get(S.FONT_TIKTOK);
        if (!FontSwap.SOURCE_FILE.equals(Prefs.text(Prefs.FONT_SOURCE))) return I18n.get(S.FONT_SYSTEM);
        String name = Prefs.text(Prefs.FONT_FILE);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** The face a choice will look like: TikTok's (this screen's own font), the system's, or the imported file. */
    private Typeface fontPreview(int choice) {
        if (choice == 1) return Typeface.create("sans-serif", Typeface.NORMAL);
        if (choice == 2) {
            java.io.File file = FontSwap.file(this);
            if (file.isFile()) {
                try {
                    return Typeface.createFromFile(file);
                } catch (RuntimeException broken) {
                    return Fonts.regular(this);
                }
            }
        }
        return Fonts.regular(this);
    }

    /** 0 TikTok, 1 system, 2 file. */
    private static int fontChoice() {
        if (!Prefs.on(Prefs.FONT)) return 0;
        return FontSwap.SOURCE_FILE.equals(Prefs.text(Prefs.FONT_SOURCE)) ? 2 : 1;
    }

    /** A sample line in the chosen font, then the three sources as a single-choice list. */
    private View fontSheet() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        TextView sample = new TextView(this);
        sample.setText(I18n.get(S.FONT_SAMPLE));
        sample.setTextColor(palette.textPrimary);
        sample.setTextSize(22);
        sample.setPadding(dp(18), dp(6), dp(18), dp(14));
        content.addView(sample);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list);
        boolean hasFile = FontSwap.file(this).isFile() && !Prefs.text(Prefs.FONT_FILE).isEmpty();
        S[] titles = {S.FONT_TIKTOK, S.FONT_SYSTEM, S.FONT_FROM_FILE};
        List<ImageView> marks = new ArrayList<>();
        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            int chosen = fontChoice();
            sample.setTypeface(fontPreview(chosen));
            for (int index = 0; index < marks.size(); index++) {
                marks.get(index).setImageDrawable(radio(index == chosen));
            }
        };
        for (int index = 0; index < titles.length; index++) {
            int choice = index;
            LinearLayout row = row(list);
            String detail = null;
            if (choice == 2) {
                detail = hasFile ? Prefs.text(Prefs.FONT_FILE) + " · " + I18n.get(S.FONT_CHANGE) : I18n.get(S.FONT_PICK);
            }
            LinearLayout texts = texts(row, I18n.get(titles[index]), detail);
            ImageView mark = new ImageView(this);
            row.addView(mark, new LinearLayout.LayoutParams(dp(22), dp(22)));
            marks.add(mark);
            if (choice == 2) {
                // The note under "From a file" picks a new file; the row itself selects the saved one.
                View note = texts.getChildCount() > 1 ? texts.getChildAt(texts.getChildCount() - 1) : null;
                if (note != null) note.setOnClickListener(view -> pickFont());
            }
            row.setOnClickListener(view -> {
                row.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                if (choice == 2 && !(FontSwap.file(this).isFile() && !Prefs.text(Prefs.FONT_FILE).isEmpty())) {
                    pickFont();
                    return;
                }
                Prefs.set(Prefs.FONT, choice != 0);
                if (choice != 0) Prefs.setText(Prefs.FONT_SOURCE, choice == 2 ? FontSwap.SOURCE_FILE : FontSwap.SOURCE_SYSTEM);
                render[0].run();
            });
        }
        render[0].run();
        fontChanged = () -> {
            if (sheet == null) return;
            openSheet(fontSheet());
        };
        return content;
    }

    private String refreshLabel() {
        long rate = Prefs.number(Prefs.REFRESH_RATE);
        return rate <= 0 ? I18n.get(S.FONT_TIKTOK) : String.format(locale, I18n.get(S.REFRESH_HZ), rate);
    }

    /** TikTok's own choice and every rate the screen offers, as a single-choice list. */
    private View refreshSheet() {
        List<Integer> choices = new ArrayList<>();
        choices.add(0);
        choices.addAll(RefreshRate.rates(this));
        return choiceSheet(Prefs.REFRESH_RATE, choices,
                hz -> hz == 0 ? I18n.get(S.FONT_TIKTOK) : String.format(locale, I18n.get(S.REFRESH_HZ), hz), S.REFRESH_FOOTER,
                // This screen is a TikTok window too, so the new rate shows here at once.
                () -> getWindow().setAttributes(getWindow().getAttributes()));
    }

    private String bitrateLabel() {
        long mbps = Prefs.number(Prefs.HD_BITRATE);
        return mbps <= 0 ? I18n.get(S.FONT_TIKTOK) : String.format(locale, I18n.get(S.HD_MBPS), mbps);
    }

    private View bitrateSheet() {
        List<Integer> choices = new ArrayList<>();
        for (int mbps : HdUpload.CHOICES) choices.add(mbps);
        return choiceSheet(Prefs.HD_BITRATE, choices,
                mbps -> mbps == 0 ? I18n.get(S.FONT_TIKTOK) : String.format(locale, I18n.get(S.HD_MBPS), mbps), S.HD_BITRATE_DESC, null);
    }

    /** A single-choice list of numbers stored under {@code key}, with a note under it. */
    private View choiceSheet(String key, List<Integer> choices, java.util.function.IntFunction<String> label, S note,
            Runnable picked) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        List<ImageView> marks = new ArrayList<>();
        Runnable render = () -> {
            long chosen = Prefs.number(key);
            for (int index = 0; index < marks.size(); index++) marks.get(index).setImageDrawable(radio(choices.get(index) == chosen));
        };
        for (int value : choices) {
            LinearLayout row = row(content);
            texts(row, label.apply(value), null);
            ImageView mark = new ImageView(this);
            row.addView(mark, new LinearLayout.LayoutParams(dp(22), dp(22)));
            marks.add(mark);
            row.setOnClickListener(view -> {
                row.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                Prefs.setNumber(key, value);
                if (picked != null) picked.run();
                render.run();
            });
        }
        TextView footer = new TextView(this);
        footer.setText(I18n.get(note));
        footer.setTextColor(palette.textTertiary);
        footer.setTextSize(13);
        footer.setTypeface(Fonts.regular(this));
        footer.setPadding(dp(18), dp(8), dp(18), dp(6));
        content.addView(footer);
        render.run();
        return content;
    }

    private GradientDrawable radio(boolean on) {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setSize(dp(22), dp(22));
        if (on) {
            circle.setColor(palette.background);
            circle.setStroke(dp(6.5f), palette.trackOn);
        } else {
            circle.setColor(0);
            circle.setStroke(dp(2), palette.textTertiary);
        }
        return circle;
    }

    private void pickFont() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"font/ttf", "font/otf", "font/sfnt", "font/collection",
                "application/x-font-ttf", "application/x-font-otf", "application/font-sfnt", "application/octet-stream"});
        try {
            startActivityForResult(pick, PICK_FONT);
        } catch (RuntimeException unavailable) {
            android.widget.Toast.makeText(this, I18n.get(S.FONT_FAILED), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == SAVE_BACKUP || request == OPEN_BACKUP) {
            backupFile(request, data.getData());
            return;
        }
        if (request != PICK_FONT) return;
        Uri uri = data.getData();
        String name = displayName(uri);
        new Thread(() -> {
            String error = FontSwap.importFont(this, uri);
            runOnUiThread(() -> {
                if (error != null) {
                    android.widget.Toast.makeText(this, I18n.get(S.FONT_FAILED), android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                Prefs.setText(Prefs.FONT_FILE, name);
                Prefs.setText(Prefs.FONT_SOURCE, FontSwap.SOURCE_FILE);
                Prefs.set(Prefs.FONT, true);
                if (fontChanged != null) fontChanged.run();
            });
        }, "tpx-font-import").start();
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (RuntimeException ignored) { }
        String last = uri.getLastPathSegment();
        return last == null ? "font" : last.substring(last.lastIndexOf('/') + 1);
    }

    // ---- bottom sheet ----------------------------------------------------------------------

    /** A panel that slides up over a dim layer; tapping the dim or Back closes it. */
    private void openSheet(View content) {
        boolean replacing = sheet != null;
        if (replacing) {
            root.removeView(sheet);
            sheet = null;
        }
        FrameLayout layer = new FrameLayout(this);
        View dim = new View(this);
        dim.setBackgroundColor(0x99000000);
        layer.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(palette.card);
        float radius = dp(24);
        shape.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        panel.setBackground(shape);
        panel.setClickable(true);
        panel.setPadding(0, dp(10), 0, dp(12) + bottomInset);
        View grab = new View(this);
        grab.setBackground(rounded(palette.textTertiary, dp(2)));
        grab.setAlpha(0.6f);
        LinearLayout.LayoutParams grabParams = new LinearLayout.LayoutParams(dp(36), dp(4));
        grabParams.gravity = Gravity.CENTER_HORIZONTAL;
        grabParams.bottomMargin = dp(10);
        panel.addView(grab, grabParams);
        panel.addView(content);
        layer.addView(panel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        dim.setOnClickListener(view -> closeSheet());
        root.addView(layer, new FrameLayout.LayoutParams(-1, -1));
        sheet = layer;
        if (!replacing && motion()) {
            dim.setAlpha(0f);
            dim.animate().alpha(1f).setDuration(220).setInterpolator(EASE).start();
            panel.setVisibility(View.INVISIBLE);
            panel.post(() -> {
                panel.setTranslationY(panel.getHeight());
                panel.setVisibility(View.VISIBLE);
                panel.animate().translationY(0).setDuration(280).setInterpolator(OPEN).start();
            });
        }
        if (Build.VERSION.SDK_INT >= 33 && sheetBack == null) {
            android.window.OnBackInvokedCallback callback = this::closeSheet;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback);
            sheetBack = callback;
        }
    }

    private void closeSheet() {
        FrameLayout layer = sheet;
        if (layer == null) return;
        sheet = null;
        fontChanged = null;
        if (Build.VERSION.SDK_INT >= 33 && sheetBack != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) sheetBack);
            sheetBack = null;
        }
        View dim = layer.getChildAt(0);
        View panel = layer.getChildAt(1);
        if (!motion()) {
            root.removeView(layer);
            return;
        }
        dim.animate().alpha(0f).setDuration(180).setInterpolator(EASE).start();
        panel.animate().translationY(panel.getHeight()).setDuration(200).setInterpolator(EASE)
                .withEndAction(() -> root.removeView(layer)).start();
    }

    private static Integer parseHex(String value) {
        String hex = value.trim().replace("#", "");
        if (!hex.matches("[0-9a-fA-F]{6}")) return null;
        return Integer.parseInt(hex, 16);
    }

    /** Save to a .json file or copy as text. */
    private View exportSheet() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout save = row(content);
        texts(save, I18n.get(S.BACKUP_SAVE_FILE), "TiktokPatchXposed-settings.json");
        save.setOnClickListener(view -> {
            closeSheet();
            Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE, "TiktokPatchXposed-settings.json");
            try {
                startActivityForResult(create, SAVE_BACKUP);
            } catch (RuntimeException unavailable) {
                copySettings();
            }
        });
        LinearLayout copy = row(content);
        texts(copy, I18n.get(S.BACKUP_COPY), null);
        copy.setOnClickListener(view -> {
            closeSheet();
            copySettings();
        });
        return content;
    }

    private void copySettings() {
        String json = Prefs.export();
        android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
        if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("TiktokPatchXposed", json));
        android.widget.Toast.makeText(this, I18n.get(S.BACKUP_COPIED), android.widget.Toast.LENGTH_SHORT).show();
    }

    private void openBackup() {
        Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        open.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        try {
            startActivityForResult(open, OPEN_BACKUP);
        } catch (RuntimeException unavailable) {
            android.widget.Toast.makeText(this, I18n.get(S.BACKUP_INVALID), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** Writes or reads a backup file off the main thread, then reports on it. */
    private void backupFile(int request, Uri uri) {
        new Thread(() -> {
            boolean done;
            if (request == SAVE_BACKUP) {
                done = writeText(uri, Prefs.export());
            } else {
                String text = readText(uri);
                done = text != null && Prefs.importJson(text);
            }
            runOnUiThread(() -> {
                S message = request == SAVE_BACKUP ? (done ? S.BACKUP_SAVED : S.DL_FAILED) : (done ? S.BACKUP_DONE : S.BACKUP_INVALID);
                android.widget.Toast.makeText(this, I18n.get(message), android.widget.Toast.LENGTH_SHORT).show();
                if (done && request == OPEN_BACKUP) pop();
            });
        }, "tpx-backup").start();
    }

    private boolean writeText(Uri uri, String text) {
        try (java.io.OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) return false;
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return true;
        } catch (java.io.IOException | RuntimeException error) {
            return false;
        }
    }

    /** At most 1 MB: a backup is a few kilobytes, anything bigger is not one. */
    private String readText(Uri uri) {
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int read; (read = in.read(buffer)) > 0; ) {
                bytes.write(buffer, 0, read);
                if (bytes.size() > 1 << 20) return null;
            }
            return bytes.toString("UTF-8");
        } catch (java.io.IOException | RuntimeException error) {
            return null;
        }
    }

    /** Paste a backup made with "Export settings". */
    private View importPage() {
        Page page = new Page(I18n.get(S.BACKUP_IMPORT), true);
        LinearLayout card = page.card();
        EditText field = new EditText(this);
        field.setBackground(null);
        field.setHint(I18n.get(S.BACKUP_HINT));
        field.setHintTextColor(palette.textSecondary);
        field.setTextColor(palette.textPrimary);
        field.setTextSize(14);
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setMinLines(4);
        field.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.addView(field, new LinearLayout.LayoutParams(-1, -2));
        android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
        if (clipboard != null && clipboard.getPrimaryClip() != null && clipboard.getPrimaryClip().getItemCount() > 0) {
            CharSequence pasted = clipboard.getPrimaryClip().getItemAt(0).getText();
            if (pasted != null && pasted.toString().contains("TiktokPatchXposed")) field.setText(pasted);
        }
        page.content.addView(new View(this), new LinearLayout.LayoutParams(-1, dp(12)));
        LinearLayout actions = page.card();
        navigation(actions, I18n.get(S.BACKUP_OPEN_FILE), null, view -> openBackup());
        navigation(actions, I18n.get(S.BACKUP_APPLY), null, view -> {
            boolean done = Prefs.importJson(field.getText().toString());
            android.widget.Toast.makeText(this, I18n.get(done ? S.BACKUP_DONE : S.BACKUP_INVALID),
                    android.widget.Toast.LENGTH_SHORT).show();
            if (done) {
                hideKeyboard(view);
                pop();
            }
        });
        return page.root;
    }

    private String speedsLabel() {
        String speeds = Prefs.text(Prefs.SPEEDS).trim();
        if (speeds.isEmpty()) return I18n.get(S.SPEEDS_TIKTOK);
        StringBuilder label = new StringBuilder();
        for (Float speed : PlaybackSpeed.parseSpeeds(speeds)) {
            if (label.length() > 0) label.append(", ");
            label.append(speed % 1 == 0 ? String.valueOf(speed.intValue()) : String.valueOf(speed));
        }
        return label.toString();
    }

    /** Dedicated page for all hide & transparency controls, cleanly split into two sections. */

    private View creditsPage() {
        Page page = new Page(I18n.get(S.CREDITS_TITLE), true);
        LinearLayout card = page.card();
        LinearLayout row = row(card);
        row.setBackground(null);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.START);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView author = new TextView(this);
        author.setTextColor(palette.textPrimary);
        author.setTextSize(17);
        author.setTypeface(Fonts.semibold(this));
        author.setText("原作者：hik0w");
        row.addView(author);

        TextView repo = new TextView(this);
        repo.setTextColor(palette.trackOn);
        repo.setTextSize(14);
        repo.setText("GitHub: https://github.com/hik0w/TiktokPatchXposed");
        LinearLayout.LayoutParams repoLp = new LinearLayout.LayoutParams(-1, -2);
        repoLp.topMargin = dp(4);
        row.addView(repo, repoLp);

        TextView desc = new TextView(this);
        desc.setTextColor(palette.textSecondary);
        desc.setTextSize(13);
        desc.setText(I18n.get(S.CREDITS_DESC));
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(-1, -2);
        descLp.topMargin = dp(8);
        row.addView(desc, descLp);

        navigation(card, I18n.get(S.SOURCE_CODE), "hik0w/TiktokPatchXposed", view -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)));
            } catch (RuntimeException ignored) { }
        });

        page.footer("TiktokPatchXposed 優化版 · Based on hik0w/TiktokPatchXposed");
        return page.root;
    }

    private String currentLanguageDisplayName() {
        String lang = Prefs.text(Prefs.APP_LANGUAGE);
        if (lang == null || lang.isEmpty() || "auto".equals(lang)) {
            return I18n.get(S.LANG_FOLLOW_SYSTEM);
        }
        switch (lang) {
            case "zh-TW": return "繁體中文";
            case "zh": return "简体中文";
            case "en": return "English";
            case "ru": return "Русский";
            case "uk": return "Українська";
            case "de": return "Deutsch";
            case "es": return "Español";
            case "pt": return "Português";
            case "fr": return "Français";
            case "it": return "Italiano";
            case "pl": return "Polski";
            case "tr": return "Türkçe";
            case "id": return "Bahasa Indonesia";
            case "ar": return "العربية";
            default: return lang;
        }
    }

    private View languagePage() {
        Page page = new Page(I18n.get(S.APP_LANGUAGE_TITLE), true);
        LinearLayout card = page.card();
        String currentLang = Prefs.text(Prefs.APP_LANGUAGE);
        if (currentLang == null || currentLang.isEmpty()) currentLang = "auto";

        String[][] languages = {
            {"auto", I18n.get(S.LANG_FOLLOW_SYSTEM)},
            {"zh-TW", "繁體中文 (Traditional Chinese)"},
            {"zh", "简体中文 (Simplified Chinese)"},
            {"en", "English"},
            {"ru", "Русский (Russian)"},
            {"uk", "Українська (Ukrainian)"},
            {"de", "Deutsch (German)"},
            {"es", "Español (Spanish)"},
            {"fr", "Français (French)"},
            {"pt", "Português (Portuguese)"},
            {"it", "Italiano (Italian)"},
            {"pl", "Polski (Polish)"},
            {"tr", "Türkçe (Turkish)"},
            {"id", "Bahasa Indonesia"},
            {"ar", "العربية (Arabic)"}
        };

        for (String[] lang : languages) {
            final String code = lang[0];
            final String name = lang[1];
            boolean selected = code.equals(currentLang);

            LinearLayout row = row(card);
            TextView label = new TextView(this);
            label.setText(name);
            label.setTextColor(selected ? (palette.accent != 0 ? palette.accent : 0xFF2BB673) : palette.textPrimary);
            label.setTextSize(15);
            label.setTypeface(selected ? Fonts.semibold(this) : Fonts.regular(this));
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));

            if (selected) {
                TextView check = new TextView(this);
                check.setText("✓");
                check.setTextColor(palette.accent != 0 ? palette.accent : 0xFF2BB673);
                check.setTextSize(17);
                check.setTypeface(Fonts.semibold(this));
                row.addView(check);
            }

            row.setOnClickListener(v -> {
                String newCode = "auto".equals(code) ? "" : code;
                Prefs.setText(Prefs.APP_LANGUAGE, newCode);
                I18n.use(Locale.getDefault());
                recreate();
            });
        }

        page.footer(I18n.get(S.APP_LANGUAGE_DESC));
        return page.root;
    }

    private View hideTransparencyPage() {
        Page page = new Page(I18n.get(S.SECTION_HIDE_TRANSPARENCY), true);

        // 1. 隱藏項目 (All hide switches in one list)
        page.section(I18n.get(S.GROUP_HIDE_ITEMS));
        LinearLayout hideCard = page.card();
        toggle(hideCard, S.REMOVE_BLACK_EDGES, S.REMOVE_BLACK_EDGES_DESC, Prefs.REMOVE_BLACK_EDGES);
        toggle(hideCard, S.HIDE_LIVE_ICON, S.HIDE_LIVE_ICON_DESC, Prefs.HIDE_LIVE_ICON);
        toggle(hideCard, S.HIDE_PLUS_BUTTON, S.HIDE_PLUS_BUTTON_DESC, Prefs.HIDE_PLUS_BUTTON);
        toggle(hideCard, S.HIDE_BOTTOM_NAV, S.HIDE_BOTTOM_NAV_DESC, Prefs.HIDE_BOTTOM_NAV);
        toggle(hideCard, S.HIDE_TOP_NAV, S.HIDE_TOP_NAV_DESC, Prefs.HIDE_TOP_NAV);
        toggle(hideCard, S.HIDE_BUTTONS, S.HIDE_BUTTONS_DESC, Prefs.HIDE_BUTTONS);
        toggle(hideCard, S.HIDE_CAPTION, null, Prefs.HIDE_CAPTION);
        toggle(hideCard, S.HIDE_MUSIC, null, Prefs.HIDE_MUSIC);
        toggle(hideCard, S.HIDE_TAKO, S.HIDE_TAKO_DESC, Prefs.HIDE_TAKO);
        toggle(hideCard, S.HIDE_NAV_BAR, S.HIDE_NAV_BAR_DESC, Prefs.HIDE_NAV_BAR);
        toggle(hideCard, S.HIDE_STATUS_BAR, S.HIDE_STATUS_BAR_DESC, Prefs.HIDE_STATUS_BAR);

        // 2. 透明度調節 (All transparency sliders in one list)
        page.section(I18n.get(S.GROUP_TRANSPARENCY));
        LinearLayout transCard = page.card();
        addSliderItem(transCard, I18n.get(S.TOP_NAV_OPACITY), I18n.get(S.TOP_NAV_OPACITY_DESC), Prefs.TOP_NAV_OPACITY, 0, 100, 100);
        addSliderItem(transCard, I18n.get(S.BOTTOM_NAV_OPACITY), I18n.get(S.BOTTOM_NAV_OPACITY_DESC), Prefs.BOTTOM_NAV_OPACITY, 0, 100, 100);
        addSliderItem(transCard, I18n.get(S.OVERLAY_OPACITY), I18n.get(S.OVERLAY_OPACITY_DESC), Prefs.OVERLAY_OPACITY, 10, 100, 100);

        page.footer(I18n.get(S.HIDE_PARTS_FOOTER));
        return page.root;
    }

    private void addSliderItem(LinearLayout card, String title, String desc, String prefKey, int minVal, int maxVal, int defaultVal) {
        LinearLayout row = row(card);
        row.setBackground(null);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.START);
        row.setPadding(dp(16), dp(10), dp(16), dp(12));

        TextView labelView = new TextView(this);
        labelView.setTextColor(palette.textPrimary);
        labelView.setTextSize(15);
        labelView.setTypeface(Fonts.semibold(this));

        long current = Prefs.number(prefKey);
        int currentVal = current <= 0 && !Prefs.contains(prefKey) ? defaultVal : (int) Math.max(minVal, Math.min(maxVal, current));

        labelView.setText(title + " (" + currentVal + "%)");
        row.addView(labelView);

        android.widget.SeekBar slider = new android.widget.SeekBar(this);
        slider.setMax(maxVal - minVal);
        slider.setProgress(currentVal - minVal);
        slider.setProgressTintList(ColorStateList.valueOf(palette.trackOn));
        slider.setThumbTintList(ColorStateList.valueOf(palette.trackOn));

        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean user) {
                int val = progress + minVal;
                labelView.setText(title + " (" + val + "%)");
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar bar) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar bar) {
                Prefs.setNumber(prefKey, bar.getProgress() + minVal);
            }
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(6);
        row.addView(slider, params);

        if (desc != null && !desc.isEmpty()) {
            TextView descView = new TextView(this);
            descView.setTextColor(palette.textSecondary);
            descView.setTextSize(12);
            descView.setText(desc);
            LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(-1, -2);
            descParams.topMargin = dp(2);
            row.addView(descView, descParams);
        }
    }

    private View hidePage() {
        return hideTransparencyPage();
    }

    /** TikTok's own flame picture, the default of auto streaks. */
    private static final String FIRE_URL = "https://p16-tiktokcdn-com.akamaized.net/obj/im-resource-tiktok/fire.webp";

    /**
     * The streaks page: the switch, a preview of the chat as the friend will see it, then when,
     * what and to whom. Everything under the switch dims while it is off. Returns its sync.
     */
    private Runnable streaks(Page page) {
        toggle(page.card(), S.AUTO_STREAK, S.AUTO_STREAK_DESC, Prefs.AUTO_STREAK);

        LinearLayout preview = page.card();
        ((LinearLayout.LayoutParams) preview.getLayoutParams()).topMargin = dp(12);
        preview.setShowDividers(LinearLayout.SHOW_DIVIDER_NONE);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPaddingRelative(dp(14), dp(12), dp(14), dp(12));
        ImageView back = new ImageView(this);
        back.setImageDrawable(new Glyph(Glyph.Kind.BACK, palette.textSecondary, dp(20)));
        head.addView(back, new LinearLayout.LayoutParams(dp(20), dp(20)));
        View avatar = new View(this);
        GradientDrawable face = new GradientDrawable();
        face.setShape(GradientDrawable.OVAL);
        face.setColor(palette.trackOff);
        avatar.setBackground(face);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(30), dp(30));
        avatarParams.setMarginStart(dp(10));
        head.addView(avatar, avatarParams);
        TextView name = new TextView(this);
        name.setText(I18n.get(S.STREAK_PREVIEW_NAME));
        name.setTextColor(palette.textPrimary);
        name.setTextSize(15);
        name.setTypeface(Fonts.semibold(this));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-2, -2);
        nameParams.setMarginStart(dp(10));
        head.addView(name, nameParams);
        TextView days = new TextView(this);
        days.setText("125");
        days.setTextColor(palette.textSecondary);
        days.setTextSize(13);
        days.setTypeface(Fonts.semibold(this));
        days.setCompoundDrawablePadding(dp(3));
        Glyph flame = new Glyph(Glyph.Kind.FLAME, palette.textSecondary, dp(14));
        flame.setBounds(0, 0, dp(14), dp(14));
        days.setCompoundDrawablesRelative(flame, null, null, null);
        LinearLayout.LayoutParams daysParams = new LinearLayout.LayoutParams(-2, -2);
        daysParams.setMarginStart(dp(8));
        head.addView(days, daysParams);
        preview.addView(head);
        View rule = new View(this);
        rule.setBackgroundColor(palette.dark ? 0x14FFFFFF : 0x14000000);
        preview.addView(rule, new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f))));
        TextView stamp = new TextView(this);
        stamp.setTextColor(palette.textTertiary);
        stamp.setTextSize(12);
        stamp.setTypeface(Fonts.regular(this));
        stamp.setGravity(Gravity.CENTER);
        stamp.setPadding(dp(16), dp(12), dp(16), dp(8));
        preview.addView(stamp, new LinearLayout.LayoutParams(-1, -2));
        ImageView sticker = new ImageView(this);
        sticker.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams stickerParams = new LinearLayout.LayoutParams(dp(92), dp(92));
        stickerParams.gravity = Gravity.END;
        stickerParams.setMarginEnd(dp(16));
        stickerParams.bottomMargin = dp(16);
        preview.addView(sticker, stickerParams);

        page.section(I18n.get(S.STREAK_SETTINGS));
        LinearLayout settings = page.card();
        LinearLayout timeRow = row(settings);
        texts(timeRow, I18n.get(S.STREAK_TIME), null);
        TextView chip = new TextView(this);
        chip.setTextColor(palette.textPrimary);
        chip.setTextSize(15);
        chip.setTypeface(Fonts.semibold(this));
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(10), dp(4), dp(10), dp(4));
        chip.setBackground(rounded(palette.pressed, dp(8)));
        timeRow.addView(chip, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(4), dp(16), dp(14));
        panel.setVisibility(View.GONE);
        settings.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout wheels = new LinearLayout(this);
        int stored = StreakTiming.minuteOfDay(Prefs.number(Prefs.STREAK_TIME));
        int initial = stored < 0 ? 21 * 60 : stored;
        android.widget.NumberPicker hours = wheel(23, initial / 60);
        android.widget.NumberPicker minutes = wheel(59, initial % 60);
        android.widget.NumberPicker.OnValueChangeListener changed = (picker, old, value) -> {
            picker.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            Prefs.setNumber(Prefs.STREAK_TIME, 1 + hours.getValue() * 60L + minutes.getValue());
        };
        hours.setOnValueChangedListener(changed);
        minutes.setOnValueChangedListener(changed);
        wheels.addView(hours, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams minuteParams = new LinearLayout.LayoutParams(0, -2, 1);
        minuteParams.setMarginStart(dp(12));
        wheels.addView(minutes, minuteParams);
        View modes = segmented(new String[]{I18n.get(S.STREAK_WHEN_FADING), I18n.get(S.STREAK_WHEN_TIME)},
                () -> Prefs.number(Prefs.STREAK_TIME) > 0 ? 1 : 0, index -> Prefs.setNumber(Prefs.STREAK_TIME,
                        index == 0 ? 0 : 1 + hours.getValue() * 60L + minutes.getValue()));
        panel.addView(modes, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams wheelsParams = new LinearLayout.LayoutParams(-1, -2);
        wheelsParams.topMargin = dp(10);
        panel.addView(wheels, wheelsParams);
        timeRow.setOnClickListener(view -> {
            if (motion()) android.transition.TransitionManager.beginDelayedTransition(page.content);
            panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });

        TextView what = navigation(settings, I18n.get(S.STREAK_SEND), stickerLabel(), view -> openSheet(stickerSheet()));
        TextView whom = navigation(settings, I18n.get(S.STREAK_RECIPIENTS), recipientsLabel(), view -> openSheet(recipientsSheet()));
        page.footer(I18n.get(S.STREAK_TIME_NOTE));

        // "Selected: N" needs the streak chats; read them once, quietly, while the page opens.
        new Thread(() -> {
            List<AutoStreakManager.Partner> partners = AutoStreakManager.partners();
            if (partners != null) runOnUiThread(() -> {
                lastPartners = partners;
                whom.setText(recipientsLabel());
            });
        }, "tpx-streak-count").start();
        String[] shownSticker = {null};
        return () -> {
            int minute = StreakTiming.minuteOfDay(Prefs.number(Prefs.STREAK_TIME));
            String time = minute < 0 ? null : String.format(Locale.US, "%02d:%02d", minute / 60, minute % 60);
            chip.setText(time == null ? I18n.get(S.STREAK_AUTO) : time);
            stamp.setText(time == null ? I18n.get(S.STREAK_PREVIEW_FADING) : String.format(I18n.get(S.STREAK_TODAY), time));
            wheels.setVisibility(minute < 0 ? View.GONE : View.VISIBLE);
            if (modes.getTag() instanceof Runnable) ((Runnable) modes.getTag()).run();
            what.setText(stickerLabel());
            whom.setText(recipientsLabel());
            String id = StreakSticker.chosen();
            String key = id == null ? "fire" : id;
            if (!key.equals(shownSticker[0])) {
                shownSticker[0] = key;
                sticker.setImageDrawable(null);
                String url = Prefs.text(Prefs.STREAK_STICKER_URL);
                loadPicture(sticker, key, id == null || url.isEmpty() ? java.util.Collections.singletonList(FIRE_URL)
                        : java.util.Collections.singletonList(url));
            }
            boolean on = Prefs.on(Prefs.AUTO_STREAK);
            for (View part : new View[]{preview, settings}) {
                part.animate().alpha(on ? 1f : 0.35f).setDuration(motion() ? 180 : 0).start();
                enable(part, on);
            }
        };
    }

    private void enable(View view, boolean on) {
        view.setEnabled(on);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) enable(group.getChildAt(index), on);
        }
    }

    /** A wheel of 0..max shown as two digits, in the page's colors. */
    private android.widget.NumberPicker wheel(int max, int value) {
        android.widget.NumberPicker picker = new android.widget.NumberPicker(this);
        picker.setMinValue(0);
        picker.setMaxValue(max);
        picker.setValue(value);
        picker.setFormatter(number -> String.format(Locale.US, "%02d", number));
        picker.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        picker.setBackground(rounded(palette.pressed, dp(12)));
        if (Build.VERSION.SDK_INT >= 29) {
            picker.setTextColor(palette.textPrimary);
            picker.setSelectionDividerHeight(0);
        }
        return picker;
    }

    /**
     * Two or more options in one pill; the chosen one sits on a lighter pill. The view's tag is a
     * Runnable that redraws it from {@code current}.
     */
    private View segmented(String[] labels, java.util.function.IntSupplier current, java.util.function.IntConsumer pick) {
        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(dp(3), dp(3), dp(3), dp(3));
        bar.setBackground(rounded(palette.pressed, dp(10)));
        List<TextView> items = new ArrayList<>();
        Runnable render = () -> {
            int chosen = current.getAsInt();
            for (int index = 0; index < items.size(); index++) {
                TextView item = items.get(index);
                boolean on = index == chosen;
                item.setBackground(on ? rounded(palette.trackOff, dp(8)) : null);
                item.setTextColor(on ? palette.textPrimary : palette.textSecondary);
                item.setTypeface(on ? Fonts.semibold(this) : Fonts.regular(this));
                item.setSelected(on);
            }
        };
        for (int index = 0; index < labels.length; index++) {
            TextView item = new TextView(this);
            item.setText(labels[index]);
            item.setTextSize(14);
            item.setGravity(Gravity.CENTER);
            item.setSingleLine(true);
            item.setEllipsize(TextUtils.TruncateAt.END);
            item.setPadding(dp(8), dp(8), dp(8), dp(8));
            int chosen = index;
            item.setOnClickListener(view -> {
                if (!view.isEnabled() || current.getAsInt() == chosen) return;
                view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                pick.accept(chosen);
                render.run();
            });
            bar.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
            items.add(item);
        }
        render.run();
        bar.setTag(render);
        return bar;
    }

    private String stickerLabel() {
        if (StreakSticker.chosen() == null) return I18n.get(S.STREAK_FLAME);
        String title = Prefs.text(Prefs.STREAK_STICKER_TITLE);
        return title == null || title.isEmpty() ? I18n.get(S.STREAK_STICKER) : title;
    }

    /** Title and a quiet line under it, at the top of a bottom sheet. */
    private void sheetHeader(LinearLayout content, String title, String description) {
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(palette.textPrimary);
        heading.setTextSize(18);
        heading.setTypeface(Fonts.bold(this));
        heading.setPadding(dp(18), 0, dp(18), 0);
        content.addView(heading);
        TextView line = new TextView(this);
        line.setText(description);
        line.setTextColor(palette.textSecondary);
        line.setTextSize(13.5f);
        line.setTypeface(Fonts.regular(this));
        line.setPadding(dp(18), dp(4), dp(18), dp(12));
        content.addView(line);
    }

    /** Puts {@code body} in a scroll view no taller than about half the screen. */
    private View capped(View body) {
        ScrollView scroll = new ScrollView(this) {
            @Override protected void onMeasure(int width, int height) {
                int cap = (int) (getResources().getDisplayMetrics().heightPixels * 0.5f);
                super.onMeasure(width, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    private String recipientsLabel() {
        if (!Prefs.on(Prefs.STREAK_ONLY_SELECTED)) return I18n.get(S.STREAK_EVERYONE);
        List<AutoStreakManager.Partner> known = lastPartners;
        if (known == null) return I18n.get(S.STREAK_MODE_SELECTED);
        java.util.Set<String> excluded = AutoStreakManager.excluded();
        int count = 0;
        for (AutoStreakManager.Partner partner : known) if (!excluded.contains(partner.conversation)) count++;
        return String.format(locale, I18n.get(S.STREAK_SELECTED), count);
    }

    /** Streak chats as last read for the recipients sheet; the row's "Selected: N" counts them. */
    private volatile List<AutoStreakManager.Partner> lastPartners;

    /** Everyone, or only the checked chats; changes are kept on "Save". */
    private View recipientsSheet() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        sheetHeader(content, I18n.get(S.STREAK_RECIPIENTS_TITLE), I18n.get(S.STREAK_RECIPIENTS_DESC));
        boolean[] some = {Prefs.on(Prefs.STREAK_ONLY_SELECTED)};
        java.util.Set<String> excluded = new java.util.HashSet<>();
        for (String id : Prefs.text(Prefs.STREAK_EXCLUDED).split(",")) if (!id.trim().isEmpty()) excluded.add(id.trim());
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        Runnable dim = () -> {
            list.setAlpha(some[0] ? 1f : 0.45f);
            enable(list, some[0]);
        };
        View modes = segmented(new String[]{I18n.get(S.STREAK_MODE_ALL), I18n.get(S.STREAK_MODE_SELECTED)},
                () -> some[0] ? 1 : 0, index -> {
                    some[0] = index == 1;
                    dim.run();
                });
        LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(-1, -2);
        modeParams.setMargins(dp(16), 0, dp(16), dp(8));
        content.addView(modes, modeParams);
        content.addView(capped(list));
        TextView status = new TextView(this);
        status.setText(I18n.get(S.STREAK_LOADING));
        status.setTextColor(palette.textSecondary);
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(24), dp(16), dp(24), dp(16));
        list.addView(status);
        dim.run();
        new Thread(() -> {
            List<AutoStreakManager.Partner> partners = AutoStreakManager.partners();
            java.util.Map<String, StreakPeople.Person> people = partners == null ? null : StreakPeople.lookup(partners);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (partners == null || partners.isEmpty()) {
                    status.setText(I18n.get(S.STREAK_RECIPIENTS_EMPTY));
                    return;
                }
                lastPartners = partners;
                list.removeAllViews();
                for (AutoStreakManager.Partner partner : partners) {
                    StreakPeople.Person person = people == null ? null : people.get(partner.peer);
                    recipientRow(list, partner, person, excluded);
                }
                dim.run();
            });
        }, "tpx-streak-partners").start();
        TextView save = new TextView(this);
        save.setText(I18n.get(S.STREAK_SAVE));
        save.setTextColor(palette.background);
        save.setTextSize(16);
        save.setTypeface(Fonts.semibold(this));
        save.setGravity(Gravity.CENTER);
        save.setPadding(0, dp(13), 0, dp(13));
        save.setBackground(rounded(palette.textPrimary, dp(14)));
        save.setOnClickListener(view -> {
            Prefs.setText(Prefs.STREAK_EXCLUDED, String.join(",", excluded));
            Prefs.set(Prefs.STREAK_ONLY_SELECTED, some[0]);
            closeSheet();
        });
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-1, -2);
        saveParams.setMargins(dp(16), dp(12), dp(16), 0);
        content.addView(save, saveParams);
        return content;
    }

    /** Avatar, name and streak length, with a check box; unchecking leaves the chat out. */
    private void recipientRow(LinearLayout list, AutoStreakManager.Partner partner, StreakPeople.Person person,
            java.util.Set<String> excluded) {
        LinearLayout row = row(list);
        row.setBackground(null);
        ImageView avatar = new ImageView(this);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable face = new GradientDrawable();
        face.setShape(GradientDrawable.OVAL);
        face.setColor(palette.trackOff);
        avatar.setBackground(face);
        avatar.setClipToOutline(true);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(38), dp(38));
        avatarParams.setMarginEnd(dp(12));
        row.addView(avatar, avatarParams);
        if (person != null && person.avatar != null) {
            loadPicture(avatar, "avatar_" + partner.peer, java.util.Collections.singletonList(person.avatar));
        }
        String name = person != null && person.name != null && !person.name.isEmpty() ? person.name
                : person != null && person.handle != null ? "@" + person.handle : "id " + partner.peer;
        LinearLayout texts = texts(row, name, String.valueOf(partner.days));
        TextView days = (TextView) texts.getChildAt(texts.getChildCount() - 1);
        Glyph flame = new Glyph(Glyph.Kind.FLAME, palette.textSecondary, dp(13));
        flame.setBounds(0, 0, dp(13), dp(13));
        days.setCompoundDrawablesRelative(flame, null, null, null);
        days.setCompoundDrawablePadding(dp(3));
        ImageView box = new ImageView(this);
        Runnable draw = () -> box.setImageDrawable(checkBox(!excluded.contains(partner.conversation)));
        draw.run();
        row.addView(box, new LinearLayout.LayoutParams(dp(22), dp(22)));
        row.setOnClickListener(view -> {
            if (!view.isEnabled()) return;
            view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            if (!excluded.remove(partner.conversation)) excluded.add(partner.conversation);
            draw.run();
        });
    }

    private android.graphics.drawable.Drawable checkBox(boolean on) {
        GradientDrawable square = new GradientDrawable();
        square.setCornerRadius(dp(6));
        square.setSize(dp(22), dp(22));
        if (!on) {
            square.setStroke(dp(2), palette.textTertiary);
            return square;
        }
        square.setColor(palette.accent);
        Glyph check = new Glyph(Glyph.Kind.CHECK, palette.background, dp(16));
        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{square, check});
        layers.setLayerInset(1, dp(3), dp(3), dp(3), dp(3));
        return layers;
    }

    /** What auto streaks send: TikTok's flame first, then the user's favourite stickers. */
    private View stickerSheet() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        sheetHeader(content, I18n.get(S.STREAK_SEND), I18n.get(S.STREAK_STICKER_DESC));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        content.addView(capped(body));
        List<StreakSticker.Choice> shown = new ArrayList<>();
        shown.add(new StreakSticker.Choice("", I18n.get(S.STREAK_FLAME), java.util.Collections.singletonList(FIRE_URL)));
        stickerGrid(body, shown);
        TextView status = new TextView(this);
        status.setText(I18n.get(S.STREAK_LOADING));
        status.setTextColor(palette.textSecondary);
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(16), dp(12), dp(16), dp(12));
        body.addView(status);
        new Thread(() -> {
            List<StreakSticker.Choice> stickers = StreakSticker.favourites();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (stickers == null || stickers.isEmpty()) {
                    status.setText(I18n.get(stickers == null ? S.STREAK_OUTSIDE : S.STREAK_NONE));
                    return;
                }
                body.removeAllViews();
                shown.addAll(stickers);
                stickerGrid(body, shown);
            });
        }, "tpx-favourite-stickers").start();
        return content;
    }

    /** Four stickers a row; the chosen one is framed and marked, a tap picks and closes. */
    private void stickerGrid(LinearLayout card, List<StreakSticker.Choice> stickers) {
        String chosen = StreakSticker.chosen() == null ? "" : StreakSticker.chosen();
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(12), 0, dp(12), dp(4));
        card.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout line = null;
        for (int index = 0; index < stickers.size(); index++) {
            if (index % 4 == 0) {
                line = new LinearLayout(this);
                grid.addView(line, new LinearLayout.LayoutParams(-1, dp(84)));
            }
            StreakSticker.Choice sticker = stickers.get(index);
            boolean selected = sticker.id.equals(chosen);
            FrameLayout cell = new FrameLayout(this);
            GradientDrawable frame = new GradientDrawable();
            frame.setCornerRadius(dp(14));
            frame.setColor(palette.pressed);
            if (selected) frame.setStroke(dp(2), palette.accent);
            cell.setBackground(frame);
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cell.addView(image, new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER));
            loadPicture(image, sticker.id.isEmpty() ? "fire" : sticker.id, sticker.previews);
            if (selected) {
                ImageView mark = new ImageView(this);
                mark.setImageDrawable(new Glyph(Glyph.Kind.CHECK, palette.background, dp(12)));
                mark.setScaleType(ImageView.ScaleType.CENTER);
                GradientDrawable dot = new GradientDrawable();
                dot.setShape(GradientDrawable.OVAL);
                dot.setColor(palette.accent);
                mark.setBackground(dot);
                FrameLayout.LayoutParams markParams = new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP | Gravity.END);
                markParams.setMargins(0, dp(5), dp(5), 0);
                cell.addView(mark, markParams);
            }
            cell.setContentDescription(sticker.title);
            cell.setOnClickListener(view -> view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).withEndAction(() -> {
                Prefs.setText(Prefs.STREAK_STICKER, sticker.id);
                Prefs.setText(Prefs.STREAK_STICKER_TITLE, sticker.id.isEmpty() ? "" : sticker.title);
                Prefs.setText(Prefs.STREAK_STICKER_URL, sticker.id.isEmpty() || sticker.previews.isEmpty() ? "" : sticker.previews.get(0));
                closeSheet();
            }).start());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(76), 1);
            params.setMargins(dp(4), dp(4), dp(4), dp(4));
            line.addView(cell, params);
        }
        while (line != null && line.getChildCount() < 4) line.addView(new View(this), new LinearLayout.LayoutParams(0, dp(76), 1));
    }

    /** A few at a time, so a long favourites list does not open dozens of connections at once. */
    private static final java.util.concurrent.ExecutorService PICTURES = java.util.concurrent.Executors.newFixedThreadPool(4);
    /** Sticker pictures already fetched in this session, by sticker id. */
    private static final android.util.LruCache<String, byte[]> PICTURE_CACHE = new android.util.LruCache<String, byte[]>(8 << 20) {
        @Override protected int sizeOf(String key, byte[] value) { return value.length; }
    };

    /**
     * A sticker preview: from memory, then from TikTok's cache folder, then from the first mirror
     * that answers (and kept for next time). Decoded at cell size; animated ones play.
     */
    private void loadPicture(ImageView view, String id, java.util.List<String> urls) {
        java.io.File file = new java.io.File(new java.io.File(getCacheDir(), "tpx_stickers"), id.replaceAll("[^0-9A-Za-z_-]", "_"));
        PICTURES.execute(() -> {
            byte[] bytes = PICTURE_CACHE.get(id);
            if (bytes == null && file.isFile()) {
                try {
                    bytes = java.nio.file.Files.readAllBytes(file.toPath());
                } catch (java.io.IOException ignored) {
                    bytes = null;
                }
            }
            if (bytes == null) {
                bytes = fetch(urls);
                if (bytes == null) return;
                try {
                    java.io.File folder = file.getParentFile();
                    if (folder != null && (folder.isDirectory() || folder.mkdirs())) java.nio.file.Files.write(file.toPath(), bytes);
                } catch (java.io.IOException ignored) {
                    // Shown anyway; fetched again next time.
                }
            }
            PICTURE_CACHE.put(id, bytes);
            try {
                int limit = dp(72);
                android.graphics.drawable.Drawable picture = android.graphics.ImageDecoder.decodeDrawable(
                        android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)),
                        (decoder, info, source) -> {
                            int width = info.getSize().getWidth();
                            int height = info.getSize().getHeight();
                            float scale = Math.min(1f, (float) limit / Math.max(width, height));
                            decoder.setTargetSize(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
                        });
                runOnUiThread(() -> {
                    view.setImageDrawable(picture);
                    if (picture instanceof android.graphics.drawable.AnimatedImageDrawable) {
                        ((android.graphics.drawable.AnimatedImageDrawable) picture).start();
                    }
                });
            } catch (Exception broken) {
                PICTURE_CACHE.remove(id);
                file.delete();
            }
        });
    }

    private byte[] fetch(java.util.List<String> urls) {
        for (String url : urls) {
            if (isFinishing()) return null;
            java.net.HttpURLConnection connection = null;
            try {
                connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                connection.setConnectTimeout(8_000);
                connection.setReadTimeout(12_000);
                if (connection.getResponseCode() != 200) continue;
                try (java.io.InputStream input = connection.getInputStream();
                     java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                    byte[] buffer = new byte[16 * 1024];
                    for (int read; (read = input.read(buffer)) > 0; ) output.write(buffer, 0, read);
                    if (output.size() > 0) return output.toByteArray();
                }
            } catch (Exception ignored) {
                // Next mirror.
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        return null;
    }

    private View excludePage() {
        Page page = new Page(I18n.get(S.TRANSLATE_EXCLUDE), true);
        textField(page.card(), Prefs.TRANSLATE_EXCLUDE, I18n.get(S.TRANSLATE_EXCLUDE_HINT));
        return page.root;
    }

    /** Speeds offered in TikTok's own speed menu. */
    private View speedsPage() {
        Page page = new Page(I18n.get(S.SPEEDS), true);
        textField(page.card(), Prefs.SPEEDS, "0.75, 1, 1.25, 1.5, 2, 3");
        page.footer(I18n.get(S.SPEEDS_FOOTER));
        return page.root;
    }

    /** The user's own filter: words and hashtags, authors, minimum likes and views. */
    private View filterPage() {
        Page page = new Page(I18n.get(S.CUSTOM_FILTER), true);
        page.section(I18n.get(S.FILTER_WORDS));
        textField(page.card(), Prefs.FILTER_WORDS, I18n.get(S.FILTER_WORDS_HINT));
        page.section(I18n.get(S.FILTER_AUTHORS));
        textField(page.card(), Prefs.FILTER_AUTHORS, I18n.get(S.FILTER_AUTHORS_HINT));
        page.section(I18n.get(S.FILTER_MIN));
        LinearLayout minimum = page.card();
        numberRow(minimum, I18n.get(S.FILTER_MIN_LIKES), Prefs.FILTER_MIN_LIKES);
        numberRow(minimum, I18n.get(S.FILTER_MIN_VIEWS), Prefs.FILTER_MIN_VIEWS);
        page.footer(I18n.get(S.FILTER_FOOTER));
        return page.root;
    }

    private void textField(LinearLayout card, String key, String hint) {
        EditText field = new EditText(this);
        field.setBackground(null);
        field.setText(Prefs.text(key));
        field.setHint(hint);
        field.setHintTextColor(palette.textSecondary);
        field.setTextColor(palette.textPrimary);
        field.setTextSize(16);
        field.setTypeface(Fonts.regular(this));
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setMinLines(2);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setPadding(dp(16), dp(12), dp(16), dp(12));
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { Prefs.setText(key, s.toString()); }
        });
        card.addView(field, new LinearLayout.LayoutParams(-1, -2));
    }

    private void numberRow(LinearLayout card, String title, String key) {
        LinearLayout row = row(card);
        row.setBackground(null);
        texts(row, title, null);
        EditText field = new EditText(this);
        field.setBackground(rounded(palette.field, dp(10)));
        long value = Prefs.number(key);
        if (value > 0) field.setText(String.valueOf(value));
        field.setHint("0");
        field.setHintTextColor(palette.textSecondary);
        field.setTextColor(palette.textPrimary);
        field.setTextSize(16);
        field.setGravity(Gravity.CENTER);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(10)});
        field.setPadding(dp(10), dp(6), dp(10), dp(6));
        field.setMinWidth(dp(96));
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                long parsed;
                try {
                    parsed = s.length() == 0 ? 0 : Long.parseLong(s.toString());
                } catch (NumberFormatException error) {
                    parsed = 0;
                }
                Prefs.setNumber(key, parsed);
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMarginStart(dp(12));
        row.addView(field, params);
        row.setOnClickListener(view -> field.requestFocus());
    }

    private void buildRegion(Page page, LinearLayout card) {
        TextView regionValue = navigation(card, I18n.get(S.REGION), regionLabel(),
                view -> push(regionPage(), true));
        View languageRow = toggle(card, S.REGION_LANGUAGE, S.REGION_LANGUAGE_DESC, Prefs.REGION_LANGUAGE);
        languageRow.setVisibility(Prefs.region().isEmpty() ? View.GONE : View.VISIBLE);
        page.footer(I18n.get(S.REGION_DESC));
        regionValue.setCompoundDrawablePadding(dp(8));
        Runnable sync = () -> {
            regionValue.setText(regionLabel());
            String flag = RegionFlag.of(Prefs.region());
            regionValue.setCompoundDrawablesRelative(flag == null ? null : new FlagDot(flag, dp(18)), null, null, null);
            languageRow.setVisibility(Prefs.region().isEmpty() ? View.GONE : View.VISIBLE);
        };
        sync.run();
        page.root.setTag(sync);
    }

    /**
     * A group of rows: one quiet card with hairlines between its rows, so a switch always sits
     * inside a shape rather than floating on the page.
     */
    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(palette.card, dp(16)));
        card.setClipToOutline(true);
        GradientDrawable line = new GradientDrawable();
        line.setColor(palette.hairline);
        line.setSize(0, Math.max(1, dp(0.5f)));
        card.setDividerDrawable(new android.graphics.drawable.InsetDrawable(line, dp(18), 0, 0, 0));
        card.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(16), 0, dp(16), 0);
        parent.addView(card, params);
        return card;
    }

    private View regionPage() {
        Page page = new Page(I18n.get(S.REGION), false);
        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setPadding(dp(12), 0, dp(12), 0);
        searchBox.setBackground(rounded(palette.field, dp(8)));
        ImageView glass = new ImageView(this);
        glass.setImageDrawable(new Glyph(Glyph.Kind.SEARCH, palette.textSecondary, dp(18)));
        searchBox.addView(glass, new LinearLayout.LayoutParams(dp(18), dp(18)));
        EditText search = new EditText(this);
        search.setBackground(null);
        search.setHint(I18n.get(S.SEARCH));
        search.setHintTextColor(palette.textSecondary);
        search.setTextColor(palette.textPrimary);
        search.setTextSize(16);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPaddingRelative(dp(8), 0, 0, 0);
        searchBox.addView(search, new LinearLayout.LayoutParams(0, dp(40), 1));
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(-1, dp(40));
        boxParams.setMargins(dp(16), dp(8), dp(16), dp(12));
        page.content.addView(searchBox, boxParams);

        LinearLayout list = page.card();
        List<View> rows = new ArrayList<>();
        List<String> names = new ArrayList<>();
        String selected = Prefs.region();
        View off = choice(list, I18n.get(S.REGION_OFF), selected.isEmpty(), () -> Prefs.setRegion(""));
        leadingIcon(off, new Glyph(Glyph.Kind.GLOBE, palette.textSecondary, dp(22)));
        rows.add(off);
        names.add("");
        for (Regions.Region region : Regions.sorted(locale)) {
            String name = region.name(locale);
            View row = choice(list, name, region.iso.equals(selected), () -> Prefs.setRegion(region.iso));
            String flag = RegionFlag.of(region.iso);
            if (flag != null) leadingIcon(row, new FlagDot(flag, dp(22)));
            rows.add(row);
            names.add(name.toLowerCase(locale) + " " + region.iso + " " + region.name(Locale.ENGLISH).toLowerCase(Locale.ROOT));
        }
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                String query = s.toString().trim().toLowerCase(locale);
                for (int index = 0; index < rows.size(); index++) {
                    boolean visible = query.isEmpty() ? true : index > 0 && names.get(index).contains(query);
                    rows.get(index).setVisibility(visible ? View.VISIBLE : View.GONE);
                }
            }
        });
        return page.root;
    }

    /** Preset periods plus a custom number of days. */
    private View agePage() {
        Page page = new Page(I18n.get(S.OLD_AGE), false);
        LinearLayout presets = page.card();
        int current = Prefs.oldVideoDays();
        boolean custom = true;
        for (int index = 0; index < AGE_DAYS.length; index++) {
            int days = AGE_DAYS[index];
            if (days == current) custom = false;
            choice(presets, I18n.get(AGE_NAMES[index]), days == current, () -> Prefs.setOldVideoDays(days));
        }

        page.section(I18n.get(S.AGE_CUSTOM));
        LinearLayout card = page.card();
        LinearLayout row = row(card);
        row.setBackground(null);
        EditText field = new EditText(this);
        field.setBackground(null);
        field.setPadding(0, 0, 0, 0);
        field.setHint(I18n.get(S.AGE_DAYS_HINT));
        field.setHintTextColor(palette.textSecondary);
        field.setTextColor(palette.textPrimary);
        field.setTextSize(17);
        field.setTypeface(Fonts.semibold(this));
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        if (custom) field.setText(String.valueOf(current));
        row.addView(field, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView check = new ImageView(this);
        check.setImageDrawable(new Glyph(Glyph.Kind.CHECK, palette.accent, dp(22)));
        check.setVisibility(custom ? View.VISIBLE : View.INVISIBLE);
        row.addView(check, new LinearLayout.LayoutParams(dp(22), dp(22)));
        row.setOnClickListener(view -> field.requestFocus());
        field.setOnEditorActionListener((view, action, event) -> {
            int days;
            try {
                days = Integer.parseInt(field.getText().toString().trim());
            } catch (NumberFormatException error) {
                days = 0;
            }
            if (days > 0) {
                Prefs.setOldVideoDays(days);
                pop();
            }
            return true;
        });
        return page.root;
    }

    private String ageLabel(int days) {
        for (int index = 0; index < AGE_DAYS.length; index++) {
            if (AGE_DAYS[index] == days) return I18n.get(AGE_NAMES[index]);
        }
        return String.format(locale, I18n.get(S.AGE_DAYS), days);
    }

    private String regionLabel() {
        Regions.Region region = Regions.find(Prefs.region());
        return region == null ? I18n.get(S.OFF) : region.name(locale);
    }

    // ---- rows ------------------------------------------------------------------------------

    private View toggle(LinearLayout card, S title, S description, String key) {
        LinearLayout row = row(card);
        LinearLayout texts = texts(row, I18n.get(title), description == null ? null : I18n.get(description));
        if (java.util.Arrays.asList(BETA_KEYS).contains(key)) chip(texts, "Beta", false);
        if (java.util.Arrays.asList(Prefs.RESTART_KEYS).contains(key)) chip(texts, "↻", true);
        Toggle toggle = new Toggle(this, palette);
        toggle.setChecked(Prefs.on(key), false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMarginStart(dp(12));
        row.addView(toggle, params);
        row.setContentDescription(texts.getContentDescription());
        texts.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        // The switch reacts the moment the finger lands, not when it lifts.
        row.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    toggle.press(true);
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    toggle.press(false);
                    break;
                default:
                    break;
            }
            return false;
        });
        row.setOnClickListener(view -> {
            boolean value = !toggle.checked();
            toggle.setChecked(value, true);
            // The tick lands on the same frame the thumb starts to move.
            if (Build.VERSION.SDK_INT >= 34) {
                row.performHapticFeedback(value ? android.view.HapticFeedbackConstants.TOGGLE_ON
                        : android.view.HapticFeedbackConstants.TOGGLE_OFF);
            } else {
                row.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            }
            Prefs.set(key, value);
            row.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);
        });
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Switch");
                info.setCheckable(true);
                info.setChecked(toggle.checked());
            }
        });
        return row;
    }

    private TextView navigation(LinearLayout card, String title, String value, View.OnClickListener click) {
        LinearLayout row = row(card);
        texts(row, title, null);
        TextView end = new TextView(this);
        end.setTextColor(palette.textSecondary);
        end.setTextSize(16);
        end.setTypeface(Fonts.regular(this));
        end.setSingleLine(true);
        end.setEllipsize(TextUtils.TruncateAt.END);
        end.setMaxWidth(dp(180));
        if (value != null) end.setText(value);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(-2, -2);
        valueParams.setMarginStart(dp(12));
        row.addView(end, valueParams);
        if (click != null) {
            ImageView chevron = new ImageView(this);
            chevron.setImageDrawable(new Glyph(Glyph.Kind.CHEVRON, palette.chevron, dp(20)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(20), dp(20));
            params.setMarginStart(dp(4));
            row.addView(chevron, params);
            row.setOnClickListener(click);
        } else {
            row.setClickable(false);
            row.setBackground(null);
        }
        return end;
    }

    /** A 22dp icon before a row's text, such as a country's round flag. */
    private void leadingIcon(View row, android.graphics.drawable.Drawable icon) {
        ImageView view = new ImageView(this);
        view.setImageDrawable(icon);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(22), dp(22));
        params.setMarginEnd(dp(14));
        ((LinearLayout) row).addView(view, 0, params);
    }

    private View choice(LinearLayout card, String title, boolean selected, Runnable select) {
        LinearLayout row = row(card);
        row.setMinimumHeight(dp(52));
        texts(row, title, null);
        ImageView check = new ImageView(this);
        check.setImageDrawable(new Glyph(Glyph.Kind.CHECK, palette.accent, dp(22)));
        check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        row.addView(check, new LinearLayout.LayoutParams(dp(22), dp(22)));
        row.setSelected(selected);
        row.setOnClickListener(view -> {
            select.run();
            hideKeyboard(view);
            pop();
        });
        return row;
    }

    private LinearLayout row(LinearLayout card) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(54));
        row.setPadding(dp(18), dp(11), dp(16), dp(11));
        row.setClipToPadding(false);
        // The card clips the press wash to its rounded corners.
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.pressed), null,
                new ColorDrawable(0xFFFFFFFF)));
        row.setClickable(true);
        row.setFocusable(true);
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    /** An option of the row above it: indented under its parent's title, nothing more. */
    private void subOption(View row) {
        row.setPaddingRelative(dp(34), row.getPaddingTop(), row.getPaddingEnd(), row.getPaddingBottom());
    }

    /** Settings still being tested on real accounts; they carry a "Beta" mark. */
    private static final String[] BETA_KEYS = {Prefs.NO_LOGIN_WALL, Prefs.BLOCK_LOCATION, Prefs.HIDE_VPN, Prefs.SPOOF_ANDROID_ID,
            Prefs.UNMUTE, Prefs.SELF_REPOST, Prefs.HD_UPLOAD, Prefs.GHOST_ONLINE, Prefs.GHOST_READ,
            Prefs.COMMENT_PHOTOS, Prefs.NO_HASHTAG_LIMIT, Prefs.CAMERA_EXTRAS};

    /**
     * A small mark after a row's title: an outlined "Beta" for settings under test, a plain
     * restart glyph for those that apply after TikTok restarts. Both stay in the text colors.
     */
    private void chip(LinearLayout texts, String label, boolean warm) {
        View first = texts.getChildAt(0);
        LinearLayout line;
        if (first instanceof LinearLayout) {
            line = (LinearLayout) first;
        } else {
            texts.removeView(first);
            line = new LinearLayout(this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            // Wrap-content line with a weighted title: the chip sits right after a short title,
            // and a long title gives up width (and wraps) instead of pushing the chip out.
            line.addView(first, new LinearLayout.LayoutParams(-2, -2, 1));
            texts.addView(line, 0, new LinearLayout.LayoutParams(-2, -2));
        }
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(warm ? 13 : 10.5f);
        chip.setTypeface(Fonts.regular(this));
        chip.setTextColor(warm ? palette.textTertiary : palette.textSecondary);
        if (!warm) {
            GradientDrawable outline = rounded(0, dp(9));
            outline.setStroke(Math.max(1, dp(0.75f)), palette.textTertiary);
            chip.setBackground(outline);
            chip.setPadding(dp(6), 0, dp(6), dp(1));
        }
        chip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMarginStart(dp(8));
        line.addView(chip, params);
    }

    private LinearLayout texts(LinearLayout row, String title, String description) {
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(palette.textPrimary);
        titleView.setTextSize(16);
        titleView.setTypeface(Fonts.semibold(this));
        texts.addView(titleView);
        if (description != null) {
            TextView descriptionView = new TextView(this);
            descriptionView.setText(description);
            descriptionView.setTextColor(palette.textSecondary);
            descriptionView.setTextSize(13.5f);
            descriptionView.setLineSpacing(0, 1.08f);
            descriptionView.setTypeface(Fonts.regular(this));
            descriptionView.setPadding(0, dp(2), 0, 0);
            texts.addView(descriptionView);
        }
        texts.setContentDescription(description == null ? title : title + ". " + description);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        return texts;
    }

    // ---- restart ---------------------------------------------------------------------------

    private View restartBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout inner = new LinearLayout(this);
        inner.setGravity(Gravity.CENTER_VERTICAL);
        inner.setPadding(dp(16), dp(8), dp(8), dp(8));
        // Inverted: the one solid shape on a page of plain text, so it is seen without a color.
        inner.setBackground(rounded(palette.textPrimary, dp(12)));
        TextView hint = new TextView(this);
        hint.setText(I18n.get(S.RESTART_HINT));
        hint.setTextColor(blend(palette.background, palette.textPrimary, 0.35f));
        hint.setTextSize(13.5f);
        hint.setTypeface(Fonts.regular(this));
        inner.addView(hint, new LinearLayout.LayoutParams(0, -2, 1));
        TextView button = new TextView(this);
        button.setText(I18n.get(S.RESTART));
        button.setTextColor(palette.background);
        button.setTextSize(15);
        button.setTypeface(Fonts.semibold(this));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setMinHeight(dp(40));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.dark ? 0x22000000 : 0x33FFFFFF), null,
                rounded(0xFFFFFFFF, dp(10))));
        button.setOnClickListener(view -> AppRestart.restart(this));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-2, dp(40));
        buttonParams.setMarginStart(dp(12));
        inner.addView(button, buttonParams);
        bar.addView(inner, new LinearLayout.LayoutParams(-1, -2));
        bar.setVisibility(View.GONE);
        return bar;
    }

    private void onPrefsChanged() {
        runOnUiThread(() -> {
            updateRestartBar(true);
            for (View page : pages) {
                if (page.getTag() instanceof Runnable) ((Runnable) page.getTag()).run();
            }
        });
    }

    private void updateRestartBar(boolean animate) {
        boolean show = Prefs.restartPending();
        boolean shown = restartBar.getVisibility() == View.VISIBLE && restartBar.getAlpha() > 0f;
        if (show == shown) return;
        if (!show) {
            // Leaves the way it came, faster than it arrived.
            if (animate && motion()) {
                restartBar.animate().alpha(0f).translationY(dp(12)).setDuration(150).setInterpolator(PRESS)
                        .withEndAction(() -> restartBar.setVisibility(View.GONE)).start();
            } else {
                restartBar.setVisibility(View.GONE);
            }
            return;
        }
        restartBar.animate().cancel();
        restartBar.setVisibility(View.VISIBLE);
        restartBar.setAlpha(1f);
        restartBar.setTranslationY(0);
        if (animate && motion()) {
            restartBar.setAlpha(0f);
            restartBar.setTranslationY(dp(12));
            restartBar.animate().alpha(1f).translationY(0).setDuration(220).setInterpolator(EASE).start();
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private void hideKeyboard(View view) {
        android.view.inputmethod.InputMethodManager manager = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (manager != null) manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    /** A scrolling page with TikTok's collapsing large title and a toolbar. */
    private final class Page {
        final FrameLayout root = new FrameLayout(ModSettingsActivity.this);
        final LinearLayout content = new LinearLayout(ModSettingsActivity.this);
        private final ScrollView scroll = new ScrollView(ModSettingsActivity.this);
        private final FrameLayout toolbar = new FrameLayout(ModSettingsActivity.this);

        Page(String title, boolean root) {
            this.root.setBackgroundColor(palette.background);
            this.root.setClickable(true);
            content.setOrientation(LinearLayout.VERTICAL);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            scroll.setClipToPadding(false);
            scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
            this.root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

            // Clear while the page is at its top, so the hero glow runs under the status bar;
            // it fills in as content scrolls beneath it, instead of a hard divider line.
            ColorDrawable toolbarFill = new ColorDrawable(palette.background);
            toolbarFill.setAlpha(0);
            toolbar.setBackground(toolbarFill);
            ImageView back = new ImageView(ModSettingsActivity.this);
            back.setImageDrawable(new Glyph(Glyph.Kind.BACK, palette.textPrimary, dp(24)));
            back.setScaleType(ImageView.ScaleType.CENTER);
            back.setContentDescription(I18n.get(S.BACK));
            back.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.pressed), null, null));
            back.setOnClickListener(view -> {
                if (pages.size() > 1) pop();
                else finish();
            });
            FrameLayout.LayoutParams backParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START | Gravity.BOTTOM);
            backParams.setMarginStart(dp(4));
            backParams.bottomMargin = dp(4);
            toolbar.addView(back, backParams);
            TextView small = new TextView(ModSettingsActivity.this);
            small.setText(title);
            small.setTextColor(palette.textPrimary);
            small.setTextSize(18);
            small.setTypeface(Fonts.bold(ModSettingsActivity.this));
            small.setSingleLine(true);
            small.setEllipsize(TextUtils.TruncateAt.END);
            small.setGravity(Gravity.CENTER_VERTICAL);
            FrameLayout.LayoutParams smallParams = new FrameLayout.LayoutParams(-1, dp(56), Gravity.BOTTOM);
            smallParams.setMarginStart(dp(60));
            smallParams.setMarginEnd(dp(16));
            toolbar.addView(small, smallParams);
            this.root.addView(toolbar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

            View large = null;
            if (root) {
                TextView heading = new TextView(ModSettingsActivity.this);
                heading.setText(title);
                heading.setTextColor(palette.textPrimary);
                heading.setTextSize(32);
                // Large type reads looser than it is; a slightly negative tracking tightens it.
                heading.setLetterSpacing(-0.025f);
                heading.setTypeface(Fonts.bold(ModSettingsActivity.this));
                heading.setPadding(dp(24), dp(8), dp(24), dp(14));
                content.addView(heading);
                large = heading;
                small.setAlpha(0f);
            }
            View largeTitle = large;
            scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
                toolbarFill.setAlpha(Math.round(255 * clamp(y / (float) dp(20))));
                if (largeTitle == null) return;
                float fade = Math.max(0f, Math.min(1f, (y - largeTitle.getHeight() * 0.5f) / (largeTitle.getHeight() * 0.4f)));
                small.setAlpha(fade);
            });
            this.root.setTag(R_ID_CONTENT, content);
            this.root.setTag(R_ID_INSETS, (Insettable) (top, bottom) -> {
                toolbar.setPadding(0, top, 0, 0);
                toolbar.getLayoutParams().height = top + dp(56);
                toolbar.requestLayout();
                scroll.setPadding(0, top + dp(56), 0, bottom + dp(96));
            });
        }

        void section(String title) {
            TextView header = new TextView(ModSettingsActivity.this);
            header.setText(title);
            header.setTextColor(palette.textTertiary);
            header.setTextSize(12.5f);
            header.setTypeface(Fonts.regular(ModSettingsActivity.this));
            header.setPadding(dp(34), dp(24), dp(34), dp(8));
            if (Build.VERSION.SDK_INT >= 28) header.setAccessibilityHeading(true);
            content.addView(header);
        }

        LinearLayout card() { return ModSettingsActivity.this.card(content); }

        void footer(String text) {
            TextView footer = new TextView(ModSettingsActivity.this);
            footer.setText(text);
            footer.setTextColor(palette.textTertiary);
            footer.setTextSize(13);
            footer.setTypeface(Fonts.regular(ModSettingsActivity.this));
            footer.setPadding(dp(24), dp(8), dp(24), 0);
            content.addView(footer);
        }
    }

    /** Builds the intent that opens this screen from inside TikTok. */
    static Intent intent(Context host, boolean dark) {
        Intent intent = new Intent();
        intent.setClassName(host.getPackageName(), SettingsEntry.HOST_ACTIVITY);
        intent.putExtra(EXTRA, true);
        intent.putExtra(EXTRA_DARK, dark);
        if (!(host instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }
}
