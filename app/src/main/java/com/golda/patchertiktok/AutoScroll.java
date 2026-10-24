package com.golda.patchertiktok;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/**
 * When a video ends, the feed moves on to the next one, on the pages that show videos full screen:
 * Following, search and chats. Profiles stay put, and For You is left to TikTok. TikTok loops
 * videos, so the end is seen as the player position jumping from the last moments back to the start. A photo post ends once each
 * photo has had its few seconds, or when its music runs out. Nothing happens while a comment
 * sheet, menu or dialog covers the video, and if TikTok's own auto-scroll already moved on, this
 * does not move a second time. For You is left to TikTok's own auto-scroll and its switch.
 */
final class AutoScroll {
    private static final String PANEL = "com.ss.android.ugc.aweme.feed.panel.BaseListFragmentPanel";
    private static final String ABILITY = "com.ss.android.ugc.feed.platform.panel.player.IPlayerComponentAbility";
    private static final String PAGER = "com.ss.android.ugc.aweme.common.widget.VerticalViewPager";
    private static final String MENU_ROW = "com.golda.patchertiktok.autoScrollRow";
    private static final String SHARE_PACKAGE = "com.ss.android.ugc.aweme.share.improve.pkg.AwemeSharePackage";
    /** For You, where TikTok runs auto-scroll itself with its own switch. */
    private static final String RECOMMEND = "com.ss.android.ugc.aweme.feed.panel.RecommendFeedFragmentPanel";
    private static final long TICK_MS = 400L;
    private static final long PHOTO_MS = 3_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static Method currentAweme;
    private static Method playerAbility;
    private static Method pager;
    private static WeakReference<Object> panel = new WeakReference<>(null);
    private static String playing;
    private static long duration;
    /** For photo posts: how long to stay, by the music's clock. Zero for videos. */
    private static long limit;
    private static long last = -1;
    /** When TikTok last packed a video for its share panel; the panel's window follows right after. */
    private static volatile long sharePacked;
    /** A finger is on the screen of a TikTok activity. */
    private static Class<?> recommendPanel;
    /** The feed panel that last showed a video, For You included. */
    private static WeakReference<Object> shown = new WeakReference<>(null);

    private AutoScroll() { }

