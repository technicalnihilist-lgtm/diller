package com.diller.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;

/**
 * Root layout that turns a horizontal drag into a tab change.
 * Vertical drags pass through, and any child that calls
 * requestDisallowInterceptTouchEvent (e.g. a swipeable list row) keeps the gesture.
 */
public class SwipeTabHost extends LinearLayout {

    public interface Listener { void onSwipe(int dir); } // dir: +1 = next tab, -1 = previous

    private Listener listener;
    private final int slop;
    private final float threshold;
    private float x0, y0;
    private boolean claimed;

    public SwipeTabHost(Context c) {
        super(c);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        threshold = c.getResources().getDisplayMetrics().density * 64f;
    }

    public SwipeTabHost(Context c, AttributeSet a) {
        super(c, a);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        threshold = c.getResources().getDisplayMetrics().density * 64f;
    }

    public void setListener(Listener l) { listener = l; }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                x0 = e.getX();
                y0 = e.getY();
                claimed = false;
                break;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - x0;
                float dy = e.getY() - y0;
                if (Math.abs(dx) > threshold && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                    claimed = true;
                    return true;
                }
                break;
            }
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                return claimed;
            case MotionEvent.ACTION_UP: {
                float dx = e.getX() - x0;
                if (claimed && listener != null && Math.abs(dx) > threshold * 0.6f) {
                    listener.onSwipe(dx < 0 ? 1 : -1);
                }
                claimed = false;
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                claimed = false;
                return true;
        }
        return claimed;
    }
}
