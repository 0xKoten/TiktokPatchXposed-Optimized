package com.golda.patchertiktok;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** The quality picker: a sheet that slides up from the bottom in the module's own colors. */
final class DownloadSheet {
    static final class Row {
        final String title;
        final String detail;
        final Glyph.Kind icon;
        final int tile;
        final boolean best;
        /** A download arrow at the end; off for rows that are not downloads. */
        boolean arrow = true;

        Row(String title, String detail, Glyph.Kind icon, int tile, boolean best) {
            this.title = title;
            this.detail = detail;
            this.icon = icon;
            this.tile = tile;
            this.best = best;
        }
    }

    interface Pick { void picked(int index); }

    static final int VIDEO_TILE = 0xFF20D5EC;
    static final int AUDIO_TILE = 0xFF8C6CF5;
    static final int PHOTO_TILE = 0xFFFF9F1A;
    static final int APP_TILE = 0xFF8A8B91;

    private final Activity activity;
    private final Palette palette;
    private final Dialog dialog;
    private final LinearLayout sheet;
    private boolean closing;

    private DownloadSheet(Activity activity) {
        this.activity = activity;
        int night = activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        palette = Palette.of(night != Configuration.UI_MODE_NIGHT_NO);
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        sheet = new DragSheet();
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(0, dp(8), 0, dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(palette.dark ? 0xFF1A1A1A : palette.card);
        float radius = dp(20);
        background.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        sheet.setBackground(background);
    }

    /** Shows the sheet; {@code dismissed} runs once when it closes, after {@code pick} if a row was chosen. */
    static void show(Activity activity, String heading, String subheading, List<Row> rows, Pick pick, Runnable dismissed) {
        new DownloadSheet(activity).open(heading, subheading, rows, pick, dismissed);
    }

    private void open(String heading, String subheading, List<Row> rows, Pick pick, Runnable dismissed) {
        View handle = new View(activity);
        GradientDrawable bar = new GradientDrawable();
        bar.setColor(palette.dark ? 0xFF4A4A4A : 0xFFD6D6D8);
        bar.setCornerRadius(dp(2));
        handle.setBackground(bar);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(36), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(10);
        sheet.addView(handle, handleParams);

        TextView title = text(heading, 16, palette.textPrimary, true);
        title.setPadding(dp(20), 0, dp(20), 0);
        sheet.addView(title);
        if (!TextUtils.isEmpty(subheading)) {
            TextView sub = text(subheading, 12, palette.textSecondary, false);
            sub.setPadding(dp(20), dp(2), dp(20), 0);
            sheet.addView(sub);
        }

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(6), 0, dp(4));
        for (int index = 0; index < rows.size(); index++) {
            int chosen = index;
            View row = row(rows.get(index));
            row.setOnClickListener(view -> {
                if (closing) return;
                pick.picked(chosen);
                close();
            });
            list.addView(row);
        }
        int maxHeight = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.55f);
        ScrollView scroll = new ScrollView(activity) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(list);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

