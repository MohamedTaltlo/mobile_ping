package online.mtradius.mtping;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import java.util.Collections;
import java.util.List;

final class LatencyGraphView extends View {
    private static final int GREEN = 0xFF3EDAB6;
    private static final int RED = 0xFFFF6278;
    private static final int GRID = 0xFF253A50;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<PingSample> data = Collections.emptyList();

    LatencyGraphView(Context context) {
        super(context);
        setMinimumHeight(dp(160));
    }

    void setSamples(List<PingSample> items) {
        data = items;
        invalidate();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float left = dp(8), right = getWidth() - dp(8);
        float top = dp(18), bottom = getHeight() - dp(15);
        paint.setColor(GRID);
        paint.setStrokeWidth(dp(1));
        paint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i <= 4; i++) {
            float y = top + (bottom - top) * i / 4f;
            c.drawLine(left, y, right, y, paint);
        }
        int n = Math.min(90, data.size());
        if (n == 0) return;
        int offset = data.size() - n;
        double max = 5.0;
        for (int i = offset; i < data.size(); i++) {
            PingSample s = data.get(i);
            if (s.kind == PingSample.Kind.REPLY) max = Math.max(max, s.rttMs);
        }
        max *= 1.20;
        Path path = new Path();
        boolean penDown = false;
        paint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < n; i++) {
            PingSample s = data.get(offset + i);
            float x = left + (n <= 1 ? 0 : (right - left) * i / (n - 1f));
            if (s.kind == PingSample.Kind.REPLY) {
                float y = bottom - (float) Math.min(1.0, s.rttMs / max) * (bottom - top);
                if (!penDown) path.moveTo(x, y);
                else path.lineTo(x, y);
                penDown = true;
            } else {
                penDown = false;
                paint.setColor(s.kind == PingSample.Kind.TIMEOUT ? RED : 0xFFFFC36A);
                paint.setStrokeWidth(dp(2));
                c.drawLine(x, bottom - dp(14), x, bottom, paint);
            }
        }
        paint.setColor(GREEN);
        paint.setStrokeWidth(dp(2.2f));
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        PingSample latest = data.get(data.size() - 1);
        if (latest.kind == PingSample.Kind.REPLY) {
            float x = n == 1 ? left : right;
            float y = bottom - (float) Math.min(1.0, latest.rttMs / max) * (bottom - top);
            c.drawCircle(x, y, dp(3), paint);
        }
    }
}
