package com.fireremote.app;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Vector glyphs drawn on a 24x24 grid, so no image resources or icon fonts are needed. */
final class Icons {
    static final int POWER = 1, HOME = 2, BACK = 3, MENU = 4, PLAY_PAUSE = 5, REWIND = 6, FAST_FORWARD = 7,
            VOL_DOWN = 8, VOL_UP = 9, MUTE = 10, KEYBOARD = 11, GEAR = 12, WIFI = 13, CHEVRON = 14, GRID = 15, PLUS = 16;

    private Icons() {
    }

    /** Draws {@code icon} centred at (cx, cy) with the given size in pixels. */
    static void draw(Canvas c, int icon, float cx, float cy, float size, int color) {
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(color);
        stroke.setStrokeWidth(2f);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
        fill.setStrokeJoin(Paint.Join.ROUND);

        c.save();
        float s = size / 24f;
        c.translate(cx - size / 2f, cy - size / 2f);
        c.scale(s, s);
        Path p = new Path();
        switch (icon) {
            case POWER:
                c.drawArc(new RectF(4, 5, 20, 21), -55, 290, false, stroke);
                c.drawLine(12, 3, 12, 12, stroke);
                break;
            case HOME:
                p.moveTo(3, 11.5f); p.lineTo(12, 4); p.lineTo(21, 11.5f);
                p.moveTo(6, 9.5f); p.lineTo(6, 20); p.lineTo(10, 20); p.lineTo(10, 14.5f);
                p.lineTo(14, 14.5f); p.lineTo(14, 20); p.lineTo(18, 20); p.lineTo(18, 9.5f);
                c.drawPath(p, stroke);
                break;
            case BACK:
                p.moveTo(9, 5); p.lineTo(4, 10); p.lineTo(9, 15);
                p.moveTo(4, 10); p.lineTo(14.5f, 10);
                p.cubicTo(18, 10, 20, 12.5f, 20, 15);
                p.cubicTo(20, 17.5f, 18, 20, 14.5f, 20);
                p.lineTo(11, 20);
                c.drawPath(p, stroke);
                break;
            case MENU:
                c.drawLine(5, 7, 19, 7, stroke);
                c.drawLine(5, 12, 19, 12, stroke);
                c.drawLine(5, 17, 19, 17, stroke);
                break;
            case PLAY_PAUSE:
                p.moveTo(3.5f, 6); p.lineTo(11.5f, 12); p.lineTo(3.5f, 18); p.close();
                c.drawPath(p, fill);
                c.drawRoundRect(new RectF(14, 6, 16.5f, 18), 0.8f, 0.8f, fill);
                c.drawRoundRect(new RectF(18.5f, 6, 21, 18), 0.8f, 0.8f, fill);
                break;
            case REWIND:
                p.moveTo(11.5f, 6); p.lineTo(3, 12); p.lineTo(11.5f, 18); p.close();
                p.moveTo(21, 6); p.lineTo(12.5f, 12); p.lineTo(21, 18); p.close();
                c.drawPath(p, fill);
                break;
            case FAST_FORWARD:
                p.moveTo(3, 6); p.lineTo(11.5f, 12); p.lineTo(3, 18); p.close();
                p.moveTo(12.5f, 6); p.lineTo(21, 12); p.lineTo(12.5f, 18); p.close();
                c.drawPath(p, fill);
                break;
            case VOL_DOWN:
            case VOL_UP:
            case MUTE:
                p.moveTo(3, 9); p.lineTo(7, 9); p.lineTo(12, 5); p.lineTo(12, 19); p.lineTo(7, 15);
                p.lineTo(3, 15); p.close();
                c.drawPath(p, fill);
                if (icon == MUTE) {
                    c.drawLine(15.5f, 9.5f, 20.5f, 14.5f, stroke);
                    c.drawLine(20.5f, 9.5f, 15.5f, 14.5f, stroke);
                } else {
                    c.drawLine(15, 12, 21, 12, stroke);
                    if (icon == VOL_UP) c.drawLine(18, 9, 18, 15, stroke);
                }
                break;
            case KEYBOARD:
                c.drawRoundRect(new RectF(2.5f, 6, 21.5f, 18), 2, 2, stroke);
                for (int i = 0; i < 5; i++) c.drawPoint(6 + i * 3, 9.8f, strokeDot(stroke));
                for (int i = 0; i < 4; i++) c.drawPoint(7.5f + i * 3, 12.3f, strokeDot(stroke));
                c.drawLine(8, 15, 16, 15, stroke);
                break;
            case GEAR:
                c.drawCircle(12, 12, 3, stroke);
                c.drawCircle(12, 12, 7, stroke);
                for (int i = 0; i < 8; i++) {
                    c.save();
                    c.rotate(i * 45, 12, 12);
                    c.drawLine(12, 2.5f, 12, 5, stroke);
                    c.restore();
                }
                break;
            case WIFI:
                c.drawArc(new RectF(2, 6, 22, 26), -135, 90, false, stroke);
                c.drawArc(new RectF(5.5f, 9.5f, 18.5f, 22.5f), -135, 90, false, stroke);
                c.drawArc(new RectF(9, 13, 15, 19), -135, 90, false, stroke);
                c.drawCircle(12, 19.5f, 1.4f, fill);
                break;
            case CHEVRON: // points up; callers rotate the canvas
                p.moveTo(6, 15); p.lineTo(12, 9); p.lineTo(18, 15);
                stroke.setStrokeWidth(2.6f);
                c.drawPath(p, stroke);
                break;
            case GRID:
                for (int i = 0; i < 3; i++) {
                    for (int j = 0; j < 3; j++) c.drawCircle(6 + i * 6, 6 + j * 6, 1.9f, fill);
                }
                break;
            case PLUS:
                c.drawLine(12, 5, 12, 19, stroke);
                c.drawLine(5, 12, 19, 12, stroke);
                break;
            default:
                break;
        }
        c.restore();
    }

    private static Paint strokeDot(Paint base) {
        Paint d = new Paint(base);
        d.setStrokeWidth(2.2f);
        return d;
    }
}
