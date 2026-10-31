package com.fireremote.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** Circular navigation pad: four directional wedges around a central Select button. */
final class DpadView extends View {

    static final int NONE = -1, UP = 0, RIGHT = 1, DOWN = 2, LEFT = 3, CENTER = 4;

    interface Listener {
        void onKey(int which);
    }

    private Listener listener;
    private int pressed = NONE;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wedge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint center = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private final Runnable repeater = new Runnable() {
        @Override
        public void run() {
            if (pressed == NONE || pressed == CENTER) return;
            if (listener != null) listener.onKey(pressed);
            postDelayed(this, 200);
        }
    };

    DpadView(Context ctx) {
        super(ctx);
        ring.setColor(Theme.SURFACE);
        wedge.setColor(Theme.SURFACE_PRESSED);
        center.setColor(Theme.SURFACE_HIGH);
        centerRim.setStyle(Paint.Style.STROKE);
        centerRim.setColor(Theme.ACCENT);
        text.setColor(Theme.TEXT);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        setClickable(true);
        setContentDescription("Navigation pad");
    }

    void setListener(Listener l) {
        listener = l;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getMode(hSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(hSpec) : Math.min(w, Theme.dpi(getContext(), 290));
        setMeasuredDimension(w, h);
    }

    private float cx() { return getWidth() / 2f; }
    private float cy() { return getHeight() / 2f; }
    private float outerR() {
        float max = Theme.dp(getContext(), 160);
        return Math.min(max, Math.min(getWidth(), getHeight()) / 2f - Theme.dp(getContext(), 2));
    }
    private float innerR() { return outerR() * 0.38f; }

    @Override
    protected void onDraw(Canvas c) {
        float cx = cx(), cy = cy(), R = outerR(), r = innerR();
        c.drawCircle(cx, cy, R, ring);

        if (pressed >= UP && pressed <= LEFT) {
            oval.set(cx - R, cy - R, cx + R, cy + R);
            float start = -135 + pressed * 90; // UP wedge spans -135..-45 degrees
            c.drawArc(oval, start, 90, true, wedge);
        }

        float iconSize = R * 0.36f;
        float dist = (R + r) / 2f;
        for (int d = UP; d <= LEFT; d++) {
            c.save();
            c.rotate(d * 90, cx, cy);
            Icons.draw(c, Icons.CHEVRON, cx, cy - dist, iconSize, pressed == d ? Theme.ACCENT : Theme.MUTED);
            c.restore();
        }

        center.setColor(pressed == CENTER ? Theme.SURFACE_PRESSED : Theme.SURFACE_HIGH);
        float cr = pressed == CENTER ? r * 0.96f : r;
        c.drawCircle(cx, cy, cr, center);
        centerRim.setStrokeWidth(Theme.dp(getContext(), 2));
        c.drawCircle(cx, cy, cr - Theme.dp(getContext(), 1), centerRim);
        text.setTextSize(Theme.sp(getContext(), 17));
        c.drawText("OK", cx, cy - (text.descent() + text.ascent()) / 2f, text);
    }

    private int hit(float x, float y) {
        float dx = x - cx(), dy = y - cy();
        double d = Math.sqrt(dx * dx + dy * dy);
        if (d <= innerR()) return CENTER;
        if (d > outerR() * 1.08) return NONE;
        if (Math.abs(dx) > Math.abs(dy)) return dx > 0 ? RIGHT : LEFT;
        return dy > 0 ? DOWN : UP;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = hit(e.getX(), e.getY());
                if (pressed == NONE) return false;
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if (listener != null) listener.onKey(pressed);
                if (pressed != CENTER) postDelayed(repeater, 450);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pressed = NONE;
                removeCallbacks(repeater);
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
