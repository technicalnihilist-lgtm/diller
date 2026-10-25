package com.diller.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** Animated "liquid glass" backdrop: soft colour blobs drifting behind frosted panels. */
public class GlassBackgroundView extends View {

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] colors = {0xFF3A6BFF, 0xFF7A3CFF, 0xFF00D4C8, 0xFFFF5C8A};
    private float w = 1f, h = 1f;
    private final long t0 = System.nanoTime();

    public GlassBackgroundView(Context c) { super(c); init(); }
    public GlassBackgroundView(Context c, AttributeSet a) { super(c, a); init(); }
    public GlassBackgroundView(Context c, AttributeSet a, int d) { super(c, a, d); init(); }

    private void init() {
        bgPaint.setColor(0xFF070B18);
        blobPaint.setDither(true);
    }

    @Override protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        w = Math.max(1, nw); h = Math.max(1, nh);
        super.onSizeChanged(nw, nh, ow, oh);
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawRect(0, 0, w, h, bgPaint);
        float t = (System.nanoTime() - t0) / 1_000_000_000f;
        for (int i = 0; i < colors.length; i++) {
            float cx = w * (0.5f + 0.38f * (float) Math.sin(t * 0.22f + i * 1.7f));
            float cy = h * (0.5f + 0.40f * (float) Math.cos(t * 0.19f + i * 2.3f));
            float r = Math.max(w, h) * (0.36f + 0.05f * (float) Math.sin(t * 0.3f + i));
            int base = colors[i];
            int c1 = Color.argb(165, Color.red(base), Color.green(base), Color.blue(base));
            int c2 = Color.argb(0, Color.red(base), Color.green(base), Color.blue(base));
            blobPaint.setShader(new RadialGradient(cx, cy, r, c1, c2, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r, blobPaint);
        }
        postInvalidateOnAnimation();
    }
}
