package com.fireremote.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** A rounded remote-control key showing either a vector glyph or a short text label. */
final class RemoteButton extends View {

    interface OnPress {
        void onPress();
    }

    private final int icon;
    private final String label;
    private final boolean round;
    private int bg = Theme.SURFACE;
    private int bgPressed = Theme.SURFACE_PRESSED;
    private int fg = Theme.TEXT;
    private boolean repeat;
    private OnPress onPress;
    private boolean pressedNow;
    private float labelSp = 14;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final Runnable repeater = new Runnable() {
        @Override
        public void run() {
            if (!pressedNow) return;
            fire(false);
            postDelayed(this, 220);
        }
    };

    RemoteButton(Context ctx, int icon, String label, boolean round) {
        super(ctx);
        this.icon = icon;
        this.label = label;
        this.round = round;
        textPaint.setColor(fg);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        setClickable(true);
        setFocusable(true);
        if (label != null) setContentDescription(label);
    }

    RemoteButton colors(int bg, int bgPressed, int fg) {
        this.bg = bg;
        this.bgPressed = bgPressed;
        this.fg = fg;
        textPaint.setColor(fg);
        invalidate();
        return this;
    }

    RemoteButton labelSize(float sp) {
        labelSp = sp;
        invalidate();
        return this;
    }

    RemoteButton repeatWhileHeld() {
        repeat = true;
        return this;
    }

    RemoteButton onPress(OnPress p) {
        onPress = p;
        return this;
    }

    RemoteButton describe(String desc) {
        setContentDescription(desc);
        return this;
    }

    private void fire(boolean haptic) {
        if (haptic) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (onPress != null) onPress.onPress();
    }

    /** With a long-press action the tap fires on release, so holding doesn't also trigger it. */
    RemoteButton onLongPress(OnPress p) {
        onLong = p;
        return this;
    }

    private OnPress onLong;
    private boolean longFired;
    private final Runnable longRunner = new Runnable() {
        @Override
        public void run() {
            if (!pressedNow) return;
            longFired = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (onLong != null) onLong.onPress();
        }
    };

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressedNow = true;
                longFired = false;
                invalidate();
                if (onLong != null) {
                    postDelayed(longRunner, 500);
                } else {
                    fire(true);
                    if (repeat) postDelayed(repeater, 450);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (onLong != null && pressedNow) {
                    float x = e.getX(), y = e.getY(), slop = Theme.dp(getContext(), 16);
                    if (x < -slop || y < -slop || x > getWidth() + slop || y > getHeight() + slop) {
                        pressedNow = false;
                        removeCallbacks(longRunner);
                        invalidate();
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (onLong != null && pressedNow && !longFired) {
                    removeCallbacks(longRunner);
                    fire(true);
                }
                // fall through
            case MotionEvent.ACTION_CANCEL:
                pressedNow = false;
                removeCallbacks(repeater);
                removeCallbacks(longRunner);
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() { // accessibility (TalkBack) activation
        super.performClick();
        fire(false);
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        bgPaint.setColor(pressedNow ? bgPressed : bg);
        if (round) {
            c.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f, bgPaint);
        } else {
            rect.set(0, 0, w, h);
            float r = Math.min(h / 2f, Theme.dp(getContext(), 22));
            c.drawRoundRect(rect, r, r, bgPaint);
        }
        float scale = pressedNow ? 0.93f : 1f;
        if (icon != 0) {
            Icons.draw(c, icon, w / 2f, h / 2f, Math.min(w, h) * 0.42f * scale, fg);
        } else if (label != null) {
            float size = Theme.sp(getContext(), labelSp);
            textPaint.setTextSize(size);
            float avail = w - Theme.dp(getContext(), 10);
            while (size > Theme.sp(getContext(), 9) && textPaint.measureText(label) > avail) {
                size -= Theme.sp(getContext(), 0.5f);
                textPaint.setTextSize(size);
            }
            textPaint.setTextSize(size * scale);
            float y = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
            c.drawText(label, w / 2f, y, textPaint);
        }
    }
}
