package com.golda.patchertiktok;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saves a comment's sticker, GIF or photos. A long press on a comment opens TikTok's share panel
 * while the finger is still down; the comment under that finger is read from its list cell, and if
 * it carries media, "Save sticker" or "Save photo" joins the panel under its send-to row.
 */
final class CommentMedia {
    private static final String COMMENT = "com.ss.android.ugc.aweme.comment.model.Comment";
    private static final String ROW = "com.golda.patchertiktok.commentMediaRow";

    private static Class<?> commentType;

    private CommentMedia() { }

    static void install(ClassLoader loader) {
        commentType = Hooks.findClass(COMMENT, loader);
        if (commentType == null) {
            RuntimeLog.log("comment media unavailable: comment model not found");
            return;
        }
        Touch.install();
        Hooks.findAndHook(Dialog.class, "show", new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                if (!Touch.holding(3_000L) || !Prefs.on(Prefs.COMMENT_MEDIA)) return;
                View root = Touch.window();
                Dialog dialog = (Dialog) param.thisObject;
                if (root == null || dialog.getWindow() == null) return;
                Object comment = commentAt(root, Touch.x(), Touch.y());
                if (comment == null) return;
                List<DownloadModels.Item> media = media(comment);
                if (media.isEmpty()) {
                    return;
                }
                boolean sticker = Reflect.get(comment, "stickerStruct") != null;
                View decor = dialog.getWindow().getDecorView();
                ViewTreeObserver observer = decor.getViewTreeObserver();
                observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                    private int frames;

                    @Override public boolean onPreDraw() {
                        LinearLayout block = sendBlock(decor);
                        if (block == null && ++frames < 30) return true;
                        ViewTreeObserver live = observer.isAlive() ? observer : decor.getViewTreeObserver();
                        if (live.isAlive()) live.removeOnPreDrawListener(this);
                        if (block == null || decor.findViewWithTag(ROW) != null) return true;
                        addRow(block, dialog, comment, media, sticker);
                        return false;
                    }
                });
            }
        });
    }

    /** The comment whose list row is under the screen point, if any. */

    private static Object commentAt(View root, float x, float y) {
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        int[] at = new int[2];
        while (!queue.isEmpty()) {
            View view = queue.poll();
            if (view.getVisibility() != View.VISIBLE) continue;
            view.getLocationOnScreen(at);
            if (x < at[0] || y < at[1] || x >= at[0] + view.getWidth() || y >= at[1] + view.getHeight()) continue;
            if (Views.isRecycler(view)) {
                ViewGroup list = (ViewGroup) view;
                for (int i = 0; i < list.getChildCount(); i++) {
                    View child = list.getChildAt(i);
                    child.getLocationOnScreen(at);
                    if (x < at[0] || y < at[1] || x >= at[0] + child.getWidth() || y >= at[1] + child.getHeight()) continue;
                    // A PowerList cell keeps the row's model in "item"; the comment sits in or near it.
                    Object holder = holder(view, child);
                    Object comment = find(Reflect.get(holder, "item"));
                    if (comment == null) comment = find(holder);
                    if (comment != null) return comment;
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
            }
        }
        return null;
    }

    private static Object holder(View list, View child) {
        try {
            Method method = list.getClass().getMethod("getChildViewHolder", View.class);
            return method.invoke(list, child);
        } catch (Throwable error) {
            return null;
        }
    }

    /** A Comment held by the list cell, a few references deep (cell → presenter → comment). */
    private static Object find(Object start) {
        if (start == null) return null;
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{start, 0});
        while (!queue.isEmpty() && seen.size() < 300) {
            Object[] entry = queue.poll();
            Object value = entry[0];
            int depth = (Integer) entry[1];
            if (value == null || seen.put(value, Boolean.TRUE) != null) continue;
            if (commentType.isInstance(value)) return value;
            if (depth >= 3) continue;
            for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                String name = type.getName();
                if (name.startsWith("android.") || name.startsWith("java.")) break;
                for (Field field : type.getDeclaredFields()) {
                    Class<?> kind = field.getType();
                    if (Modifier.isStatic(field.getModifiers()) || kind.isPrimitive() || kind == String.class
                            || View.class.isAssignableFrom(kind) || Drawable.class.isAssignableFrom(kind)
                            || android.content.Context.class.isAssignableFrom(kind)
                            || java.util.Map.class.isAssignableFrom(kind)) continue;
                    try {
                        field.setAccessible(true);
                        Object next = field.get(value);
                        if (next != null) queue.add(new Object[]{next, depth + 1});
                    } catch (Throwable ignored) { }
                }
            }
        }
        return null;
    }

    /** What can be saved: the sticker or GIF in its best size, or each attached photo. */
    static List<DownloadModels.Item> media(Object comment) {
        List<DownloadModels.Item> items = new ArrayList<>();
        Object sticker = Reflect.get(comment, "stickerStruct");
        if (sticker != null) {
            for (String kind : new String[]{"animatedUrl", "staticUrl"}) {
                Object struct = Reflect.get(sticker, kind);
                for (String size : new String[]{"highResolutionUrl", "midResolutionUrl", "lowResolutionUrl"}) {
                    DownloadModels.Item item = DownloadModels.picture(Reflect.get(struct, size));
                    if (item != null) return Collections.singletonList(item);
                }
            }
        }
        Object images = Reflect.get(comment, "imageList");
        if (images instanceof List) {
            for (Object image : (List<?>) images) {
                DownloadModels.Item item = DownloadModels.picture(DownloadModels.photoAddress(image));
                if (item != null) items.add(item);
            }
        }
        return items;
    }


    /** The panel's send-to block: the first entry of its list, a vertical title-and-contacts column. */
    private static LinearLayout sendBlock(View decor) {
        View list = AutoScroll.shallowestList(decor);
        if (!(list instanceof ViewGroup) || ((ViewGroup) list).getChildCount() == 0) return null;
        View first = ((ViewGroup) list).getChildAt(0);
        for (int depth = 0; depth < 3 && first != null; depth++) {
            if (first instanceof LinearLayout && ((LinearLayout) first).getOrientation() == LinearLayout.VERTICAL) {
                return (LinearLayout) first;
            }
            first = first instanceof ViewGroup && ((ViewGroup) first).getChildCount() > 0
                    ? ((ViewGroup) first).getChildAt(0) : null;
        }
        return null;
    }

    private static void addRow(LinearLayout block, Dialog dialog, Object comment, List<DownloadModels.Item> media,
            boolean sticker) {
        float density = block.getResources().getDisplayMetrics().density;
        View list = AutoScroll.shallowestList(dialog.getWindow().getDecorView());
        TextView sample = sampleRowText(list);
        int color = sample != null ? sample.getCurrentTextColor() : Color.WHITE;
        View sampleRow = sample == null ? null : rowOf(sample, list);

        LinearLayout row = new LinearLayout(block.getContext());
        row.setTag(ROW);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int side = Math.round(16 * density);
        row.setPadding(side, 0, side, 0);
        GradientDrawable card = new GradientDrawable();
        int fill = sampleRow == null ? 0 : colorOf(sampleRow.getBackground());
        card.setColor(fill != 0 ? fill : (luminance(color) > 0.5f ? 0x1AFFFFFF : 0x0D000000));
        card.setCornerRadius(12 * density);
        row.setBackground(card);

        ImageView icon = new ImageView(block.getContext());
        icon.setImageDrawable(new Glyph(Glyph.Kind.DOWNLOAD, color, Math.round(22 * density)));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(Math.round(22 * density), Math.round(22 * density));
        iconParams.rightMargin = Math.round(12 * density);
        row.addView(icon, iconParams);

        TextView title = new TextView(block.getContext());
        title.setText(sticker ? I18n.get(I18n.S.CM_SAVE_STICKER) : media.size() > 1
                ? String.format(java.util.Locale.ROOT, I18n.get(I18n.S.CM_SAVE_PHOTOS), media.size())
                : I18n.get(I18n.S.CM_SAVE_PHOTO));
        title.setTextColor(color);
        if (sample != null) {
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sample.getTextSize());
            title.setTypeface(sample.getTypeface());
        } else {
            title.setTextSize(15);
        }
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        row.setOnClickListener(view -> {
            Object id = Reflect.call(comment, "getCid");
            DownloadsPlus.save(dialog.getContext(), "comment_" + id, media);
            try {
                dialog.dismiss();
            } catch (RuntimeException ignored) {
                // Already closing.
            }
        });

        alignTo(row, icon, iconParams, sample, sampleRow);
        int height = sampleRow != null && sampleRow.getHeight() > 0 ? sampleRow.getHeight() : Math.round(52 * density);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, height);
        int margin = Math.round(16 * density);
        if (sampleRow != null) {
            int[] rowAt = new int[2];
            int[] blockAt = new int[2];
            sampleRow.getLocationInWindow(rowAt);
            block.getLocationInWindow(blockAt);
            margin = Math.max(0, rowAt[0] - blockAt[0]);
        }
        params.setMargins(margin, Math.round(8 * density), margin, Math.round(4 * density));
        block.addView(row, params);
    }

    /** Puts the icon and the title where the panel's own rows have theirs. */
    private static void alignTo(LinearLayout row, ImageView icon, LinearLayout.LayoutParams iconParams,
            TextView sample, View sampleRow) {
        if (sample == null || sampleRow == null || !(sample.getParent() instanceof ViewGroup)) return;
        ViewGroup line = (ViewGroup) sample.getParent();
        View theirIcon = line.getChildAt(0);
        if (theirIcon == null || theirIcon == sample || theirIcon.getWidth() == 0) return;
        int[] rowAt = new int[2];
        int[] iconAt = new int[2];
        int[] textAt = new int[2];
        sampleRow.getLocationInWindow(rowAt);
        theirIcon.getLocationInWindow(iconAt);
        sample.getLocationInWindow(textAt);
        int left = iconAt[0] - rowAt[0];
        int gap = textAt[0] - iconAt[0] - theirIcon.getWidth();
        if (left < 0 || gap < 0) return;
        row.setPadding(left, row.getPaddingTop(), row.getPaddingRight(), row.getPaddingBottom());
        iconParams.width = theirIcon.getWidth();
        iconParams.height = theirIcon.getHeight();
        iconParams.rightMargin = gap;
        icon.setLayoutParams(iconParams);
        icon.setScaleType(ImageView.ScaleType.CENTER);
    }

    /** A label of one of the panel's plain rows ("Report", "Repost comment"). */
    private static TextView sampleRowText(View list) {
        if (!(list instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) list;
        for (int i = group.getChildCount() - 1; i >= 1; i--) {
            TextView text = Views.firstText(group.getChildAt(i));
            if (text != null) return text;
        }
        return null;
    }

    private static View rowOf(View text, View list) {
        View row = text;
        while (row.getParent() instanceof View && row.getParent() != list) row = (View) row.getParent();
        return row;
    }

    private static int colorOf(Drawable drawable) {
        if (drawable instanceof ColorDrawable) return ((ColorDrawable) drawable).getColor();
        if (drawable instanceof GradientDrawable && ((GradientDrawable) drawable).getColor() != null) {
            return ((GradientDrawable) drawable).getColor().getDefaultColor();
        }
        return 0;
    }

    private static float luminance(int color) {
        return (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f;
    }

}
