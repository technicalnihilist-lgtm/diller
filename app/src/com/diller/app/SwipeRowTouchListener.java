package com.diller.app;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

/**
 * Swipe a list row: drag right to call, drag left to delete.
 * Vertical drags are left to the ListView so scrolling still works.
 *
 * The row MUST be clickable (setClickable(true)) or it never becomes the touch
 * target and will not receive MOVE/UP events.
 */
public class SwipeRowTouchListener implements View.OnTouchListener {

    public interface Callbacks {
        void onSwipeRight();
        void onSwipeLeft();
        void onTap();
    }

    private final Callbacks cb;
    private final int slop;
    private final float maxSwipe;
    private final int bgCall, bgDelete;

    private float x0, y0;
    private boolean swiping;

    public SwipeRowTouchListener(Context c, Callbacks cb, int bgCall, int bgDelete) {
        this.cb = cb;
        this.bgCall = bgCall;
        this.bgDelete = bgDelete;
        this.slop = ViewConfiguration.get(c).getScaledTouchSlop();
        this.maxSwipe = c.getResources().getDisplayMetrics().density * 110f;
    }

    @Override
    public boolean onTouch(final View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                x0 = e.getX();
                y0 = e.getY();
                swiping = false;
                v.setTranslationX(0f);
                v.setBackgroundResource(0);
                return false;

            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - x0;
                float dy = e.getY() - y0;
                if (!swiping) {
                    // only start a swipe on a clearly horizontal drag
                    if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.3f) {
                        swiping = true;
                        ViewParent p = v.getParent();
                        if (p != null) p.requestDisallowInterceptTouchEvent(true);
                    } else {
                        return false;
                    }
                }
                float clamped = Math.max(-maxSwipe, Math.min(maxSwipe, dx));
                v.setTranslationX(clamped);
                v.setBackgroundResource(clamped > 0 ? bgCall : bgDelete);
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!swiping) {
                    v.setTranslationX(0f);
                    v.setBackgroundResource(0);
                    // treat as a tap only if the finger barely moved
                    if (e.getActionMasked() == MotionEvent.ACTION_UP
                            && Math.abs(e.getX() - x0) < slop
                            && Math.abs(e.getY() - y0) < slop) {
                        cb.onTap();
                        return true;
                    }
                    return false;
                }
                ViewParent p = v.getParent();
                if (p != null) p.requestDisallowInterceptTouchEvent(false);

                float tx = v.getTranslationX();
                float threshold = maxSwipe * 0.5f;
                final boolean right = tx > threshold;
                final boolean left = tx < -threshold;
                swiping = false;

                if (right || left) {
                    v.animate().translationX(right ? v.getWidth() : -v.getWidth())
                            .setDuration(150)
                            .withEndAction(new Runnable() {
                                public void run() {
                                    v.setTranslationX(0f);
                                    v.setBackgroundResource(0);
                                    if (right) cb.onSwipeRight();
                                    else cb.onSwipeLeft();
                                }
                            }).start();
                } else {
                    v.animate().translationX(0f).setDuration(130).start();
                    v.setBackgroundResource(0);
                }
                return true;
            }
        }
        return false;
    }
}