    static void install(ClassLoader loader) {
        Class<?> panelType = Hooks.findClass(PANEL, loader);
        if (panelType == null) {
            RuntimeLog.log("auto-scroll unavailable: feed panel not found");
            return;
        }
        currentAweme = RenderedAdSkip.currentAweme(panelType);
        recommendPanel = Hooks.findClass(RECOMMEND, loader);
        for (Method method : panelType.getDeclaredMethods()) {
            if (method.getParameterTypes().length != 0) continue;
            String type = method.getReturnType().getName();
            if (playerAbility == null && ABILITY.equals(type)) playerAbility = method;
            if (pager == null && PAGER.equals(type)) pager = method;
        }
        if (currentAweme == null || playerAbility == null || pager == null) {
            RuntimeLog.log("auto-scroll unavailable: player or pager not found");
            return;
        }
        currentAweme.setAccessible(true);
        playerAbility.setAccessible(true);
        pager.setAccessible(true);
        for (Method method : panelType.getDeclaredMethods()) {
            if (!"onRenderFirstFrame".equals(method.getName()) || method.getReturnType() != void.class) continue;
            Hooks.hook(method, new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    shown = new WeakReference<>(param.thisObject);
                    // The panel is remembered even when off, so the switch can start right away.
                    if (!recommended(param.thisObject)) started(param.thisObject);
                }
            });
        }
    }

    /**
     * The switch in the panel a long press opens. On For You, TikTok's menu has its own
     * auto-scroll switch, so nothing is added there. Elsewhere (profiles, chats, search) the long
     * press opens the share panel: it is recognised by TikTok packing the video for sharing just
     * before a dialog appears while the finger is still on the screen; a tap on the share button
     * has already lifted it. The switch goes under the panel's list.
     */
    static void installSharePanel(ClassLoader loader) {
        Class<?> pack = Hooks.findClass(SHARE_PACKAGE, loader);
        if (pack == null) {
            RuntimeLog.log("auto-scroll share panel unavailable: share package not found");
            return;
        }
        Touch.install();
        Hooks.hookConstructors(pack, new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                sharePacked = android.os.SystemClock.uptimeMillis();
            }
        });
        Hooks.findAndHook(android.app.Dialog.class, "show", new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                if (!Touch.pressing() || android.os.SystemClock.uptimeMillis() - sharePacked > 3_000L) return;
                Object current = shown.get();
                if (current == null || recommended(current) || profile(current)) return;
                android.view.Window window = ((android.app.Dialog) param.thisObject).getWindow();
                if (window == null) return;
                View decor = window.getDecorView();
                // Joined before the panel's first frame, so the card slides in with it.
                android.view.ViewTreeObserver observer = decor.getViewTreeObserver();
                observer.addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                    private int frames;

                    @Override public boolean onPreDraw() {
                        View list = shallowestList(decor);
                        boolean ready = list != null && list.getWidth() > 0;
                        if (!ready && ++frames < 30) return true;
                        remove(decor, observer, this);
                        if (!ready) return true;
                        addToPanel(decor);
                        // Skip this frame; the next one already has the card.
                        return decor.findViewWithTag(MENU_ROW) == null;
                    }
                });
            }
        });
    }

    private static void addToPanel(View decor) {
        if (decor.findViewWithTag(MENU_ROW) != null) return;
        View list = shallowestList(decor);
        if (list == null) return;
        // The friends list scrolls; the actions and the share-to card above it stay put. The switch
        // joins that fixed header as its last card.
        ViewParent holder = list.getParent();
        if (holder instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) holder;
            int index = group.indexOfChild(list);
            View before = index > 0 ? group.getChildAt(index - 1) : null;
            if (before instanceof LinearLayout && ((LinearLayout) before).getOrientation() == LinearLayout.VERTICAL
                    && ((LinearLayout) before).getChildCount() > 0) {
                LinearLayout header = (LinearLayout) before;
                insert(header, header.getChildAt(header.getChildCount() - 1), header);
                return;
            }
        }
        View child = list;
        for (int depth = 0; depth < 6 && child != null; depth++) {
            ViewParent parent = child.getParent();
            if (parent instanceof LinearLayout && ((LinearLayout) parent).getOrientation() == LinearLayout.VERTICAL) {
                insert((LinearLayout) parent, child, list);
                return;
            }
            child = parent instanceof View ? (View) parent : null;
        }
    }

    /** The share panel's own list: the recycler view nearest the top of the window. */
    static View shallowestList(View root) {
        java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.poll();
            if (Views.isRecycler(view)) return view;
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
            }
        }
        return null;
    }

    /** Adds the switch card to {@code list} right after {@code after}, in the text color found in {@code style}. */
    private static void insert(LinearLayout list, View after, View style) {
        View anchor = list;
        TextView sample = Views.firstText(style);
        int color = sample != null ? sample.getCurrentTextColor() : Color.WHITE;
        boolean dark = luminance(color) > 0.5f;
        float density = anchor.getResources().getDisplayMetrics().density;

        LinearLayout line = new LinearLayout(anchor.getContext());
        line.setTag(MENU_ROW);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(Math.round(56 * density));
        // A card of its own, like the panel's other blocks.
        int side = Math.round(16 * density);
        line.setPadding(side, 0, side, 0);
        android.graphics.drawable.GradientDrawable card = new android.graphics.drawable.GradientDrawable();
        card.setColor(dark ? 0x1AFFFFFF : 0x0D000000);
        card.setCornerRadius(16 * density);
        line.setBackground(card);

        ImageView icon = new ImageView(anchor.getContext());
        icon.setImageDrawable(new Glyph(Glyph.Kind.SCROLL, color, Math.round(24 * density)));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(Math.round(24 * density), Math.round(24 * density));
        iconParams.rightMargin = Math.round(12 * density);
        line.addView(icon, iconParams);

        TextView title = new TextView(anchor.getContext());
        title.setText(I18n.get(I18n.S.AUTO_SCROLL_MENU));
        title.setTextColor(color);
        // Labels under icons are small; a row title is never smaller than 15sp.
        float minimum = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15, anchor.getResources().getDisplayMetrics());
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sample != null ? Math.max(minimum, sample.getTextSize()) : minimum);
        if (sample != null) title.setTypeface(sample.getTypeface());
        line.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        Toggle toggle = new Toggle(anchor.getContext(), Palette.of(dark));
        toggle.setChecked(Prefs.on(Prefs.AUTO_SCROLL), false);
        line.addView(toggle);
        line.setOnClickListener(view -> {
            boolean on = !Prefs.on(Prefs.AUTO_SCROLL);
            Prefs.set(Prefs.AUTO_SCROLL, on);
            toggle.setChecked(on, true);
            Object current = panel.get();
            if (on && current != null) started(current);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(Math.round(12 * density), Math.round(8 * density), Math.round(12 * density), 0);
        line.setAlpha(0f);
        list.addView(line, list.indexOfChild(after) + 1, params);
        android.view.ViewTreeObserver observer = line.getViewTreeObserver();
        // Once laid out, take the panel's own card look: its side margins, the gap between its
        // cards, its color and its corners. Then the switch reads as one more of its cards.
        observer.addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (line.getWidth() == 0) return true;
                remove(line, observer, this);
                line.setAlpha(1f);
                // The panel may have turned the params into its own type; adjust those, never replace them.
                if (!(line.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return true;
                ViewGroup.MarginLayoutParams live = (ViewGroup.MarginLayoutParams) line.getLayoutParams();
                java.util.List<View> cards = new java.util.ArrayList<>();
                cards(after, 0, list.getWidth(), cards);
                if (!cards.isEmpty() && list == style) {
                    // In the header: the block above wraps its card with the spacing the panel uses.
                    View first = cards.get(0);
                    int[] listAt = new int[2];
                    int[] cardAt = new int[2];
                    int[] blockAt = new int[2];
                    list.getLocationInWindow(listAt);
                    first.getLocationInWindow(cardAt);
                    after.getLocationInWindow(blockAt);
                    live.leftMargin = Math.max(0, cardAt[0] - listAt[0]);
                    live.rightMargin = Math.max(0, listAt[0] + list.getWidth() - cardAt[0] - first.getWidth());
                    int below = blockAt[1] + after.getHeight() - cardAt[1] - first.getHeight();
                    live.topMargin = 0;
                    live.bottomMargin = below >= 0 && below <= Math.round(32 * density) ? below : Math.round(8 * density);
                    if (below > Math.round(32 * density) || below < 0) live.topMargin = Math.round(8 * density);
                    line.setMinimumHeight(first.getHeight());
                    copyShape(first, line);
                    line.requestLayout();
                } else if (!cards.isEmpty()) {
                    View first = cards.get(0);
                    int[] listAt = new int[2];
                    int[] cardAt = new int[2];
                    list.getLocationInWindow(listAt);
                    first.getLocationInWindow(cardAt);
                    live.leftMargin = Math.max(0, cardAt[0] - listAt[0]);
                    live.rightMargin = Math.max(0, listAt[0] + list.getWidth() - cardAt[0] - first.getWidth());
                    if (cards.size() > 1) {
                        int[] nextAt = new int[2];
                        cards.get(1).getLocationInWindow(nextAt);
                        int gap = nextAt[1] - cardAt[1] - first.getHeight();
                        // The list keeps empty room at its end; the card covers it so the last gap matches the others.
                        if (gap >= 0 && gap <= Math.round(24 * density)) live.topMargin = gap - endRoom(first);
                    }
                    copyShape(first, line);
                    line.requestLayout();
                }
                // Drawn on the next frame, with the fitted margins.
                return cards.isEmpty();
            }
        });
    }

    private static void remove(View view, android.view.ViewTreeObserver observer,
            android.view.ViewTreeObserver.OnPreDrawListener listener) {
        android.view.ViewTreeObserver live = observer.isAlive() ? observer : view.getViewTreeObserver();
        if (live.isAlive()) live.removeOnPreDrawListener(listener);
    }


    /** Paints the switch card with a copy of TikTok's own card background. */
    private static void copyShape(View from, View to) {
        android.graphics.drawable.Drawable theirs = from.getBackground();
        android.graphics.drawable.Drawable.ConstantState state = theirs == null ? null : theirs.getConstantState();
        if (state != null) to.setBackground(state.newDrawable(from.getResources()).mutate());
    }

    /** Empty room the cards' scrolling list keeps after its last card. */
    private static int endRoom(View card) {
        ViewParent parent = card.getParent();
        return parent instanceof View ? ((View) parent).getPaddingBottom() : 0;
    }

    /** Rounded full-width blocks of the panel, top to bottom. */
    private static void cards(View view, int depth, int width, java.util.List<View> out) {
        if (depth > 8 || out.size() >= 2 || width <= 0) return;
        // A card: painted, nearly as wide as the panel but inset from its edges, taller than a line.
        android.graphics.drawable.Drawable background = view.getBackground();
        float density = view.getResources().getDisplayMetrics().density;
        if (depth > 0 && background != null && view.getWidth() >= width * 0.8f && view.getWidth() <= width - 8 * density
                && view.getHeight() >= 40 * density) {
            out.add(view);
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount() && out.size() < 2; i++) cards(group.getChildAt(i), depth + 1, width, out);
    }

    private static float luminance(int color) {
        return (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f;
    }

    /** A person's videos opened from their profile, the liked list included. */
    private static boolean profile(Object feedPanel) {
        for (Class<?> type = feedPanel.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().endsWith(".ProfileDetailFragmentPanel")) return true;
        }
        return false;
    }

    private static boolean recommended(Object feedPanel) {
        return recommendPanel != null && recommendPanel.isInstance(feedPanel);
    }

    private static void started(Object feedPanel) {
        if (profile(feedPanel)) {
            // Profiles are browsed video by video; they never move on by themselves.
            MAIN.removeCallbacks(TICK);
            panel = new WeakReference<>(null);
            return;
        }
        Object aweme = invoke(currentAweme, feedPanel);
        String id = aweme == null ? null : string(Reflect.get(aweme, "aid"));
        if (id == null) return;
        if (!id.equals(playing) || panel.get() != feedPanel) last = -1;
        panel = new WeakReference<>(feedPanel);
        playing = id;
        duration = ResumePosition.duration(aweme);
        limit = 0;
        if (duration <= 0) {
            // A photo post: its music plays on the player's clock.
            int photos = DownloadModels.photoImages(aweme).size();
            Object seconds = Reflect.call(Reflect.call(aweme, "getMusic"), "getDuration");
            long music = seconds instanceof Number ? ((Number) seconds).longValue() * 1_000L : 0L;
            if (photos > 0) limit = AutoScrollPolicy.photoTime(photos, PHOTO_MS, music);
        }
        MAIN.removeCallbacks(TICK);
        if (Prefs.on(Prefs.AUTO_SCROLL) && (duration > 0 || limit > 0)) MAIN.postDelayed(TICK, TICK_MS);
    }

    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            Object feedPanel = panel.get();
            String id = playing;
            if (feedPanel == null || id == null || !Prefs.on(Prefs.AUTO_SCROLL)) return;
            Object aweme = invoke(currentAweme, feedPanel);
            if (aweme == null || !id.equals(string(Reflect.get(aweme, "aid")))) return;
            Object value = Reflect.call(invoke(playerAbility, feedPanel), "getCurrentPosition");
            if (value instanceof Number) {
                long position = ((Number) value).longValue();
                boolean done = limit > 0
                        ? position >= limit || AutoScrollPolicy.looped(last, position, limit)
                        : AutoScrollPolicy.looped(last, position, duration);
                if (done && next(feedPanel)) {
                    last = -1;
                    return;
                }
                last = position;
            }
            MAIN.postDelayed(this, TICK_MS);
        }
    };

    private static boolean next(Object feedPanel) {
        Activity activity = activity(feedPanel);
        // A comment sheet, share menu or dialog has the focus: the user is busy with this video.
        if (activity == null || activity.isFinishing() || !activity.hasWindowFocus()) return false;
        Object view = invoke(pager, feedPanel);
        if (view == null) return false;
        Object current = Reflect.call(view, "getCurrentItem");
        if (!(current instanceof Integer)) return false;
        int target = (Integer) current + 1;
        Object adapter = Reflect.call(view, "getAdapter");
        Object count = adapter == null ? null : Reflect.call(adapter, "getCount");
        if (count instanceof Integer && target >= (Integer) count) return false;
        try {
            view.getClass().getMethod("setCurrentItem", int.class, boolean.class).invoke(view, target, true);
            return true;
        } catch (Throwable smooth) {
            try {
                view.getClass().getMethod("setCurrentItem", int.class).invoke(view, target);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    private static Activity activity(Object feedPanel) {
        Object value = Reflect.call(feedPanel, "getActivity");
        if (!(value instanceof Activity)) value = Reflect.get(feedPanel, "activity");
        return value instanceof Activity ? (Activity) value : null;
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
