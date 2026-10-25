package online.mtradius.mtping;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private ThemePalette palette;
    private int BG,CARD,BORDER,WHITE,MUTED,GREEN,RED,ORANGE;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            paintLiveState();
            ui.postDelayed(this, 350);
        }
    };

    private Sidebar sidebar;
    private EditText hostInput;
    private Button startButton;
    private EditText intervalInput;
    private TextView statusText, heroText, heroDetail, outageText;
    private LinearLayout pingCards, outageCards;
    private TextView[] stats = new TextView[6];
    private LatencyGraphView graph;
    private long lastRevision = Long.MIN_VALUE;
    private OutageStore outageStore;
    private boolean lastRunning;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        palette=ThemePalette.of(this);
        BG=palette.bg;CARD=palette.card;BORDER=palette.border;
        WHITE=palette.primary;MUTED=palette.muted;GREEN=palette.accent;
        RED=palette.danger;ORANGE=palette.warning;
        ThemePalette.applyWindow(this,palette);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        outageStore = new OutageStore(this);
        buildUI();
        String host = getSharedPreferences("settings", MODE_PRIVATE).getString("host", "10.0.0.1");
        hostInput.setText(host);
        int interval = getSharedPreferences("settings", MODE_PRIVATE).getInt("interval", 1000);
        intervalInput.setText(Integer.toString(interval));
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 77);
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable shape(int fill, int border, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radius));
        if (border != 0) d.setStroke(dp(1), border);
        return d;
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private TextView txt(String content, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(content);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams margin(int width, int height, int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height);
        p.topMargin = dp(top);
        p.bottomMargin = dp(bottom);
        return p;
    }

    private void addHeading(LinearLayout root, String title) {
        root.addView(txt(title, 15, WHITE, true), margin(-1, -2, 20, 10));
    }

    private void buildUI() {
        ScrollView scrolling = new ScrollView(this);
        scrolling.setFillViewport(true);
        scrolling.setBackgroundColor(BG);
        scrolling.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        // Android 15+ forces edge-to-edge for targetSdk 35. Reserve the actual
        // status-bar/cutout inset so the MT_PING header never sits behind the clock.
        // On older Android versions the decor already fits system windows.
        if (Build.VERSION.SDK_INT >= 35) {
            // Never render scrolling cards behind the Android status-bar icons.
            scrolling.setClipToPadding(true);
            scrolling.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets topSafe = insets.getInsets(
                        WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets bottomSafe = insets.getInsets(
                        WindowInsets.Type.navigationBars());
                view.setPadding(0, topSafe.top, 0, bottomSafe.bottom);
                return insets;
            });
        }
        sidebar = Sidebar.attach(this, scrolling, "ping");
        LinearLayout root = vertical();
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setFocusableInTouchMode(true);
        scrolling.addView(root);

        LinearLayout header = horizontal();
        header.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        header.setMinimumHeight(dp(48));
        TextView menuButton = txt("☰", 26, GREEN, true);
        menuButton.setGravity(Gravity.CENTER);
        header.addView(menuButton,new LinearLayout.LayoutParams(dp(48),dp(48)));
        menuButton.setContentDescription("فتح القائمة الجانبية");
        menuButton.setOnClickListener(v -> sidebar.open());

        TextView brand = txt("MT_TOOLS", 25, WHITE, true);
        brand.setLetterSpacing(.10f);
        brand.setSingleLine(true);
        brand.setTextDirection(View.TEXT_DIRECTION_LTR);
        brand.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(0, -2, 1);
        brandParams.rightMargin = dp(9);
        header.addView(brand,brandParams);

        statusText = txt("●  متوقف", 12, MUTED, true);
        statusText.setPadding(dp(10),dp(9),dp(10),dp(9));
        statusText.setBackground(shape(CARD,BORDER,30));
        header.addView(statusText);
        root.addView(header);
        root.addView(new View(this), margin(-1, dp(9), 0, 0));

        LinearLayout target = vertical();
        target.setPadding(dp(16), dp(14), dp(16), dp(16));
        target.setBackground(shape(CARD, BORDER, 18));
        target.addView(txt("عنوان الجهاز أو الموقع المراد فحصه", 11, MUTED, true));
        LinearLayout fields = horizontal();
        hostInput = new EditText(this);
        hostInput.setSingleLine(true);
        hostInput.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        hostInput.setTextDirection(View.TEXT_DIRECTION_LTR);
        hostInput.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        hostInput.setTextSize(17);
        hostInput.setTypeface(Typeface.MONOSPACE);
        hostInput.setTextColor(WHITE);
        hostInput.setHintTextColor(MUTED);
        hostInput.setHint("10.0.0.1");
        hostInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        hostInput.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        hostInput.setBackground(shape(palette.panel, BORDER, 12));
        hostInput.setPadding(dp(12), dp(11), dp(12), dp(11));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, dp(52), 1);
        ip.rightMargin = dp(9);
        fields.addView(hostInput, ip);
        intervalInput = new EditText(this);
        intervalInput.setSingleLine(true);
        intervalInput.setTextColor(GREEN);
        intervalInput.setTextSize(15);
        intervalInput.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        intervalInput.setSelectAllOnFocus(true);
        intervalInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        intervalInput.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        intervalInput.setTextDirection(View.TEXT_DIRECTION_LTR);
        intervalInput.setGravity(Gravity.CENTER);
        intervalInput.setHint("1000");
        intervalInput.setHintTextColor(MUTED);
        intervalInput.setBackground(shape(palette.accentSoft, BORDER, 12));
        fields.addView(intervalInput, new LinearLayout.LayoutParams(dp(94), dp(52)));
        intervalInput.setOnEditorActionListener((view, actionId, event) -> {
            if(actionId == EditorInfo.IME_ACTION_DONE) {
                // Keep the same scroll position on Samsung IME dismissal.
                final int scrollY=scrolling.getScrollY();
                InputMethodManager input=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
                if(input!=null)input.hideSoftInputFromWindow(intervalInput.getWindowToken(),0);
                intervalInput.clearFocus();
                root.requestFocus();
                // Samsung keyboard can relayout the ScrollView twice: once when
                // the IME starts hiding, again when the viewport expands.
                // Restore the user's position after those layout passes.
                scrolling.post(()->scrolling.scrollTo(0,scrollY));
                scrolling.postDelayed(()->scrolling.scrollTo(0,scrollY),180);
                scrolling.postDelayed(()->scrolling.scrollTo(0,scrollY),340);
                return true;
            }
            return false;
        });
        target.addView(fields, margin(-1, -2, 8, 0));
        TextView intervalHelp = txt("الفاصل بين النبضات بالمللي ثانية (200 إلى 60000) • الافتراضي 1000", 10, MUTED, false);
        target.addView(intervalHelp, margin(-1, -2, 8, 0));
        target.addView(txt("عناوين جاهزة", 10, MUTED, true), margin(-1, -2, 13, 7));
        // Independent, evenly spaced address chips. Use symmetric margins so
        // none of the rounded corners touch in either RTL or LTR layouts.
        LinearLayout presets = horizontal();
        presets.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        String[] defaultTargets = {"10.0.0.1", "8.8.8.8", "www.google.com"};
        for (int i = 0; i < defaultTargets.length; i++) {
            final String item = defaultTargets[i];
            TextView option = txt(item, i == 2 ? 11 : 12, GREEN, true);
            option.setSingleLine(true);
            option.setEllipsize(android.text.TextUtils.TruncateAt.END);
            option.setTextDirection(View.TEXT_DIRECTION_LTR);
            option.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            option.setGravity(Gravity.CENTER);
            option.setPadding(dp(5), 0, dp(5), 0);
            option.setBackground(shape(palette.accentSoft, BORDER, 12));
            option.setMinimumHeight(dp(49));
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                    0, dp(49), i == 2 ? 1.75f : 1.05f);
            pp.leftMargin = dp(5);
            pp.rightMargin = dp(5);
            presets.addView(option, pp);
            option.setOnClickListener(v -> {
                if (PingState.INSTANCE.snapshot().running) {
                    Toast.makeText(this, "أوقف المراقبة لتغيير العنوان", Toast.LENGTH_SHORT).show();
                } else {
                    hostInput.setText(item);
                    hostInput.setSelection(item.length());
                }
            });
        }
        target.addView(presets);
        root.addView(target);

        LinearLayout actions = horizontal();
        startButton = new Button(this);
        startButton.setAllCaps(false);
        startButton.setText("▶  بدء المراقبة");
        startButton.setTextColor(palette.onAccent);
        startButton.setTextSize(14);
        startButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        startButton.setBackground(shape(GREEN, 0, 14));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, dp(52));
        actions.addView(startButton, ap);
        startButton.setOnClickListener(v -> toggleMonitor());

        root.addView(actions, margin(-1, -2, 12, 0));

        LinearLayout hero = vertical();
        hero.setPadding(dp(20), dp(18), dp(20), dp(17));
        hero.setBackground(shape(CARD, BORDER, 20));
        hero.addView(txt("زمن الاستجابة الحالي", 11, MUTED, true));
        heroText = txt("—", 46, GREEN, true);
        heroText.setTypeface(Typeface.create("sans-serif-light", Typeface.BOLD));
        hero.addView(heroText, margin(-1, -2, 4, 0));
        heroDetail = txt("ابدأ المراقبة لعرض نتيجة كل نبضة", 12, MUTED, false);
        hero.addView(heroDetail);
        TextView divider = txt("────────────────────────────", 14, BORDER, false);
        divider.setSingleLine(true);
        hero.addView(divider, margin(-1, -2, 14, 8));
        LinearLayout outageRow = horizontal();
        outageRow.addView(txt("مدة الانقطاع الحالي", 11, MUTED, true), new LinearLayout.LayoutParams(0, -2, 1));
        outageText = txt("00:00:00", 22, GREEN, true);
        outageText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        outageRow.addView(outageText);
        hero.addView(outageRow);
        root.addView(hero, margin(-1, -2, 14, 0));

        addHeading(root, "إحصائيات الاتصال");
        LinearLayout first = horizontal();
        stats[0] = statCard(first, "المرسلة", "0");
        stats[1] = statCard(first, "المستلمة", "0");
        stats[2] = statCard(first, "المفقودة", "0");
        root.addView(first);
        LinearLayout second = horizontal();
        stats[3] = statCard(second, "نسبة الفقد", "0.0%");
        stats[4] = statCard(second, "متوسط البينغ", "—");
        stats[5] = statCard(second, "التذبذب", "—");
        root.addView(second, margin(-1, -2, 9, 0));

        addHeading(root, "الرسم البياني للاستجابة");
        LinearLayout graphCard = vertical();
        graphCard.setPadding(dp(10), dp(8), dp(10), dp(8));
        graphCard.setBackground(shape(CARD, BORDER, 18));
        graph = new LatencyGraphView(this);
        graphCard.addView(graph, new LinearLayout.LayoutParams(-1, dp(220)));
        root.addView(graphCard);

        addHeading(root, "سجل الفصل للجلسة الحالية");
        outageCards=vertical();
        outageCards.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.addView(outageCards);

        addHeading(root, "نتائج النبضات بالتفصيل");
        pingCards=vertical();
        pingCards.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.addView(pingCards);
        AppFooter.add(this,root);
    }

    private TextView statCard(LinearLayout row, String label, String initial) {
        LinearLayout card = vertical();
        card.setPadding(dp(11), dp(12), dp(5), dp(12));
        card.setBackground(shape(CARD, BORDER, 12));
        card.addView(txt(label, 10, MUTED, true));
        TextView result = txt(initial, 17, WHITE, true);
        result.setSingleLine(true);
        card.addView(result, margin(-1, -2, 8, 0));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(86), 1f);
        p.rightMargin = dp(6);
        row.addView(card, p);
        return result;
    }

    private void toggleMonitor() {
        if (PingState.INSTANCE.snapshot().running) {
            Intent stop = new Intent(this, PingService.class).setAction(PingService.ACTION_STOP);
            startService(stop);
            return;
        }
        String target = hostInput.getText().toString().trim();
        if (!PingService.validTarget(target)) {
            hostInput.setError("أدخل عنوان IP أو اسم موقع صحيح");
            hostInput.requestFocus();
            return;
        }
        int millis;
        try {
            millis = Integer.parseInt(intervalInput.getText().toString().trim());
        } catch (NumberFormatException e) {
            intervalInput.setError("اكتب الفاصل بالمللي ثانية");
            intervalInput.requestFocus();
            return;
        }
        if (millis < 200 || millis > 60000) {
            intervalInput.setError("اختر من 200 إلى 60000 مللي ثانية");
            intervalInput.requestFocus();
            return;
        }
        Intent start = new Intent(this, PingService.class)
                .setAction(PingService.ACTION_START)
                .putExtra(PingService.EXTRA_HOST, target)
                .putExtra(PingService.EXTRA_INTERVAL, millis);
        try {
            startForegroundService(start);
        } catch (Exception e) {
            Toast.makeText(this, "تعذّر بدء المراقبة: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void paintLiveState() {
        PingState.Snapshot s = PingState.INSTANCE.snapshot();
        PingSample last = s.last();
        lastRunning = s.running;
        boolean down = s.running && s.isDown();
        boolean error = s.running && last != null && last.kind == PingSample.Kind.ERROR;
        statusText.setText(s.running ? (down ? "●  منقطع" : error ? "●  خطأ" : "●  متصل") : "●  متوقف");
        statusText.setTextColor(down ? RED : error ? ORANGE : s.running ? GREEN : MUTED);
        startButton.setText(s.running ? "■  إيقاف المراقبة" : "▶  بدء المراقبة");
        startButton.setTextColor(s.running ? WHITE : palette.onAccent);
        startButton.setBackground(shape(s.running ? palette.stop : GREEN, 0, 14));
        hostInput.setEnabled(!s.running);
        intervalInput.setEnabled(!s.running);
        if (!s.running && last == null) {
            heroText.setText("—");
            heroDetail.setText("ابدأ المراقبة لعرض نتيجة كل نبضة");
            heroText.setTextColor(GREEN);
        } else if (down) {
            heroText.setText("منقطع");
            heroText.setTextSize(40);
            heroText.setTextColor(RED);
            heroDetail.setText("لا توجد استجابة من " + s.host);
        } else if (error) {
            heroText.setText("خطأ الفحص");
            heroText.setTextSize(40);
            heroText.setTextColor(ORANGE);
            heroDetail.setText(last.detail);
        } else if (last != null && last.kind == PingSample.Kind.REPLY) {
            heroText.setTextSize(46);
            heroText.setText(String.format(Locale.US, "%.2f مللي ثانية", last.rttMs));
            heroText.setTextColor(GREEN);
            heroDetail.setText("النبضة رقم " + last.sequence + "   TTL=" + last.ttl + "   •   " + s.host);
        } else {
            heroText.setText("—");
            heroDetail.setText("بانتظار أول نبضة فحص");
        }
        long outage = down ? s.downMs : 0;
        outageText.setText(duration(outage));
        outageText.setTextColor(down ? RED : GREEN);
        stats[0].setText(Long.toString(s.sent));
        stats[1].setText(Long.toString(s.received));
        stats[2].setText(Long.toString(s.lost));
        stats[2].setTextColor(s.lost > 0 ? RED : WHITE);
        stats[3].setText(String.format(Locale.US, "%.1f%%", s.sent == 0 ? 0 : s.lost * 100.0 / s.sent));
        stats[4].setText(s.received == 0 ? "—" : String.format(Locale.US, "%.1f ملث", s.avgRtt));
        stats[5].setText(s.received < 2 ? "—" : String.format(Locale.US, "%.1f ملث", s.jitter));
        if (lastRevision != s.revision) {
            lastRevision = s.revision;
            graph.setSamples(s.samples);
            showPackets(s.samples);
            showOutages(s.outages, s.totalDownMs, s.errors);
        }
    }

    private static String duration(long millis) {
        long secs = Math.max(0, millis / 1000);
        return String.format(Locale.US, "%02d:%02d:%02d", secs / 3600, (secs / 60) % 60, secs % 60);
    }

    private void showPackets(List<PingSample> items) {
        if(pingCards==null)return;
        pingCards.removeAllViews();
        if(items.isEmpty()){
            LinearLayout card=ResultCards.newCard(this,pingCards);
            ResultCards.note(this,card,"بانتظار نتائج النبضات...",MUTED);
            return;
        }
        SimpleDateFormat time=new SimpleDateFormat("HH:mm:ss",Locale.US);
        for(int i=items.size()-1,count=0;i>=0 && count<60;i--,count++) {
            PingSample p=items.get(i);
            ResultCards.ping(this,pingCards,p,time.format(new Date(p.wallTime)));
        }
    }

    private void showOutages(List<PingState.Outage> items,long downTime,long errors) {
        if(outageCards==null)return;
        outageCards.removeAllViews();
        LinearLayout overview=ResultCards.newCard(this,outageCards);
        ResultCards.title(this,overview,"ملخص جلسة المراقبة");
        ResultCards.field(this,overview,"مرات الانقطاع",Integer.toString(outageStore.count()),true);
        ResultCards.field(this,overview,"مدة الانقطاع",duration(downTime),true);
        if(errors>0)ResultCards.field(this,overview,"أخطاء الفحص",Long.toString(errors),true);

        List<OutageStore.Entry> history=outageStore.recent(50);
        if(history.isEmpty()){
            ResultCards.note(this,overview,"لم يتم تسجيل أي انقطاع حتى الآن.",MUTED);
            return;
        }
        SimpleDateFormat date=new SimpleDateFormat("yyyy/MM/dd HH:mm:ss",Locale.US);
        for(int i=0;i<history.size()&&i<20;i++){
            OutageStore.Entry e=history.get(i);
            String state;
            if("active".equals(e.outcome))state="الانقطاع مستمر";
            else if("recovered".equals(e.outcome))state="عاد الاتصال";
            else if("stopped".equals(e.outcome))state="توقفت المراقبة";
            else if("error".equals(e.outcome))state="خطأ أثناء الفحص";
            else state="انقطع الرصد قبل التأكد من العودة";
            long measured=e.durationMs;
            if(e.active() && PingState.INSTANCE.snapshot().running)
                measured=PingState.INSTANCE.snapshot().downMs;
            ResultCards.outage(this,outageCards,"الانقطاع رقم "+(i+1),
                    e.host,date.format(new Date(e.beganAt)),duration(measured),state);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        ui.removeCallbacks(refresh);
        ui.post(refresh);
    }

    @Override protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (sidebar != null && sidebar.isOpen()) { sidebar.close(); return; }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        ui.removeCallbacks(refresh);
        if (outageStore != null) outageStore.close();
        super.onDestroy();
    }
}
