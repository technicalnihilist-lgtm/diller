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

    /** The blobs drift at ~0.2 rad/s, so 60 redraws a second buy nothing the eye can see.
     *  The call screen stays up for the whole of a call, so pacing the frame callback at
     *  ~30fps halves the full-screen GPU work for that time without changing how fast or
     *  how far the backdrop moves. */
    private static final long FRAME_MS = 33L;

    /** One cached gradient per blob, centred on the origin and reused every frame.
     *  Gradients used to be rebuilt on each onDraw (4 new shaders x 60fps), which churned
     *  the heap and caused GC jank; now a shader is only rebuilt when its radius really changes. */
    private final RadialGradient[] shaders = new RadialGradient[colors.length];
    private final float[] shaderRadius = new float[colors.length];

    public GlassBackgroundView(Context c) { super(c); init(); }
    public GlassBackgroundView(Context c, AttributeSet a) { super(c, a); init(); }
    public GlassBackgroundView(Context c, AttributeSet a, int d) { super(c, a, d); init(); }

    private void init() {
        bgPaint.setColor(0xFF070B18);
        blobPaint.setDither(true);
    }

    @Override protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        w = Math.max(1, nw); h = Math.max(1, nh);
        // Blob radius is relative to the view size, so cached gradients no longer apply.
        for (int i = 0; i < shaders.length; i++) shaders[i] = null;
        super.onSizeChanged(nw, nh, ow, oh);
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawRect(0, 0, w, h, bgPaint);
        float t = (System.nanoTime() - t0) / 1_000_000_000f;
        for (int i = 0; i < colors.length; i++) {
            float cx = w * (0.5f + 0.38f * (float) Math.sin(t * 0.22f + i * 1.7f));
            float cy = h * (0.5f + 0.40f * (float) Math.cos(t * 0.19f + i * 2.3f));
            float r = Math.max(w, h) * (0.36f + 0.05f * (float) Math.sin(t * 0.3f + i));
            // Quantise the radius so the shader is rebuilt only on a visible size change.
            float qr = Math.max(1f, Math.round(r));
            RadialGradient g = shaders[i];
            if (g == null || shaderRadius[i] != qr) {
                int base = colors[i];
                int c1 = Color.argb(165, Color.red(base), Color.green(base), Color.blue(base));
                int c2 = Color.argb(0, Color.red(base), Color.green(base), Color.blue(base));
                g = new RadialGradient(0f, 0f, qr, c1, c2, Shader.TileMode.CLAMP);
                shaders[i] = g;
                shaderRadius[i] = qr;
            }
            blobPaint.setShader(g);
            int save = canvas.save();
            canvas.translate(cx, cy);
            canvas.drawCircle(0f, 0f, qr, blobPaint);
            canvas.restoreToCount(save);
        }
        // Only keep the frame pump alive while the backdrop is actually on screen.
        // The call screen is often obscured (another app in front, screen covered by
        // the proximity sensor); posting a frame every 33ms then only wakes the UI
        // thread and burns GPU for pixels nobody sees.
        if (isShown() && getWindowVisibility() == View.VISIBLE) postInvalidateDelayed(FRAME_MS);
    }

    /** Restart the animation as soon as the view is visible again; a stale invalidate on
     *  a hidden view is a no-op, so posting unconditionally here is safe. */
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == View.VISIBLE) postInvalidate();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == View.VISIBLE) postInvalidate();
    }
}
