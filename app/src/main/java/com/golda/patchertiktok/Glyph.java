package com.golda.patchertiktok;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Small stroke icons drawn in code, sized on a 24-unit grid like TikTok's icons. */
final class Glyph extends Drawable {
    enum Kind { BACK, CHEVRON, CHECK, SEARCH, FEED, PLAY, PALETTE, DOWNLOAD, COMMENT, GLOBE, GHOST, FLAME, INFO, NOTE, IMAGE, SCROLL, FLASK, EYE }

    private final Kind kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int size;

    Glyph(Kind kind, int color, int sizePx) {
        this.kind = kind;
        this.size = sizePx;
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override public int getIntrinsicWidth() { return size; }

    @Override public int getIntrinsicHeight() { return size; }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float unit = Math.min(bounds.width(), bounds.height()) / 24f;
        canvas.save();
        canvas.translate(bounds.left + (bounds.width() - 24 * unit) / 2f, bounds.top + (bounds.height() - 24 * unit) / 2f);
        canvas.scale(unit, unit);
        path.reset();
        switch (kind) {
            case BACK:
                paint.setStrokeWidth(2.2f);
                path.moveTo(20.5f, 12f);
                path.lineTo(3.5f, 12f);
                path.moveTo(10.5f, 4.5f);
                path.lineTo(3.2f, 12f);
                path.lineTo(10.5f, 19.5f);
                break;
            case CHEVRON:
                paint.setStrokeWidth(1.9f);
                path.moveTo(9f, 5f);
                path.lineTo(16f, 12f);
                path.lineTo(9f, 19f);
                break;
            case CHECK:
                paint.setStrokeWidth(2.4f);
                path.moveTo(4.5f, 12.5f);
                path.lineTo(9.5f, 17.5f);
                path.lineTo(19.5f, 6.5f);
                break;
            case SEARCH:
                paint.setStrokeWidth(2f);
                path.addCircle(10.5f, 10.5f, 6.5f, Path.Direction.CW);
                path.moveTo(15.5f, 15.5f);
                path.lineTo(20.5f, 20.5f);
                break;
            case FEED:
                // Stacked cards: the feed.
                paint.setStrokeWidth(2f);
                path.addRoundRect(6f, 3.5f, 18f, 16.5f, 2.5f, 2.5f, Path.Direction.CW);
                path.moveTo(8f, 20.5f);
                path.lineTo(16f, 20.5f);
                break;
            case PLAY:
                paint.setStrokeWidth(2f);
                path.moveTo(8f, 5f);
                path.lineTo(19f, 12f);
                path.lineTo(8f, 19f);
                path.close();
                break;
            case PALETTE:
                paint.setStrokeWidth(2f);
                path.addRoundRect(3.5f, 5f, 20.5f, 19f, 3f, 3f, Path.Direction.CW);
                path.moveTo(3.5f, 9.5f);
                path.lineTo(20.5f, 9.5f);
                path.moveTo(9f, 9.5f);
                path.lineTo(9f, 19f);
                break;
            case DOWNLOAD:
                paint.setStrokeWidth(2f);
                path.moveTo(12f, 3.5f);
                path.lineTo(12f, 15f);
                path.moveTo(7f, 10.5f);
                path.lineTo(12f, 15.5f);
                path.lineTo(17f, 10.5f);
                path.moveTo(4.5f, 20f);
                path.lineTo(19.5f, 20f);
                break;
            case IMAGE:
                paint.setStrokeWidth(2f);
                path.addRoundRect(3.5f, 4.5f, 20.5f, 19.5f, 3f, 3f, Path.Direction.CW);
                path.moveTo(4f, 17f);
                path.lineTo(9f, 12f);
                path.lineTo(13f, 16f);
                path.lineTo(15.5f, 13.5f);
                path.lineTo(20f, 18f);
                path.addCircle(15.5f, 9f, 1.5f, Path.Direction.CW);
                break;
            case SCROLL:
                paint.setStrokeWidth(2f);
                path.moveTo(6.5f, 5.5f);
                path.lineTo(12f, 11f);
                path.lineTo(17.5f, 5.5f);
                path.moveTo(6.5f, 13f);
                path.lineTo(12f, 18.5f);
                path.lineTo(17.5f, 13f);
                break;
            case NOTE:
                paint.setStrokeWidth(2f);
                path.moveTo(9f, 17.5f);
                path.lineTo(9f, 5.5f);
                path.lineTo(19f, 3.5f);
                path.lineTo(19f, 15.5f);
                path.addCircle(6.5f, 17.5f, 2.5f, Path.Direction.CW);
                path.addCircle(16.5f, 15.5f, 2.5f, Path.Direction.CW);
                break;
            case COMMENT:
                paint.setStrokeWidth(2f);
                path.moveTo(5.5f, 4.5f);
                path.lineTo(18.5f, 4.5f);
                path.quadTo(20.5f, 4.5f, 20.5f, 6.5f);
                path.lineTo(20.5f, 14f);
                path.quadTo(20.5f, 16f, 18.5f, 16f);
                path.lineTo(10f, 16f);
                path.lineTo(5.5f, 20f);
                path.lineTo(5.5f, 16f);
                path.quadTo(3.5f, 16f, 3.5f, 14f);
                path.lineTo(3.5f, 6.5f);
                path.quadTo(3.5f, 4.5f, 5.5f, 4.5f);
                path.close();
                break;
            case GLOBE:
                paint.setStrokeWidth(1.9f);
                path.addCircle(12f, 12f, 8.5f, Path.Direction.CW);
                path.addOval(8.2f, 3.5f, 15.8f, 20.5f, Path.Direction.CW);
                path.moveTo(3.5f, 12f);
                path.lineTo(20.5f, 12f);
                break;
            case GHOST:
                paint.setStrokeWidth(2f);
                path.moveTo(5f, 20f);
                path.lineTo(5f, 11f);
                path.cubicTo(5f, 2f, 19f, 2f, 19f, 11f);
                path.lineTo(19f, 20f);
                path.lineTo(16.5f, 18f);
                path.lineTo(14f, 20f);
                path.lineTo(12f, 18f);
                path.lineTo(10f, 20f);
                path.lineTo(7.5f, 18f);
                path.close();
                path.addCircle(9.5f, 10.5f, 0.6f, Path.Direction.CW);
                path.addCircle(14.5f, 10.5f, 0.6f, Path.Direction.CW);
                break;
            case FLAME:
                paint.setStrokeWidth(2f);
                path.moveTo(12f, 21f);
                path.cubicTo(7f, 21f, 5f, 17.5f, 5.5f, 14f);
                path.cubicTo(6f, 10.5f, 9f, 9f, 9.5f, 5f);
                path.cubicTo(12f, 7f, 13f, 9f, 12.5f, 11.5f);
                path.cubicTo(14f, 10.5f, 15f, 9.5f, 15.5f, 8f);
                path.cubicTo(18f, 10.5f, 19f, 13f, 18.5f, 15.5f);
                path.cubicTo(18f, 19f, 15.5f, 21f, 12f, 21f);
                path.close();
                break;
            case INFO:
                paint.setStrokeWidth(2f);
                path.addCircle(12f, 12f, 8.5f, Path.Direction.CW);
                path.moveTo(12f, 11f);
                path.lineTo(12f, 16.5f);
                path.addCircle(12f, 7.8f, 0.5f, Path.Direction.CW);
                break;
            case FLASK:
                // A lab flask: neck, wide base, a liquid line.
                paint.setStrokeWidth(2f);
                path.moveTo(9.5f, 3.5f);
                path.lineTo(14.5f, 3.5f);
                path.moveTo(10.5f, 3.5f);
                path.lineTo(10.5f, 9.5f);
                path.lineTo(5.2f, 18.2f);
                path.quadTo(4.5f, 20.5f, 7f, 20.5f);
                path.lineTo(17f, 20.5f);
                path.quadTo(19.5f, 20.5f, 18.8f, 18.2f);
                path.lineTo(13.5f, 9.5f);
                path.lineTo(13.5f, 3.5f);
                path.moveTo(7.5f, 14.5f);
                path.lineTo(16.5f, 14.5f);
                break;
            case EYE:
                paint.setStrokeWidth(1.9f);
                path.moveTo(3f, 12f);
                path.quadTo(12f, 4f, 21f, 12f);
                path.quadTo(12f, 20f, 3f, 12f);
                path.addCircle(12f, 12f, 3f, Path.Direction.CW);
                break;
        }
        canvas.drawPath(path, paint);
        canvas.restore();
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }

    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
