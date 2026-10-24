package com.golda.patchertiktok;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * The module's switch:
 * <ul>
 *   <li>the thumb widens as soon as the finger is down, so the press is felt before the flip;</li>
 *   <li>it travels with a strong ease-out and no bounce: a tap carries no momentum to overshoot;</li>
 *   <li>the check mark is drawn along its stroke as the color arrives.</li>
 * </ul>
 * Every change starts from the current position, so a second tap reverses it mid-way.
 * The owning row handles clicks and calls {@link #press}.
 */
@android.annotation.SuppressLint("ViewConstructor")
final class Toggle extends View {
    /** Strong ease-out, cubic-bezier(0.23, 1, 0.32, 1). */
    private static final Interpolator EASE = new PathInterpolator(0.23f, 1f, 0.32f, 1f);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path check = new Path();
    private final Path drawn = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final Palette palette;
    private ValueAnimator animator;
    private ValueAnimator pressAnimator;
    private boolean checked;
    /** Position of the thumb: 0 off, 1 on; overshoots slightly while springing. */
    private float progress;
    /** Color and check mark, eased without overshoot. */
    private float tint;
    /** How far the thumb has widened under the finger. */
    private float pressed;

    Toggle(Context context, Palette palette) {
        super(context);
        this.palette = palette;
        mark.setStyle(Paint.Style.STROKE);
        mark.setStrokeCap(Paint.Cap.ROUND);
        mark.setStrokeJoin(Paint.Join.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    boolean checked() { return checked; }

    void setChecked(boolean value, boolean animate) {
        if (animator != null) animator.cancel();
        checked = value;
        float target = value ? 1f : 0f;
        if (!animate || !isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) {
            progress = target;
            tint = target;
            invalidate();
            return;
        }
        // Starts from where the thumb is now, so a quick second tap reverses it mid-way.
        float startProgress = progress;
        float startTint = tint;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(220);
        animator.setInterpolator(EASE);
        animator.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            progress = startProgress + (target - startProgress) * t;
            tint = startTint + (target - startTint) * t;
            invalidate();
        });
        animator.start();
        press(false);
    }

    /** The finger went down on (true) or left (false) the row. */
    void press(boolean down) {
        if (pressAnimator != null) pressAnimator.cancel();
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        pressAnimator = ValueAnimator.ofFloat(pressed, down ? 1f : 0f);
        pressAnimator.setDuration(down ? 120 : 200);
        pressAnimator.setInterpolator(EASE);
        pressAnimator.addUpdateListener(animation -> {
            pressed = (float) animation.getAnimatedValue();
            invalidate();
        });
        pressAnimator.start();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        float density = getResources().getDisplayMetrics().density;
        if (palette.mono) setMeasuredDimension(Math.round(44 * density), Math.round(26 * density));
        else setMeasuredDimension(Math.round(52 * density), Math.round(32 * density));
    }

    /**
     * Monochrome: a flat pill in the text color with a thumb cut out of the page color when on,
     * a dim pill with a grey thumb when off. No shadow, no mark: the fill says it.
     */
    private void drawMono(Canvas canvas, float width, float height) {
        paint.setColor(blend(palette.trackOff, palette.trackOn, tint));
        rect.set(0, 0, width, height);
        canvas.drawRoundRect(rect, height / 2f, height / 2f, paint);
        float inset = height * 0.14f;
        float radius = height / 2f - inset;
        float travel = width - 2 * (inset + radius);
        float center = inset + radius + travel * progress;
        float widen = radius * 0.3f * pressed;
        float left = center - radius - (checked ? widen : 0f);
        float right = center + radius + (checked ? 0f : widen);
        int onThumb = palette.trackOn == palette.textPrimary ? palette.background : 0xFFFFFFFF;
        paint.setColor(blend(palette.thumb, onThumb, tint));
        rect.set(left, height / 2f - radius, right, height / 2f + radius);
        canvas.drawRoundRect(rect, radius, radius, paint);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float width = getWidth();
        float height = getHeight();
        if (palette.mono) {
            drawMono(canvas, width, height);
            return;
        }
        paint.setColor(blend(palette.trackOff, palette.trackOn, tint));
        rect.set(0, 0, width, height);
        canvas.drawRoundRect(rect, height / 2f, height / 2f, paint);

        float inset = height * 0.125f;
        float radius = height / 2f - inset;
        float travel = width - 2 * (inset + radius);
        float center = inset + radius + travel * progress;
        // Widened under the finger, towards the side it is about to travel to.
        float widen = radius * 0.3f * pressed;
        float left = center - radius - (checked ? widen : 0f);
        float right = center + radius + (checked ? 0f : widen);
        left = Math.max(inset * 0.6f, left);
        right = Math.min(width - inset * 0.6f, right);
        paint.setColor(blend(palette.dark ? 0xFFC9D3E3 : 0xFFFFFFFF, palette.thumb, tint));
        paint.setShadowLayer(inset * 0.9f, 0, inset / 3f, 0x40000000);
        rect.set(left, height / 2f - radius, right, height / 2f + radius);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.clearShadowLayer();

        // The check mark is drawn along its stroke as the color arrives.
        float shown = Math.max(0f, (tint - 0.4f) / 0.6f);
        if (shown <= 0f) return;
        float size = radius * 0.9f;
        float cx = (left + right) / 2f;
        float cy = height / 2f;
        check.reset();
        check.moveTo(cx - size * 0.48f, cy + size * 0.02f);
        check.lineTo(cx - size * 0.12f, cy + size * 0.36f);
        check.lineTo(cx + size * 0.5f, cy - size * 0.32f);
        measure.setPath(check, false);
        drawn.reset();
        measure.getSegment(0, measure.getLength() * shown, drawn, true);
        mark.setColor(palette.trackOn);
        mark.setStrokeWidth(radius * 0.24f);
        canvas.drawPath(drawn, mark);
    }

    private static int blend(int from, int to, float amount) {
        amount = Math.max(0f, Math.min(1f, amount));
        int a = (int) (((from >>> 24) & 255) + (((to >>> 24) & 255) - ((from >>> 24) & 255)) * amount);
        int r = (int) (((from >> 16) & 255) + (((to >> 16) & 255) - ((from >> 16) & 255)) * amount);
        int g = (int) (((from >> 8) & 255) + (((to >> 8) & 255) - ((from >> 8) & 255)) * amount);
        int b = (int) ((from & 255) + ((to & 255) - (from & 255)) * amount);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
