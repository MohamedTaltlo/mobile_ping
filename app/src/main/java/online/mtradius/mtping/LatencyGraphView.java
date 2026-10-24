package online.mtradius.mtping;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A real-time, auto-scaled line graph for the ICMP RTT reported by ping.
 * Chronology stays left -> right even when the rest of the interface is RTL.
 */
final class LatencyGraphView extends View {
    private static final int GREEN = 0xFF3EDAB6;
    private static final int RED = 0xFFFF6278;
    private static final int AMBER = 0xFFFFC36A;
    private static final int GRID = 0xFF253A50;
    private static final int LABEL = 0xFFB5C5D5;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<PingSample> data = Collections.emptyList();

    LatencyGraphView(Context context) {
        super(context);
        setMinimumHeight(dp(208));
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    void setSamples(List<PingSample> items) {
        data = items == null ? Collections.emptyList() : items;
        invalidate();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String ms(double value) {
        return value >= 100 ? String.format(Locale.US, "%.0f", value) :
                value >= 10 ? String.format(Locale.US, "%.1f", value) :
                String.format(Locale.US, "%.2f", value);
    }

    private void drawLabel(Canvas c, String label, float x, float y, int color,
                           float sp, Paint.Align align, boolean bold) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        p.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD :
                android.graphics.Typeface.DEFAULT);
        p.setTextSize(sp * getResources().getDisplayMetrics().scaledDensity);
        p.setTextAlign(align);
        c.drawText(label, x, y, p);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        // Reserve a real labelled axis so the line does not blend into the grid.
        float left = dp(12), right = w - dp(64);
        float top = dp(39), bottom = h - dp(30);
        if (right <= left || bottom <= top) return;
        int n = Math.min(60, data.size());
        int offset = data.size() - n;

        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        int successful = 0;
        PingSample latest = null;
        for (int i = offset; i < data.size(); i++) {
            PingSample s = data.get(i);
            if (s.kind == PingSample.Kind.REPLY && s.rttMs >= 0) {
                min = Math.min(min, s.rttMs);
                max = Math.max(max, s.rttMs);
                successful++;
                latest = s;
            }
        }

        if (successful == 0) {
            min = 0;
            max = 10;
        } else {
            // A 2-ms minimum span keeps even stable 1–3ms LAN latency readable.
            double span = Math.max(2.0, max - min);
            double pad = Math.max(0.3, span * 0.22);
            min = Math.max(0, min - pad);
            max = Math.max(min + 2.0, max + pad);
        }
        double range = max - min;

        if (latest != null) {
            drawLabel(c, "آخر بينغ  " + ms(latest.rttMs) + " ms",
                    right, dp(22), GREEN, 15, Paint.Align.RIGHT, true);
        } else {
            drawLabel(c, "بانتظار استجابة البينغ", right, dp(22), LABEL,
                    12, Paint.Align.RIGHT, false);
        }

        for (int i = 0; i <= 4; i++) {
            float y = top + (bottom - top) * i / 4f;
            p.setColor(GRID);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            c.drawLine(left, y, right, y, p);
            drawLabel(c, ms(max - range * i / 4.0),
                    w - dp(5), y + dp(4), LABEL, 10, Paint.Align.RIGHT, false);
        }
        drawLabel(c, "ms", w - dp(5), top - dp(13), LABEL, 10,
                Paint.Align.RIGHT, false);

        if (n == 0) {
            drawLabel(c, "بانتظار النبضات...", (left + right) / 2f,
                    (top + bottom) / 2f, LABEL, 12, Paint.Align.CENTER, false);
            return;
        }

        Path segment = new Path();
        p.setColor(GREEN);
        p.setStrokeWidth(dp(2.7f));
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStyle(Paint.Style.STROKE);
        boolean insideLine = false;
        for (int i = 0; i < n; i++) {
            PingSample s = data.get(offset + i);
            float x = n <= 1 ? (left + right) / 2f :
                    left + (right - left) * i / (n - 1f);
            if (s.kind == PingSample.Kind.REPLY && s.rttMs >= 0) {
                float fraction = (float) Math.max(0.0,
                        Math.min(1.0, (s.rttMs - min) / range));
                float y = bottom - fraction * (bottom - top);
                if (!insideLine) segment.moveTo(x, y);
                else segment.lineTo(x, y);
                insideLine = true;
            } else {
                if (insideLine) {
                    p.setColor(GREEN);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(dp(2.7f));
                    c.drawPath(segment, p);
                    segment.reset();
                    insideLine = false;
                }
                p.setStyle(Paint.Style.STROKE);
                p.setColor(s.kind == PingSample.Kind.TIMEOUT ? RED : AMBER);
                p.setStrokeWidth(dp(2.5f));
                c.drawLine(x, top, x, bottom, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(x, bottom, dp(3.5f), p);
            }
        }
        if (insideLine) {
            p.setColor(GREEN);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2.7f));
            c.drawPath(segment, p);
        }

        // Show individual samples while the plot is sparse; mark the latest
        // valid sample even if it is followed by a lost packet.
        for (int i = 0; i < n; i++) {
            PingSample s = data.get(offset + i);
            if (s.kind != PingSample.Kind.REPLY || s.rttMs < 0) continue;
            if (n > 24 && i != n - 1) continue;
            float x = n <= 1 ? (left + right) / 2f :
                    left + (right - left) * i / (n - 1f);
            float y = bottom - (float) Math.max(0.0,
                    Math.min(1.0, (s.rttMs - min) / range)) * (bottom - top);
            p.setStyle(Paint.Style.FILL);
            p.setColor(GREEN);
            c.drawCircle(x, y, dp(i == n - 1 ? 4 : 2.2f), p);
        }

        drawLabel(c, "الأقدم", left, h - dp(7), LABEL,
                10, Paint.Align.LEFT, false);
        drawLabel(c, "الأحدث", right, h - dp(7), LABEL,
                10, Paint.Align.RIGHT, false);
    }
}
