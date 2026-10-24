package com.golda.patchertiktok;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/**
 * The accent picker: a saturation/brightness square above a hue strip. The square only spans
 * colors the settings screen accepts as an accent, so grey or near-black picks are impossible.
 */
final class ColorPanel extends View {
    interface Listener {
        /** {@code done} is true when the finger lifts. */
        void onColor(int rgb, boolean done);
    }

    /** Below these the settings palette treats a color as "no accent" (see {@link Palette#settings}). */
    static final float MIN_SATURATION = 0.3f;
    static final float MIN_VALUE = 0.42f;
    private static final int GRID_W = 48;
    private static final int GRID_H = 32;

    private final float density;
    private final float[] hsv = {0f, 1f, 1f};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF square = new RectF();
    private final RectF strip = new RectF();
    private final int corner;
    private Bitmap plane;
    private float planeHue = -1f;
    private LinearGradient hues;
    private Listener listener;
    /** 0 = nothing, 1 = square, 2 = hue strip. */
    private int dragging;

    ColorPanel(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        corner = Math.round(12 * density);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.5f * density);
        ring.setColor(0xFFFFFFFF);
        ring.setShadowLayer(3 * density, 0, 0, 0x55000000);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setContentDescription("Color");
    }

    void setListener(Listener listener) { this.listener = listener; }

    void setColor(int rgb) {
        Color.colorToHSV(0xFF000000 | rgb, hsv);
        hsv[1] = Math.max(MIN_SATURATION, hsv[1]);
        hsv[2] = Math.max(MIN_VALUE, hsv[2]);
        invalidate();
    }

    int color() { return Color.HSVToColor(hsv) & 0xFFFFFF; }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = Math.round(width * 0.62f + 52 * density);
        setMeasuredDimension(width, height);
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        float knob = 11 * density;
        square.set(knob, knob, w - knob, w * 0.62f);
        float top = square.bottom + 20 * density;
        strip.set(knob, top, w - knob, top + 14 * density);
        int[] colors = new int[7];
        for (int i = 0; i < 7; i++) colors[i] = Color.HSVToColor(new float[]{i * 60f % 360f, 1f, 1f});
        colors[6] = colors[0];
        hues = new LinearGradient(strip.left, 0, strip.right, 0, colors, null, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (plane == null || planeHue != hsv[0]) buildPlane();
        canvas.save();
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(square, corner, corner, android.graphics.Path.Direction.CW);
        canvas.clipPath(clip);
        canvas.drawBitmap(plane, null, square, paint);
        canvas.restore();

        paint.setShader(hues);
        float radius = strip.height() / 2f;
        canvas.drawRoundRect(strip, radius, radius, paint);
        paint.setShader(null);

        float x = square.left + (hsv[1] - MIN_SATURATION) / (1f - MIN_SATURATION) * square.width();
        float y = square.top + (1f - (hsv[2] - MIN_VALUE) / (1f - MIN_VALUE)) * square.height();
        knob(canvas, x, y, Color.HSVToColor(hsv));
        float hx = strip.left + hsv[0] / 360f * strip.width();
        knob(canvas, hx, strip.centerY(), Color.HSVToColor(new float[]{hsv[0], 1f, 1f}));
    }

    private void knob(Canvas canvas, float x, float y, int fill) {
        float radius = 10 * density;
        paint.setColor(fill);
        canvas.drawCircle(x, y, radius, paint);
        canvas.drawCircle(x, y, radius, ring);
    }

    /** A small grid of the square's colors, stretched with filtering: smooth and cheap. */
    private void buildPlane() {
        if (plane == null) plane = Bitmap.createBitmap(GRID_W, GRID_H, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[GRID_W * GRID_H];
        float[] cell = {hsv[0], 0f, 0f};
        for (int row = 0; row < GRID_H; row++) {
            cell[2] = 1f - row / (float) (GRID_H - 1) * (1f - MIN_VALUE);
            for (int column = 0; column < GRID_W; column++) {
                cell[1] = MIN_SATURATION + column / (float) (GRID_W - 1) * (1f - MIN_SATURATION);
                pixels[row * GRID_W + column] = Color.HSVToColor(cell);
            }
        }
        plane.setPixels(pixels, 0, GRID_W, 0, 0, GRID_W, GRID_H);
        planeHue = hsv[0];
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                float slop = 16 * density;
                if (y <= square.bottom + slop / 2) dragging = 1;
                else if (y >= strip.top - slop) dragging = 2;
                else return false;
                getParent().requestDisallowInterceptTouchEvent(true);
                // Fall through: a tap moves the knob too.
            case MotionEvent.ACTION_MOVE:
                if (dragging == 1) {
                    hsv[1] = MIN_SATURATION + clamp((x - square.left) / square.width()) * (1f - MIN_SATURATION);
                    hsv[2] = MIN_VALUE + (1f - clamp((y - square.top) / square.height())) * (1f - MIN_VALUE);
                } else if (dragging == 2) {
                    hsv[0] = clamp((x - strip.left) / strip.width()) * 359.9f;
                }
                invalidate();
                if (listener != null) listener.onColor(color(), false);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging != 0 && listener != null) listener.onColor(color(), true);
                dragging = 0;
                return true;
            default:
                return true;
        }
    }

    private static float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }
}
