package online.mtradius.mtping;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;

/**
 * One header button instead of a three-option toolbar.
 * Tap cycles: system (screen) -> day (sun) -> night (moon) -> system.
 * The stored mode is shared by every Activity and survives app restarts.
 */
final class ThemeSelector {
    private ThemeSelector() {}

    private static int dp(Activity a,float value) {
        return Math.round(value*a.getResources().getDisplayMetrics().density);
    }

    static void attachIcon(Activity activity,LinearLayout header) {
        ThemePalette palette=ThemePalette.of(activity);
        ModeIcon icon=new ModeIcon(activity,palette,ThemePalette.getMode(activity));
        icon.setClickable(true);
        icon.setFocusable(true);
        icon.setContentDescription("المظهر الحالي: "+modeName(ThemePalette.getMode(activity))+
                "؛ اضغط للتبديل بين النظام والنهاري والليلي");
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(dp(activity,50),dp(activity,50));
        params.leftMargin=dp(activity,5);
        params.rightMargin=dp(activity,5);
        header.addView(icon,params);
        icon.setOnClickListener(v->{
            String current=ThemePalette.getMode(activity);
            String next=ThemePalette.SYSTEM.equals(current)?ThemePalette.LIGHT:
                    ThemePalette.LIGHT.equals(current)?ThemePalette.DARK:ThemePalette.SYSTEM;
            ThemePalette.saveMode(activity,next);
            // Keep the running foreground-service state and all persistent settings.
            activity.recreate();
        });
    }

    private static String modeName(String mode) {
        if(ThemePalette.LIGHT.equals(mode))return "نهاري";
        if(ThemePalette.DARK.equals(mode))return "ليلي";
        return "حسب النظام";
    }

    private static final class ModeIcon extends View {
        private final Paint brush=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String mode;
        private final ThemePalette theme;
        ModeIcon(Activity activity,ThemePalette colors,String selected) {
            super(activity);
            theme=colors;mode=selected;
            GradientDrawable background=new GradientDrawable();
            background.setColor(colors.card);
            background.setCornerRadius(dp(activity,15));
            background.setStroke(dp(activity,1),colors.border);
            setBackground(background);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            final float cx=getWidth()/2f,cy=getHeight()/2f;
            final float r=Math.min(getWidth(),getHeight())*.20f;
            brush.setStyle(Paint.Style.FILL);
            if(ThemePalette.LIGHT.equals(mode)) {
                brush.setColor(0xFFF49A08);
                canvas.drawCircle(cx,cy,r*.75f,brush);
                brush.setStrokeWidth(Math.max(2,getResources().getDisplayMetrics().density*2.1f));
                brush.setStrokeCap(Paint.Cap.ROUND);
                for(int i=0;i<8;i++){
                    double radians=Math.PI*i/4;
                    float x=(float)Math.cos(radians),y=(float)Math.sin(radians);
                    canvas.drawLine(cx+x*r*1.2f,cy+y*r*1.2f,
                            cx+x*r*1.65f,cy+y*r*1.65f,brush);
                }
            } else if(ThemePalette.DARK.equals(mode)) {
                brush.setColor(0xFF7754F5);
                Path moon=new Path();
                moon.addCircle(cx,cy,r*1.20f,Path.Direction.CW);
                Path carve=new Path();
                carve.addCircle(cx+r*.60f,cy-r*.50f,r*1.08f,Path.Direction.CW);
                moon.op(carve,Path.Op.DIFFERENCE);
                canvas.drawPath(moon,brush);
            } else {
                brush.setColor(0xFF129BEB);
                brush.setStyle(Paint.Style.STROKE);
                brush.setStrokeWidth(Math.max(2,getResources().getDisplayMetrics().density*2.2f));
                brush.setStrokeJoin(Paint.Join.ROUND);
                RectF monitor=new RectF(cx-r*1.28f,cy-r*.92f,cx+r*1.28f,cy+r*.56f);
                canvas.drawRoundRect(monitor,r*.10f,r*.10f,brush);
                canvas.drawLine(cx,cy+r*.60f,cx,cy+r*1.10f,brush);
                canvas.drawLine(cx-r*.60f,cy+r*1.14f,cx+r*.60f,cy+r*1.14f,brush);
            }
        }
    }
}
