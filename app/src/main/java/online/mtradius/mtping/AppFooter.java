package online.mtradius.mtping;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Calendar;

/** Shared, clickable attribution kept current without manual releases. */
final class AppFooter {
    private AppFooter() {}
    static void add(Activity activity, LinearLayout parent) {
        TextView footer = new TextView(activity);
        int year = Calendar.getInstance().get(Calendar.YEAR);
        footer.setText("Developed by: MT_RADIUS 2019-" + year);
        footer.setTextSize(12);
        footer.setTextColor(0xFF90A2B8);
        footer.setTextDirection(android.view.View.TEXT_DIRECTION_LTR);
        footer.setGravity(Gravity.CENTER);
        footer.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        int pad = (int)(18 * activity.getResources().getDisplayMetrics().density + .5f);
        footer.setPadding(0, pad, 0, pad);
        footer.setContentDescription("MT_RADIUS • التواصل عبر واتساب");
        footer.setOnClickListener(v -> {
            Intent open = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://wa.me/963969451248"));
            try { activity.startActivity(open); }
            catch (Exception ignored) { }
        });
        parent.addView(footer, new LinearLayout.LayoutParams(-1, -2));
    }
}
