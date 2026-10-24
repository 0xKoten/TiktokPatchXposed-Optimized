package com.golda.patchertiktok;

import android.app.Activity;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Rows added to TikTok's message menu (Reply, Forward, Save…), a short list with one row per action,
 * in its own style: "Download sticker" for a sticker, whose addresses come from the sticker cell that
 * shows it (its StickerTemplate holds every URL of the picture), and "Mark as read" when invisible
 * mode kept this chat's read mark back.
 */
final class ChatMenu {
    private static final String PRESS = "com.ss.android.ugc.aweme.im.sdk.chat.ui.adapter.MessageListLongPressAssem";
    private static final String SLOT = "com.ss.android.ugc.aweme.im.sdk.chat.ui.gif.StoreStickerCardReusedUISlot";
    private static final String TEMPLATE = "com.ss.android.ugc.aweme.im.message.template.card.StickerTemplate";
    private static final String ROW = "tpx_chat_menu_rows";

    /** Sticker cells by the view they draw into. */
    private static final Map<View, Object> SLOTS = Collections.synchronizedMap(new WeakHashMap<>());
    private static Method templateGetter;

    private ChatMenu() { }

    static void install(ClassLoader loader) {
        Class<?> press = Hooks.findClass(PRESS, loader);
        Class<?> slot = Hooks.findClass(SLOT, loader);
        Class<?> template = Hooks.findClass(TEMPLATE, loader);
        if (press == null || slot == null || template == null) {
            RuntimeLog.log("chat menu unavailable: classes not found");
            return;
        }
        for (Method method : slot.getDeclaredMethods()) {
            if (method.getParameterTypes().length == 0 && method.getReturnType() == template && !Modifier.isStatic(method.getModifiers())) {
                if (templateGetter != null) {
                    RuntimeLog.log("chat menu unavailable: sticker getter ambiguous");
                    templateGetter = null;
                    return;
                }
                templateGetter = method;
            }
        }
        if (templateGetter == null) {
            RuntimeLog.log("chat menu unavailable: sticker getter not found");
            return;
        }
        templateGetter.setAccessible(true);
        // Every bind passes through a one-object method; the cell's view is remembered with it.
        Hooks.Hook remember = new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                Object view = Reflect.call(param.thisObject, "getContainerView");
                if (view instanceof View) SLOTS.put((View) view, param.thisObject);
            }
        };
        int binds = 0;
        for (Method method : slot.getDeclaredMethods()) {
            if (method.getParameterTypes().length == 1 && method.getReturnType() == void.class
                    && !Modifier.isStatic(method.getModifiers()) && !Modifier.isAbstract(method.getModifiers())
                    && !View.class.isAssignableFrom(method.getParameterTypes()[0])) {
                Hooks.hook(method, remember);
                binds++;
            }
        }
        int menus = 0;
        for (Method method : press.getDeclaredMethods()) {
            if (method.getParameterTypes().length != 1 || method.getReturnType() != void.class || method.isBridge()) continue;
            Hooks.hook(method, new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    if (Prefs.on(Prefs.CHAT_STICKER) || Prefs.on(Prefs.GHOST_READ)) pressed(param.args[0]);
                }
            });
            menus++;
        }
        RuntimeLog.log("chat menu: binds=" + binds + " menus=" + menus);
    }

    /** The menu's payload: the pressed bubble, the message and TikTok's action list. */
    private static void pressed(Object payload) {
        if (payload == null) return;
        View bubble = null;
        List<?> actions = null;
        String chat = null;
        for (Field field : payload.getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true);
                Object value = field.get(payload);
                if (value instanceof View && bubble == null) bubble = (View) value;
                if (value instanceof List && actions == null) actions = (List<?>) value;
                Object id = value == null ? null : Reflect.call(value, "getConversationId");
                if (id instanceof String && chat == null) chat = (String) id;
            } catch (Throwable ignored) {
                // Next field.
            }
        }
        if (bubble == null || actions == null || actions.isEmpty()) return;
        List<Row> rows = new ArrayList<>();
        List<DownloadModels.Item> sticker = Prefs.on(Prefs.CHAT_STICKER) ? sticker(bubble) : Collections.emptyList();
        Activity activity = Views.activity(bubble.getContext());
        if (activity == null) return;
        if (!sticker.isEmpty()) {
            rows.add(new Row(I18n.get(I18n.S.CM_SAVE_STICKER), Glyph.Kind.DOWNLOAD,
                    () -> DownloadsPlus.save(activity, "chat_sticker_" + System.currentTimeMillis(), sticker)));
        }
        String conversation = chat;
        if (Prefs.on(Prefs.GHOST_READ) && ReadReceipts.unsent(conversation)) {
            rows.add(new Row(I18n.get(I18n.S.READ_NOW), Glyph.Kind.CHECK, () -> ReadReceipts.markRead(conversation)));
        }
        if (rows.isEmpty()) return;
        View decor = activity.getWindow().getDecorView();
        int count = actions.size();
        ViewTreeObserver observer = decor.getViewTreeObserver();
        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            private int frames;

            @Override public boolean onPreDraw() {
                ViewGroup menu = menu(decor, count);
                if (menu == null && ++frames < 40) return true;
                ViewTreeObserver live = observer.isAlive() ? observer : decor.getViewTreeObserver();
                if (live.isAlive()) live.removeOnPreDrawListener(this);
                if (menu == null || !(menu.getParent() instanceof FrameLayout)) return true;
                FrameLayout holder = (FrameLayout) menu.getParent();
                if (holder.findViewWithTag(ROW) != null) return true;
                addRows(activity, holder, menu, rows);
                return false;
            }
        });
    }

    /** The addresses of the sticker shown inside the pressed bubble. */
    private static List<DownloadModels.Item> sticker(View bubble) {
        List<View> views = new ArrayList<>();
        collect(bubble, views, 0);
        for (View view : views) {
            Object slot = SLOTS.get(view);
            if (slot == null) continue;
            try {
                Object template = templateGetter.invoke(slot);
                Object image = Reflect.get(template, "imageComponent");
                Object urls = Reflect.get(image, "urls");
                Object key = Reflect.get(image, "decryptKey");
                // An encrypted sticker would save as unreadable bytes.
                if (!(urls instanceof List) || (key != null && !"".equals(key))) continue;
                List<String> list = new ArrayList<>();
                for (Object url : (List<?>) urls) if (url instanceof String) list.add((String) url);
                if (!list.isEmpty()) {
                    return Collections.singletonList(new DownloadModels.Item(DownloadFormat.Kind.PHOTO, list, 0, 0, 0, 0, false));
                }
            } catch (Throwable ignored) {
                // Not a usable sticker.
            }
        }
        return Collections.emptyList();
    }

    private static void collect(View view, List<View> out, int depth) {
        out.add(view);
        if (depth > 8 || !(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out, depth + 1);
    }

    /** TikTok's menu: a shown list with one labelled row per action. */
    private static ViewGroup menu(View root, int rows) {
        if (root.getVisibility() != View.VISIBLE || !(root instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) root;
        if (Views.isRecycler(root) && group.getChildCount() == rows && root.getWidth() > 0) {
            boolean labelled = true;
            for (int i = 0; i < rows && labelled; i++) labelled = Views.firstText(group.getChildAt(i)) != null;
            if (labelled) return group;
        }
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            ViewGroup found = menu(group.getChildAt(i), rows);
            if (found != null) return found;
        }
        return null;
    }

    /** One added menu row. */
    private static final class Row {
        final String title;
        final Glyph.Kind icon;
        final Runnable action;

        Row(String title, Glyph.Kind icon, Runnable action) {
            this.title = title;
            this.icon = icon;
            this.action = action;
        }
    }

    /** Our rows, styled like the menu's first one, stacked in room made under TikTok's list. */
    private static void addRows(Activity activity, FrameLayout holder, ViewGroup menu, List<Row> rows) {
        View sample = menu.getChildAt(0);
        TextView label = Views.firstText(sample);
        if (label == null || sample.getHeight() == 0) return;
        View theirIcon = label.getParent() instanceof ViewGroup && ((ViewGroup) label.getParent()).getChildCount() > 1
                ? ((ViewGroup) label.getParent()).getChildAt(0) : null;
        // Layout positions, not screen ones: the menu is still scaling in when this runs.
        float density = menu.getResources().getDisplayMetrics().density;
        int labelX = offset(label, sample);
        int size = theirIcon != null && theirIcon.getWidth() > 0 ? theirIcon.getWidth() : Math.round(24 * density);
        int iconLeft = theirIcon != null ? offset(theirIcon, sample) : Math.max(0, labelX - size - Math.round(12 * density));
        int height = sample.getHeight();
        android.util.TypedValue ripple = new android.util.TypedValue();
        boolean hasRipple = activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);

        LinearLayout block = new LinearLayout(holder.getContext());
        block.setTag(ROW);
        block.setOrientation(LinearLayout.VERTICAL);
        for (Row item : rows) {
            LinearLayout row = new LinearLayout(holder.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            ImageView icon = new ImageView(holder.getContext());
            icon.setImageDrawable(new Glyph(item.icon, label.getCurrentTextColor(), size));
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(size, size);
            iconParams.leftMargin = iconLeft;
            iconParams.rightMargin = Math.max(0, labelX - iconLeft - size);
            row.addView(icon, iconParams);
            TextView title = new TextView(holder.getContext());
            title.setText(item.title);
            title.setTextColor(label.getCurrentTextColor());
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, label.getTextSize());
            title.setTypeface(label.getTypeface());
            title.setSingleLine(true);
            title.setPadding(label.getTotalPaddingLeft(), 0, 0, 0);
            row.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
            if (hasRipple) row.setBackgroundResource(ripple.resourceId);
            row.setOnClickListener(view -> {
                item.action.run();
                // TikTok's menu closes on Back, like on a tap outside it.
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
            });
            block.addView(row, new LinearLayout.LayoutParams(-1, height));
        }
        int total = height * rows.size();
        menu.setClipToPadding(false);
        menu.setPadding(menu.getPaddingLeft(), menu.getPaddingTop(), menu.getPaddingRight(), menu.getPaddingBottom() + total);
        // Lined up with TikTok's own rows, which sit inside the list's side padding.
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(sample.getWidth(), total, Gravity.BOTTOM | Gravity.START);
        params.leftMargin = offset(sample, holder) - holder.getPaddingLeft();
        params.bottomMargin = Math.max(0, holder.getHeight() - menu.getBottom());
        holder.addView(block, params);
    }

    /** How far {@code view} sits from the left of {@code ancestor}, by layout. */
    private static int offset(View view, View ancestor) {
        int x = 0;
        for (View v = view; v != null && v != ancestor; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            x += v.getLeft() - (v.getParent() instanceof View ? ((View) v.getParent()).getScrollX() : 0);
        }
        return x;
    }

}
