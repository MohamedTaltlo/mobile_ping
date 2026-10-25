package online.mtradius.mtping;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.view.View;
import android.view.Window;

/** One application-wide palette. Follows Android's current light/dark setting. */
final class ThemePalette {
    final boolean dark;
    final int bg, card, border, primary, muted, accent, accentSoft,
              panel, danger, warning, stop, onAccent, grid, label;

    private ThemePalette(boolean dark) {
        this.dark=dark;
        if(dark){
            bg=0xFF07121E; card=0xFF101F30; border=0xFF20364B;
            primary=0xFFF1F5F9; muted=0xFF90A2B8; accent=0xFF3EDAB6;
            accentSoft=0xFF173245; panel=0xFF030D16; danger=0xFFFF6278;
            warning=0xFFFFC36A; stop=0xFFB33B50; onAccent=0xFF07121E;
            grid=0xFF253A50;label=0xFFB5C5D5;
        } else {
            bg=0xFFF4F7FB; card=0xFFFFFFFF; border=0xFFD7E3EF;
            primary=0xFF172B40; muted=0xFF63778C; accent=0xFF087F73;
            accentSoft=0xFFE2F5F1; panel=0xFFF7FBFF; danger=0xFFB92849;
            warning=0xFF995B11; stop=0xFFB13D50; onAccent=Color.WHITE;
            grid=0xFFD8E4EE;label=0xFF52677D;
        }
    }

    static ThemePalette of(Context ctx) {
        boolean night=(ctx.getResources().getConfiguration().uiMode &
                Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        return new ThemePalette(night);
    }

    static void applyWindow(Activity activity,ThemePalette palette) {
        Window w=activity.getWindow();
        w.setStatusBarColor(palette.bg);
        w.setNavigationBarColor(palette.bg);
        View decor=w.getDecorView();
        int flags=decor.getSystemUiVisibility();
        if(palette.dark) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }
}
