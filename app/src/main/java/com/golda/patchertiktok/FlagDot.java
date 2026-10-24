package com.golda.patchertiktok;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** A country flag cropped to a small circle: the flag emoji drawn oversized and clipped. */
final class FlagDot extends Drawable {
    private final String emoji;
    private final int size;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path circle = new Path();

    FlagDot(String emoji, int size) {
        this.emoji = emoji;
        this.size = size;
        // Emoji flags are wider than tall and have a margin; this size makes the flag cover the circle.
        paint.setTextSize(size * 1.6f);
        paint.setTextAlign(Paint.Align.CENTER);
        setBounds(0, 0, size, size);
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        circle.reset();
        circle.addCircle(bounds.exactCenterX(), bounds.exactCenterY(), bounds.width() / 2f, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(circle);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = bounds.exactCenterY() - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(emoji, bounds.exactCenterX(), baseline, paint);
        canvas.restore();
    }

    @Override public int getIntrinsicWidth() { return size; }

    @Override public int getIntrinsicHeight() { return size; }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }

    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
