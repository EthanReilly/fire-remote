package com.fireremote.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;

/**
 * Trackpad-style surface: swipe to move the TV's focus (one step per ~36dp of travel, so long
 * swipes move several items and fast flicks add a few extra), tap to select, hold for options.
 */
final class TouchpadView extends View {

    interface Listener {
        /** which = DpadView.UP / RIGHT / DOWN / LEFT / CENTER */
        void onKey(int which);

        void onLongPress();
    }

    private Listener listener;

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dots = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint finger = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private VelocityTracker velocity;
    private float downX, downY, originX, originY, touchX, touchY;
    private boolean touching, moved, longPressed;
    private int lastDir = -1;
    private int stepsThisGesture;
    private float pulse; // 0..1 flash when a step fires

    private final float stepPx, slopPx;

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (touching && !moved) {
                longPressed = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                if (listener != null) listener.onLongPress();
            }
        }
    };

    private final Runnable fade = new Runnable() {
        @Override
        public void run() {
            pulse = Math.max(0, pulse - 0.15f);
            invalidate();
            if (pulse > 0) postDelayed(this, 16);
        }
    };

    TouchpadView(Context ctx) {
        super(ctx);
        stepPx = Theme.dp(ctx, 36);
        slopPx = Theme.dp(ctx, 10);
        bg.setColor(Theme.SURFACE);
        dots.setColor(0xFF2B323D);
        hint.setColor(Theme.MUTED);
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        finger.setColor(Theme.ACCENT);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(Theme.ACCENT);
        setClickable(true);
        setContentDescription("Touchpad. Swipe to move, tap to select.");
    }

    void setListener(Listener l) {
        listener = l;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getMode(hSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(hSpec) : Math.min(Math.round(w * 0.9f), Theme.dpi(getContext(), 300));
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        rect.set(0, 0, w, h);
        float r = Theme.dp(getContext(), 28);
        c.drawRoundRect(rect, r, r, bg);

        float gap = Theme.dp(getContext(), 22);
        float dr = Theme.dp(getContext(), 1.6f);
        for (float x = gap; x < w - gap / 2; x += gap) {
            for (float y = gap; y < h - gap / 2; y += gap) c.drawCircle(x, y, dr, dots);
        }

        if (!touching) {
            hint.setTextSize(Theme.sp(getContext(), 14));
            c.drawText("Swipe to move · Tap to select", w / 2, h / 2, hint);
            hint.setTextSize(Theme.sp(getContext(), 12));
            c.drawText("Hold for options", w / 2, h / 2 + Theme.sp(getContext(), 22), hint);
        } else {
            finger.setAlpha(70);
            c.drawCircle(touchX, touchY, Theme.dp(getContext(), 34), finger);
            finger.setAlpha(255);
            c.drawCircle(touchX, touchY, Theme.dp(getContext(), 7), finger);
            if (pulse > 0) {
                ring.setAlpha((int) (255 * pulse));
                ring.setStrokeWidth(Theme.dp(getContext(), 2));
                c.drawCircle(touchX, touchY, Theme.dp(getContext(), 34 + 18 * (1 - pulse)), ring);
            }
        }
        if (lastDir >= 0 && pulse > 0) {
            // brief glow on the edge in the direction just sent
            Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
            edge.setColor(Theme.ACCENT);
            edge.setAlpha((int) (200 * pulse));
            float t = Theme.dp(getContext(), 4), inset = Theme.dp(getContext(), 40);
            switch (lastDir) {
                case DpadView.UP: c.drawRoundRect(new RectF(inset, 0, w - inset, t), t, t, edge); break;
                case DpadView.DOWN: c.drawRoundRect(new RectF(inset, h - t, w - inset, h), t, t, edge); break;
                case DpadView.LEFT: c.drawRoundRect(new RectF(0, inset, t, h - inset), t, t, edge); break;
                case DpadView.RIGHT: c.drawRoundRect(new RectF(w - t, inset, w, h - inset), t, t, edge); break;
                default: break;
            }
        }
    }

    private void step(int dir, boolean haptic) {
        lastDir = dir;
        stepsThisGesture++;
        if (haptic) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        if (listener != null) listener.onKey(dir);
        pulse = 1f;
        removeCallbacks(fade);
        postDelayed(fade, 16);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true); // don't let the page scroll
                if (velocity != null) velocity.recycle();
                velocity = VelocityTracker.obtain();
                velocity.addMovement(e);
                downX = originX = touchX = x;
                downY = originY = touchY = y;
                touching = true;
                moved = false;
                longPressed = false;
                stepsThisGesture = 0;
                postDelayed(longPress, 550);
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (velocity != null) velocity.addMovement(e);
                touchX = x;
                touchY = y;
                if (!moved && Math.hypot(x - downX, y - downY) > slopPx) {
                    moved = true;
                    removeCallbacks(longPress);
                }
                if (moved && !longPressed) {
                    float dx = x - originX, dy = y - originY;
                    if (Math.abs(dx) >= stepPx && Math.abs(dx) > Math.abs(dy)) {
                        step(dx > 0 ? DpadView.RIGHT : DpadView.LEFT, true);
                        originX += dx > 0 ? stepPx : -stepPx;
                        originY = y;
                    } else if (Math.abs(dy) >= stepPx && Math.abs(dy) >= Math.abs(dx)) {
                        step(dy > 0 ? DpadView.DOWN : DpadView.UP, true);
                        originY += dy > 0 ? stepPx : -stepPx;
                        originX = x;
                    }
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if (!longPressed) {
                    if (!moved) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        if (listener != null) listener.onKey(DpadView.CENTER);
                        pulse = 1f;
                        removeCallbacks(fade);
                        postDelayed(fade, 16);
                    } else if (velocity != null) {
                        velocity.addMovement(e);
                        velocity.computeCurrentVelocity(1000);
                        float vx = velocity.getXVelocity(), vy = velocity.getYVelocity();
                        float fast = Theme.dp(getContext(), 1100);
                        float v = Math.max(Math.abs(vx), Math.abs(vy));
                        if (v > fast) {
                            int dir = Math.abs(vx) > Math.abs(vy)
                                    ? (vx > 0 ? DpadView.RIGHT : DpadView.LEFT)
                                    : (vy > 0 ? DpadView.DOWN : DpadView.UP);
                            int extra = Math.min(4, (int) (v / fast));
                            for (int i = 0; i < extra; i++) step(dir, i == 0);
                        } else if (stepsThisGesture == 0) {
                            // short, slow swipe: still move one step in its direction
                            float dx = x - downX, dy = y - downY;
                            step(Math.abs(dx) > Math.abs(dy)
                                    ? (dx > 0 ? DpadView.RIGHT : DpadView.LEFT)
                                    : (dy > 0 ? DpadView.DOWN : DpadView.UP), true);
                        }
                    }
                }
                // fall through
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                touching = false;
                if (velocity != null) {
                    velocity.recycle();
                    velocity = null;
                }
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
