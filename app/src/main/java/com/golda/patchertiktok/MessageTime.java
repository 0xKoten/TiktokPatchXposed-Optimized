package com.golda.patchertiktok;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sent time under each chat message. TikTok binds every message row through one method of its
 * row slot; after it runs, the row's message (an IM model with getCreatedAt) is read from the bound
 * item. The row's bottom padding, which TikTok sets again on every bind, gets room for one small
 * line, and the time is drawn there over the row, under the bubble and on the bubble's side. TikTok's
 * own layout is not changed, so swipes, long presses and reactions work as before.
 */
final class MessageTime {
    private static final String[] SLOTS = {
            "com.ss.android.ugc.aweme.im.sdk.chat.messagelist.skeleton.cell.IMSkeletonLayoutReusedUISlotAssem",
            "com.ss.android.ugc.aweme.im.sdk.chat.messagelist.skeleton.cell.SkeletonLayoutReusedUISlotAssem"};
    /** The bubble's container in the row; the time lines up under it. */
    private static final String BUBBLE = "f62";
    private static final String LABEL = "tpx_message_time";

    private MessageTime() { }

    static void install(ClassLoader loader) {
        int hooks = 0;
        for (String name : SLOTS) {
            Class<?> slot = Hooks.findClass(name, loader);
            if (slot == null) continue;
            Method binder = binder(slot);
            if (binder == null) continue;
            Hooks.hook(binder, new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    bound(param.thisObject, param.args[0]);
                }
            });
            hooks++;
        }
        RuntimeLog.log("message time: binders=" + hooks);
    }

    /** The row's bind method: one model argument, not the bridge that takes Object, nor a view setter. */
    private static Method binder(Class<?> slot) {
        Method found = null;
        for (Method method : slot.getDeclaredMethods()) {
            Class<?>[] p = method.getParameterTypes();
            if (method.isSynthetic() || method.isBridge() || Modifier.isStatic(method.getModifiers())
                    || Modifier.isAbstract(method.getModifiers()) || method.getReturnType() != void.class
                    || p.length != 1 || p[0].isPrimitive() || p[0] == Object.class || p[0] == String.class
                    || View.class.isAssignableFrom(p[0]) || p[0].getName().startsWith("java.")) continue;
            if (found != null) return null;
            found = method;
        }
        return found;
    }

    private static void bound(Object slot, Object item) {
        Object container = Reflect.call(slot, "getContainerView");
        if (!(container instanceof ViewGroup)) return;
        ViewGroup row = (ViewGroup) container;
        Label label = (Label) Hooks.getExtra(row, LABEL);
        Object created = Prefs.on(Prefs.MESSAGE_TIME) ? Reflect.call(message(item), "getCreatedAt") : null;
        if (!(created instanceof Long) || (Long) created <= 0) {
            // Off, or a row without a time (notices): nothing drawn, TikTok's padding stays.
            if (label != null) label.setText(null);
            return;
        }
        if (label == null) {
            label = new Label(row);
            label.setBounds(0, 0, 1 << 14, 1 << 14);
            row.getOverlay().add(label);
            Hooks.setExtra(row, LABEL, label);
        }
        Context context = row.getContext();
        label.setText(format(DateFormat.is24HourFormat(context)).format(new Date((Long) created)));
        // TikTok sets this padding on every bind; if a bind leaves ours in place, it is not added twice.
        int bottom = row.getPaddingBottom();
        if (bottom == label.applied) bottom = label.base;
        label.base = bottom;
        label.applied = bottom + label.height();
        row.setPadding(row.getPaddingLeft(), row.getPaddingTop(), row.getPaddingRight(), label.applied);
    }

    /** Rows bind on the main thread only, so one formatter per clock style is reused. */
    private static java.text.SimpleDateFormat clock24;
    private static java.text.SimpleDateFormat clock12;

    private static java.text.SimpleDateFormat format(boolean twentyFour) {
        if (twentyFour) {
            if (clock24 == null) clock24 = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
            return clock24;
        }
        if (clock12 == null) clock12 = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault());
        return clock12;
    }

    /** The time, drawn over the row in the room under the bubble. */
    private static final class Label extends Drawable {
        private final ViewGroup row;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int gap;
        private String text;
        private View bubble;
        /** TikTok's own bottom padding, and the one set with room for the time. */
        int base;
        int applied = -1;

        Label(ViewGroup row) {
            this.row = row;
            float density = row.getResources().getDisplayMetrics().density;
            paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, row.getResources().getDisplayMetrics()));
            paint.setColor(0xFF8A8B91);
            gap = Math.round(2 * density);
        }

        int height() {
            Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
            return metrics.descent - metrics.ascent + gap * 2;
        }

        void setText(String value) {
            text = value;
            invalidateSelf();
            row.invalidate();
        }

        @Override public void draw(Canvas canvas) {
            if (text == null) return;
            if (bubble == null) {
                int id = row.getResources().getIdentifier(BUBBLE, "id", row.getContext().getPackageName());
                bubble = id == 0 ? null : row.findViewById(id);
            }
            View target = bubble != null && bubble.getVisibility() == View.VISIBLE && bubble.getWidth() > 0 ? bubble : largest(row);
            if (target == null) return;
            int left = target.getLeft();
            int right = target.getRight();
            for (View parent = (View) target.getParent(); parent != null && parent != row; parent = (View) parent.getParent()) {
                left += parent.getLeft();
                right += parent.getLeft();
            }
            // Your messages sit on the right half; the time follows the bubble's outer edge.
            boolean mine = left + right > row.getWidth();
            float width = paint.measureText(text);
            float x = mine ? right - width - gap * 3 : left + gap * 3;
            Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
            float y = row.getHeight() - row.getPaddingBottom() + height() - gap - metrics.descent;
            canvas.drawText(text, x, Math.min(y, row.getHeight() - metrics.descent), paint);
        }

        /** When the bubble's id is renamed: the biggest shown part of the row that does not span all of it. */
        private static View largest(ViewGroup row) {
            View best = null;
            long area = 0;
            for (int i = 0; i < row.getChildCount(); i++) {
                View child = row.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE || child.getWidth() >= row.getWidth() * 0.9f) continue;
                long size = (long) child.getWidth() * child.getHeight();
                if (size > area) {
                    area = size;
                    best = child;
                }
            }
            return best;
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }

        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Per row model class: the fields leading to its message, found once (rows bind while scrolling). */
    private static final Map<Class<?>, Field[]> PATHS = new java.util.concurrent.ConcurrentHashMap<>();

    /** The IM message inside the bound row model: the object with getCreatedAt and getUuid, a few fields deep. */
    static Object message(Object item) {
        if (item == null) return null;
        Field[] path = PATHS.get(item.getClass());
        if (path != null) {
            Object value = item;
            for (Field field : path) {
                try {
                    value = value == null ? null : field.get(value);
                } catch (Throwable error) {
                    value = null;
                }
            }
            if (value != null && isMessage(value)) return value;
        }
        Map<Object, Field[]> routes = new IdentityHashMap<>();
        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{item, 0, new Field[0]});
        while (!queue.isEmpty() && routes.size() < 200) {
            Object[] entry = queue.poll();
            Object value = entry[0];
            int depth = (Integer) entry[1];
            Field[] route = (Field[]) entry[2];
            if (value == null || routes.put(value, route) != null) continue;
            if (isMessage(value)) {
                PATHS.put(item.getClass(), route);
                return value;
            }
            if (depth >= 4) continue;
            for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                if (type.getName().startsWith("java.") || type.getName().startsWith("android.")) break;
                for (Field field : type.getDeclaredFields()) {
                    Class<?> kind = field.getType();
                    if (Modifier.isStatic(field.getModifiers()) || kind.isPrimitive() || kind == String.class
                            || View.class.isAssignableFrom(kind) || List.class.isAssignableFrom(kind)) continue;
                    try {
                        field.setAccessible(true);
                        Object next = field.get(value);
                        if (next == null) continue;
                        Field[] longer = java.util.Arrays.copyOf(route, route.length + 1);
                        longer[route.length] = field;
                        queue.add(new Object[]{next, depth + 1, longer});
                    } catch (Throwable ignored) {
                        // Next field.
                    }
                }
            }
        }
        return null;
    }

    private static boolean isMessage(Object value) {
        return Reflect.method(value.getClass(), "getCreatedAt") != null && Reflect.method(value.getClass(), "getUuid") != null;
    }
}
