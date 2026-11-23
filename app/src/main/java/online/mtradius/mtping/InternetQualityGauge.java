package online.mtradius.mtping;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** Small native anti-aliased circular quality meter; no assets/dependencies. */
final class InternetQualityGauge extends View {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ThemePalette palette;
    private int value=-1;
    InternetQualityGauge(Context context){
        super(context);
        palette=ThemePalette.of(context);
    }
    void setValue(int n){value=n;invalidate();}
    private float dp(float n){return n*getResources().getDisplayMetrics().density;}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        float centerX=getWidth()/2f,centerY=getHeight()/2f;
        float radius=Math.min(getWidth(),getHeight())*.39f;
        float stroke=dp(12);
        RectF bounds=new RectF(centerX-radius,centerY-radius,centerX+radius,centerY+radius);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(stroke);p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(palette.border);c.drawArc(bounds,135,270,false,p);
        if(value>=0){
            p.setColor(value>=75?palette.accent:value>=55?palette.warning:palette.danger);
            c.drawArc(bounds,135,270*Math.min(100,value)/100f,false,p);
        }
        p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        p.setTextSize(dp(42));
        p.setColor(palette.primary);
        String number=value<0?"—":Integer.toString(value);
        float baseline=centerY-(p.ascent()+p.descent())/2-dp(9);
        c.drawText(number,centerX,baseline,p);
        p.setTextSize(dp(12));p.setTypeface(Typeface.DEFAULT);
        p.setColor(palette.muted);c.drawText("من 100",centerX,centerY+dp(33),p);
    }
}
