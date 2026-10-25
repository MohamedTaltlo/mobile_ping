package online.mtradius.mtping;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Three-position appearance selector shared by Ping and every tools page.
 * No preference stored => follows the Android system automatically.
 */
final class ThemeSelector {
    private ThemeSelector() {}
    private static int dp(Activity a,float v) {
        return Math.round(v*a.getResources().getDisplayMetrics().density);
    }
    private static GradientDrawable bg(Activity a,int color,int border) {
        GradientDrawable d=new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(a,12));
        d.setStroke(dp(a,1),border);
        return d;
    }
    static void attach(Activity a,LinearLayout parent) {
        ThemePalette colors=ThemePalette.of(a);
        String selected=ThemePalette.getMode(a);
        LinearLayout outer=new LinearLayout(a);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        outer.setGravity(Gravity.CENTER_VERTICAL);
        outer.setPadding(dp(a,9),dp(a,8),dp(a,9),dp(a,8));
        outer.setBackground(bg(a,colors.card,colors.border));
        LinearLayout.LayoutParams outerParams=new LinearLayout.LayoutParams(-1,dp(a,62));
        outerParams.topMargin=dp(a,11);
        outerParams.bottomMargin=dp(a,13);
        parent.addView(outer,outerParams);

        TextView title=new TextView(a);
        title.setText("المظهر");
        title.setTextSize(12);
        title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        title.setTextColor(colors.muted);
        title.setGravity(Gravity.CENTER_VERTICAL|Gravity.RIGHT);
        title.setPadding(dp(a,6),0,dp(a,7),0);
        outer.addView(title,new LinearLayout.LayoutParams(0,dp(a,42),.55f));

        option(a,outer,"☀","نهاري",ThemePalette.LIGHT,selected,colors);
        option(a,outer,"☾","ليلي",ThemePalette.DARK,selected,colors);
        option(a,outer,"🖥","النظام",ThemePalette.SYSTEM,selected,colors);
    }
    private static void option(Activity a,LinearLayout outer,String glyph,String label,
                               String mode,String current,ThemePalette colors) {
        final boolean chosen=mode.equals(current);
        LinearLayout button=new LinearLayout(a);
        button.setOrientation(LinearLayout.VERTICAL);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        button.setContentDescription("المظهر: "+label+(chosen?"، محدد":""));
        button.setBackground(bg(a,chosen?colors.accentSoft:colors.card,
                    chosen?colors.accent:colors.border));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(a,44),1f);
        p.leftMargin=dp(a,3);
        p.rightMargin=dp(a,3);
        outer.addView(button,p);

        TextView icon=new TextView(a);
        icon.setText(glyph);
        icon.setGravity(Gravity.CENTER);
        icon.setTextSize(16);
        icon.setTextColor(chosen?colors.accent:colors.muted);
        button.addView(icon,new LinearLayout.LayoutParams(-1,dp(a,23)));

        TextView caption=new TextView(a);
        caption.setText(label);
        caption.setGravity(Gravity.CENTER);
        caption.setTextSize(10);
        caption.setTypeface(Typeface.DEFAULT,chosen?Typeface.BOLD:Typeface.NORMAL);
        caption.setTextColor(chosen?colors.accent:colors.primary);
        button.addView(caption,new LinearLayout.LayoutParams(-1,dp(a,16)));

        button.setOnClickListener(v->{
            if(mode.equals(ThemePalette.getMode(a)))return;
            ThemePalette.saveMode(a,mode);
            // Recreate just the visible Activity; foreground Ping service stays running.
            a.recreate();
        });
    }
}