        TextView cancel = text(activity.getString(android.R.string.cancel), 14, palette.textPrimary, true);
        cancel.setGravity(Gravity.CENTER);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(palette.field);
        pill.setCornerRadius(dp(12));
        cancel.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.pressed), pill, null));
        cancel.setOnClickListener(view -> close());
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(-1, dp(42));
        cancelParams.setMargins(dp(16), dp(4), dp(16), 0);
        sheet.addView(cancel, cancelParams);

        dialog.setContentView(sheet);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnKeyListener((window, code, event) -> {
            if (code != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) close();
            return true;
        });
        dialog.setOnDismissListener(window -> { if (dismissed != null) dismissed.run(); });
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.setWindowAnimations(0);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.55f);
        }
        sheet.setAlpha(0f);
        dialog.show();
        sheet.post(() -> {
            sheet.setTranslationY(sheet.getHeight());
            sheet.setAlpha(1f);
            sheet.animate().translationY(0).setDuration(320)
                    .setInterpolator(new PathInterpolator(0.2f, 0.9f, 0.25f, 1f)).start();
            for (int index = 0; index < list.getChildCount(); index++) {
                View child = list.getChildAt(index);
                child.setAlpha(0f);
                child.setTranslationY(dp(14));
                child.animate().alpha(1f).translationY(0).setStartDelay(90 + 45L * index).setDuration(260)
                        .setInterpolator(new DecelerateInterpolator(1.6f)).start();
            }
        });
    }

    private View row(Row item) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(52));
        row.setPadding(dp(20), dp(6), dp(16), dp(6));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.pressed), null, new ColorDrawable(0xFFFFFFFF)));

        ImageView tile = new ImageView(activity);
        GradientDrawable square = new GradientDrawable();
        square.setColor(item.tile);
        square.setCornerRadius(dp(10));
        tile.setBackground(square);
        tile.setScaleType(ImageView.ScaleType.CENTER);
        tile.setImageDrawable(new Glyph(item.icon, 0xFFFFFFFF, dp(18)));
        LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(dp(34), dp(34));
        tileParams.rightMargin = dp(12);
        row.addView(tile, tileParams);

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout line = new LinearLayout(activity);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(text(item.title, 15, palette.textPrimary, true));
        if (item.best) {
            TextView badge = text(I18n.get(I18n.S.DL_BEST), 11, 0xFFFFFFFF, true);
            GradientDrawable chip = new GradientDrawable();
            chip.setColor(palette.accent);
            chip.setCornerRadius(dp(8));
            badge.setBackground(chip);
            badge.setPadding(dp(7), dp(2), dp(7), dp(2));
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, -2);
            badgeParams.leftMargin = dp(8);
            line.addView(badge, badgeParams);
        }
        texts.addView(line);
        if (!TextUtils.isEmpty(item.detail)) {
            TextView detail = text(item.detail, 12, palette.textSecondary, false);
            detail.setPadding(0, dp(1), 0, 0);
            texts.addView(detail);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        if (item.arrow) {
            ImageView arrow = new ImageView(activity);
            arrow.setImageDrawable(new Glyph(Glyph.Kind.DOWNLOAD, palette.chevron, dp(18)));
            row.addView(arrow, new LinearLayout.LayoutParams(dp(22), dp(22)));
        }
        return row;
    }

    private void close() {
        if (closing) return;
        closing = true;
        sheet.animate().cancel();
        sheet.animate().translationY(sheet.getHeight()).setStartDelay(0).setDuration(200)
                .setInterpolator(new PathInterpolator(0.4f, 0f, 1f, 1f))
                .withEndAction(() -> {
                    try {
                        dialog.dismiss();
                    } catch (RuntimeException ignored) {
                        // The activity went away while the sheet was closing.
                    }
                }).start();
    }

    /** Follows the finger when dragged down; a long or quick drag closes it, a short one springs back. */
    private final class DragSheet extends LinearLayout {
        private final int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        private float startY;
        private boolean dragging;
        private VelocityTracker velocity;

        DragSheet() { super(activity); }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startY = event.getRawY();
                    dragging = false;
                    track(event);
                    break;
                case MotionEvent.ACTION_MOVE:
                    track(event);
                    if (!closing && event.getRawY() - startY > slop) {
                        dragging = true;
                        startY = event.getRawY();
                        return true;
                    }
                    break;
                default:
                    break;
            }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            track(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startY = event.getRawY();
                    dragging = true;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging) setTranslationY(Math.max(0, event.getRawY() - startY));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    float speed = 0;
                    if (velocity != null) {
                        velocity.computeCurrentVelocity(1000);
                        speed = velocity.getYVelocity();
                        velocity.recycle();
                        velocity = null;
                    }
                    dragging = false;
                    if (getTranslationY() > getHeight() * 0.25f || (speed > 1200 && getTranslationY() > 0)) {
                        close();
                    } else {
                        animate().translationY(0).setStartDelay(0).setDuration(180)
                                .setInterpolator(new DecelerateInterpolator()).start();
                    }
                    return true;
                default:
                    return true;
            }
        }

        private void track(MotionEvent event) {
            if (velocity == null) velocity = VelocityTracker.obtain();
            MotionEvent raw = MotionEvent.obtain(event);
            raw.setLocation(event.getRawX(), event.getRawY());
            velocity.addMovement(raw);
            raw.recycle();
        }
    }

    private TextView text(String value, float sp, int color, boolean medium) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        if (medium) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
