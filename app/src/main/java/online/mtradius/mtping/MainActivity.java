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
    private static final int BG = 0xFF07121E;
    private static final int CARD = 0xFF101F30;
    private static final int BORDER = 0xFF20364B;
    private static final int WHITE = 0xFFF1F5F9;
    private static final int MUTED = 0xFF90A2B8;
    private static final int GREEN = 0xFF3EDAB6;
    private static final int RED = 0xFFFF6278;
    private static final int ORANGE = 0xFFFFC36A;
    private static final int[] INTERVALS = {1000, 2000, 5000};
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            paintLiveState();
            ui.postDelayed(this, 350);
        }
    };

    private EditText hostInput;
    private Button startButton, intervalButton;
    private TextView statusText, heroText, heroDetail, outageText, logText, outageHistory;
    private TextView[] stats = new TextView[6];
    private LatencyGraphView graph;
    private int intervalIndex;
    private long lastRevision = Long.MIN_VALUE;
    private OutageStore outageStore;
    private boolean lastRunning;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        outageStore = new OutageStore(this);
        buildUI();
        String host = getSharedPreferences("settings", MODE_PRIVATE).getString("host", "10.0.0.1");
        hostInput.setText(host);
        int interval = getSharedPreferences("settings", MODE_PRIVATE).getInt("interval", 1000);
        intervalIndex = interval == 5000 ? 2 : interval == 2000 ? 1 : 0;
        intervalButton.setText(INTERVALS[intervalIndex] / 1000 + " ث");
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
            scrolling.setClipToPadding(false);
            scrolling.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets topSafe = insets.getInsets(
                        WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets bottomSafe = insets.getInsets(
                        WindowInsets.Type.navigationBars());
                view.setPadding(0, topSafe.top, 0, bottomSafe.bottom);
                return insets;
            });
        }
        setContentView(scrolling);
        LinearLayout root = vertical();
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scrolling.addView(root);

        LinearLayout header = horizontal();
        header.setMinimumHeight(dp(48));
        TextView brand = txt("MT_PING", 25, WHITE, true);
        brand.setLetterSpacing(.10f);
        brand.setSingleLine(true);
        brand.setTextDirection(View.TEXT_DIRECTION_LTR);
        brand.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(0, -2, 1);
        brandParams.leftMargin = dp(10);
        header.addView(brand, brandParams);
        statusText = txt("●  متوقف", 12, MUTED, true);
        statusText.setPadding(dp(12), dp(9), dp(12), dp(9));
        statusText.setBackground(shape(CARD, BORDER, 30));
        header.addView(statusText);
        root.addView(header);
        root.addView(new View(this), margin(-1, dp(8), 0, 0));

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
        hostInput.setBackground(shape(0xFF091826, BORDER, 12));
        hostInput.setPadding(dp(12), dp(11), dp(12), dp(11));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, dp(52), 1);
        ip.rightMargin = dp(9);
        fields.addView(hostInput, ip);
        intervalButton = new Button(this);
        intervalButton.setAllCaps(false);
        intervalButton.setTextColor(GREEN);
        intervalButton.setTextSize(13);
        intervalButton.setBackground(shape(0xFF173245, 0, 12));
        fields.addView(intervalButton, new LinearLayout.LayoutParams(dp(68), dp(52)));
        intervalButton.setOnClickListener(v -> {
            if (PingState.INSTANCE.snapshot().running) {
                Toast.makeText(this, "أوقف المراقبة لتغيير مدة النبضة", Toast.LENGTH_SHORT).show();
                return;
            }
            intervalIndex = (intervalIndex + 1) % INTERVALS.length;
            intervalButton.setText(INTERVALS[intervalIndex] / 1000 + " ث");
        });
        target.addView(fields, margin(-1, -2, 8, 0));
        target.addView(txt("عناوين جاهزة", 10, MUTED, true), margin(-1, -2, 13, 7));
        LinearLayout presets = horizontal();
        String[] defaultTargets = {"10.0.0.1", "8.8.8.8", "www.google.com"};
        for (int i = 0; i < defaultTargets.length; i++) {
            final String item = defaultTargets[i];
            TextView option = txt(item, 11, GREEN, true);
            option.setSingleLine(true);
            option.setGravity(Gravity.CENTER);
            option.setPadding(dp(5), dp(11), dp(5), dp(11));
            option.setBackground(shape(0xFF173245, BORDER, 9));
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, -2, i == 2 ? 1.65f : 1);
            if (i > 0) pp.leftMargin = dp(7);
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
        startButton.setTextColor(BG);
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
        root.addView(txt("آخر 60 نبضة • تدريج مللي ثانية متغير لإظهار فروقات البينغ الصغيرة",
                10, MUTED, false), margin(-1, -2, 0, 7));
        LinearLayout graphCard = vertical();
        graphCard.setPadding(dp(10), dp(8), dp(10), dp(8));
        graphCard.setBackground(shape(CARD, BORDER, 18));
        graph = new LatencyGraphView(this);
        graphCard.addView(graph, new LinearLayout.LayoutParams(-1, dp(220)));
        root.addView(graphCard);

        addHeading(root, "سجل الفصل للجلسة الحالية");
        outageHistory = txt("لم يتم تسجيل أي انقطاع حتى الآن.", 12, MUTED, false);
        outageHistory.setPadding(dp(15), dp(12), dp(15), dp(12));
        outageHistory.setBackground(shape(CARD, BORDER, 16));
        root.addView(outageHistory);
        outageHistory.setTextIsSelectable(true);

        addHeading(root, "نتائج النبضات بالتفصيل");
        logText = txt("بانتظار نتائج النبضات...", 12, GREEN, false);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextDirection(View.TEXT_DIRECTION_RTL);
        logText.setGravity(Gravity.RIGHT);
        logText.setPadding(dp(13), dp(14), dp(13), dp(14));
        logText.setBackground(shape(0xFF030D16, BORDER, 16));
        logText.setTextIsSelectable(true);
        root.addView(logText);
        TextView foot = txt("MT_RADIUS • مراقبة مستمرة بالخلفية", 10, MUTED, false);
        foot.setGravity(Gravity.CENTER);
        root.addView(foot, margin(-1, -2, 22, 0));
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
        Intent start = new Intent(this, PingService.class)
                .setAction(PingService.ACTION_START)
                .putExtra(PingService.EXTRA_HOST, target)
                .putExtra(PingService.EXTRA_INTERVAL, INTERVALS[intervalIndex]);
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
        startButton.setTextColor(s.running ? WHITE : BG);
        startButton.setBackground(shape(s.running ? 0xFFB33B50 : GREEN, 0, 14));
        hostInput.setEnabled(!s.running);
        intervalButton.setEnabled(!s.running);
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
        if (items.isEmpty()) {
            logText.setText("بانتظار نتائج النبضات...");
            return;
        }
        SimpleDateFormat date = new SimpleDateFormat("HH:mm:ss", Locale.US);
        StringBuilder buffer = new StringBuilder();
        for (int i = items.size() - 1, count = 0; i >= 0 && count < 90; i--, count++) {
            PingSample p = items.get(i);
            buffer.append(date.format(new Date(p.wallTime)))
                    .append("  #").append(p.sequence).append("  ");
            if (p.kind == PingSample.Kind.REPLY) {
                buffer.append(String.format(Locale.US, "%.2f مللي ثانية  TTL=%d", p.rttMs, p.ttl));
            } else if (p.kind == PingSample.Kind.TIMEOUT) {
                buffer.append("✖ انتهت المهلة دون استجابة");
            } else {
                buffer.append("خطأ في الفحص: ").append(p.detail);
            }
            buffer.append('\n');
        }
        logText.setText(buffer.toString());
    }

    private void showOutages(List<PingState.Outage> items, long downTime, long errors) {
        SimpleDateFormat time = new SimpleDateFormat("yyyy/MM/dd  HH:mm:ss", Locale.US);
        List<OutageStore.Entry> history = outageStore.recent(50);
        StringBuilder b = new StringBuilder();
        b.append("مرات الفصل خلال المراقبة الحالية: ").append(outageStore.count())
                .append("  •  مدة الفصل بهذه الجلسة: ").append(duration(downTime))
                .append("  •  أخطاء الفحص: ").append(errors).append("\n");
        if (history.isEmpty()) {
            b.append("\nلم يتم تسجيل أي انقطاع حتى الآن.");
        } else {
            int index = 0;
            for (OutageStore.Entry e : history) {
                if (++index > 20) break;
                String cause;
                if ("active".equals(e.outcome)) cause = "الانقطاع مستمر";
                else if ("recovered".equals(e.outcome)) cause = "عاد الاتصال";
                else if ("stopped".equals(e.outcome)) cause = "توقّف الرصد";
                else if ("error".equals(e.outcome)) cause = "تعذّر الفحص";
                else cause = "انقطع الرصد قبل التأكد من العودة";
                long measured = e.durationMs;
                if (e.active() && PingState.INSTANCE.snapshot().running) {
                    measured = PingState.INSTANCE.snapshot().downMs;
                }
                b.append("\n").append(index).append(" - ").append(e.host)
                        .append("\n    البداية: ").append(time.format(new Date(e.beganAt)))
                        .append("\n    المدة: ").append(duration(measured))
                        .append("  •  ").append(cause).append("\n");
            }
        }
        outageHistory.setText(b.toString());
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

    @Override protected void onDestroy() {
        ui.removeCallbacks(refresh);
        if (outageStore != null) outageStore.close();
        super.onDestroy();
    }
}
