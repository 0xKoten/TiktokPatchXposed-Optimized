package com.golda.patchertiktok;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** A row of dots: one per setting in a group, filled for those that are on. */
@android.annotation.SuppressLint("ViewConstructor")
final class Meter extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int on;
    private final int off;
    private int total;
    private int filled;

    Meter(Context context, int on, int off) {
        super(context);
        this.on = on;
        this.off = off;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void set(int filled, int total) {
        this.filled = filled;
        this.total = total;
        requestLayout();
        invalidate();
    }

    /** Up to this many dots keep their full size; longer rows shrink to the same width, so the title keeps its line. */
    private static final int ROOM = 8;

    private float size() {
        float full = getResources().getDisplayMetrics().density * 5f;
        return total <= ROOM ? full : Math.max(full * 0.7f, full * ROOM / total);
    }

    private float step() { return size() * (total <= ROOM ? 1.8f : 1.6f); }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        float size = size();
        setMeasuredDimension(Math.round((total - 1) * step() + size), Math.round(size));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float size = size();
        float step = step();
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        for (int index = 0; index < total; index++) {
            paint.setColor(index < filled ? on : off);
            float x = index * step + size / 2f;
            canvas.drawCircle(rtl ? getWidth() - x : x, size / 2f, size / 2f, paint);
        }
    }
}
