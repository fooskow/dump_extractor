package it.dino.switchextractor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Barra a due tratti: pacchetto (oro) e romfs/exefs (verde acqua). La larghezza dei tratti e'
 * proporzionale al lavoro di ciascuno, quindi la barra e la percentuale coincidono.
 */
public class PipelineBarView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int trackColor;
    private int color1;
    private int color3;
    private float share1 = 0.5f;
    private float p1;
    private float p3;
    private final float gap;
    private final float radius;

    public PipelineBarView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        gap = 3 * d;
        radius = 5 * d;
    }

    public void setColors(int track, int c1, int c3) {
        trackColor = track;
        color1 = c1;
        color3 = c3;
        invalidate();
    }

    public void setProgress(float share1, float p1, float p3) {
        this.share1 = Math.max(0.05f, Math.min(0.95f, share1));
        this.p1 = clamp(p1);
        this.p3 = clamp(p3);
        invalidate();
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float usable = w - gap;
        float w1 = usable * share1;
        float w3 = usable - w1;
        segment(canvas, 0, w1, h, p1, color1);
        segment(canvas, w1 + gap, w3, h, p3, color3);
    }

    private void segment(Canvas canvas, float x, float w, float h, float fill, int color) {
        paint.setColor(trackColor);
        rect.set(x, 0, x + w, h);
        canvas.drawRoundRect(rect, radius, radius, paint);
        if (fill > 0f) {
            float fw = Math.max(h, w * fill); // almeno un'estremita' tonda visibile
            paint.setColor(color);
            rect.set(x, 0, x + Math.min(w, fw), h);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
    }
}
