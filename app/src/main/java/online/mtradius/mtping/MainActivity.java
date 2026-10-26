package online.mtradius.mtping;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
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
    // Track the actual appearance used to construct this Activity's views.
    private String appliedAppearanceMode;
    private boolean appliedAppearanceDark;
    private int BG,CARD,BORDER,WHITE,MUTED,GREEN,RED,ORANGE;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            paintLiveState();
            ui.postDelayed(this, 350);
        }
    };

    private Sidebar sidebar;
    private EditText hostInput,secondHostInput,thirdHostInput;
    private int lastFocusedHostIndex=0;
    private final TextView[] targetStatusRows=new TextView[3];
    private int selectedSlot=0;
    private Button startButton;
    private EditText intervalInput;
    private TextView statusText, heroText, heroDetail, outageText;
    private LinearLayout pingCards, outageCards;
    private TextView[] stats = new TextView[6];
    private LatencyGraphView graph;
    private long lastRevision = Long.MIN_VALUE;
    private long lastPacketSequence = -1;
    private boolean packetsEmptyShown;
    private OutageStore outageStore;
    private boolean lastRunning;

    @Override public void onCreate(Bundle saved) {
        ThemePalette.prepareActivity(this);
        super.onCreate(saved);
        palette=ThemePalette.of(this);
        appliedAppearanceMode=ThemePalette.getMode(this);
        appliedAppearanceDark=palette.dark;
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
        secondHostInput.setText(getSharedPreferences("settings",MODE_PRIVATE).getString("host1",""));
        thirdHostInput.setText(getSharedPreferences("settings",MODE_PRIVATE).getString("host2",""));
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
        header.setMinimumHeight(dp(65));
        header.setPadding(dp(8),dp(7),dp(8),dp(7));
        header.setBackground(shape(CARD,BORDER,18));
        if(!palette.dark)header.setElevation(dp(2));
        TextView menuButton = txt("☰", 26, GREEN, true);
        menuButton.setGravity(Gravity.CENTER);
        menuButton.setBackground(shape(palette.accentSoft,BORDER,13));
        header.addView(menuButton,new LinearLayout.LayoutParams(dp(48),dp(48)));
        menuButton.setContentDescription("فتح القائمة الجانبية");
        menuButton.setOnClickListener(v -> sidebar.open());

        TextView brand = txt("MT_TOOLS", 23, WHITE, true);
        brand.setLetterSpacing(.045f);
        brand.setSingleLine(true);
        brand.setTextDirection(View.TEXT_DIRECTION_LTR);
        brand.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(0, -2, 1);
        brandParams.rightMargin = dp(9);
        header.addView(brand,brandParams);

        statusText = txt("●  متوقف", 11, MUTED, true);
        statusText.setPadding(dp(10),dp(9),dp(10),dp(9));
        statusText.setBackground(shape(CARD,BORDER,30));
        header.addView(statusText);
        ThemeSelector.attachIcon(this,header);
        root.addView(header);
        root.addView(new View(this),margin(-1,dp(14),0,0));

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
        target.addView(txt("عناوين جاهزة • اضغط مطولاً للتعديل", 10, MUTED, true),
                margin(-1, -2, 13, 7));
        // Independent, evenly spaced address chips. Use symmetric margins so
        // none of the rounded corners touch in either RTL or LTR layouts.
        LinearLayout presets = horizontal();
        presets.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        for (int i = 0; i < PingPresets.COUNT; i++) {
            final int presetIndex = i;
            TextView option = txt(readPreset(presetIndex),
                    i == 2 ? 11 : 12, GREEN, true);
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
                    Toast.makeText(this,"أوقف المراقبة لتغيير عنوان الفحص",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                EditText field=focusedHostField();
                String destination=readPreset(presetIndex);
                field.setText(destination);
                field.setSelection(destination.length());
                // Continue using this target until another Ping input is focused.
                field.requestFocus();
            });
            option.setOnLongClickListener(v -> {
                editPreset(presetIndex,option);
                return true; // Consume the long click, don't fill a host input.
            });
            option.setContentDescription("عنوان جاهز: "+option.getText()+
                    "؛ اضغط لاستخدامه في الحقل المختار، أو اضغط مطولاً لتعديله");
        }
        target.addView(presets);
        target.addView(txt("أهداف إضافية (اختياري • حتى 3)",11,MUTED,true),
                margin(-1,-2,14,4));
        secondHostInput=additionalHostField("العنوان الثاني • مثال: 8.8.8.8");
        target.addView(secondHostInput,margin(-1,dp(47),5,0));
        thirdHostInput=additionalHostField("العنوان الثالث • مثال: www.google.com");
        target.addView(thirdHostInput,margin(-1,dp(47),8,0));
        hostInput.setOnFocusChangeListener((v,focused)->{
            if(focused){lastFocusedHostIndex=0;highlightActiveHost();}
        });
        secondHostInput.setOnFocusChangeListener((v,focused)->{
            if(focused){lastFocusedHostIndex=1;highlightActiveHost();}
        });
        thirdHostInput.setOnFocusChangeListener((v,focused)->{
            if(focused){lastFocusedHostIndex=2;highlightActiveHost();}
        });
        root.addView(target);
        highlightActiveHost();

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

        LinearLayout summary=vertical();
        summary.setPadding(dp(13),dp(12),dp(13),dp(10));
        summary.setBackground(shape(CARD,BORDER,18));
        summary.addView(txt("المراقبة المتزامنة • اضغط عنواناً للتفاصيل",12,MUTED,true));
        for(int i=0;i<3;i++){
            final int slot=i;
            TextView row=txt("—",13,WHITE,true);
            row.setSingleLine(true);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12),dp(9),dp(12),dp(9));
            row.setMinimumHeight(dp(46));
            row.setOnClickListener(v->selectTarget(slot));
            targetStatusRows[i]=row;
            summary.addView(row,margin(-1,-2,5,0));
        }
        root.addView(summary,margin(-1,-2,10,0));

        LinearLayout hero = vertical();
        hero.setPadding(dp(20), dp(18), dp(20), dp(17));
        hero.setBackground(shape(CARD, BORDER, 20));
        hero.addView(txt("زمن الاستجابة الحالي", 11, MUTED, true));
        heroText = txt("—", 46, GREEN, true);
        heroText.setSingleLine(true);
        heroText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heroText.setAutoSizeTextTypeUniformWithConfiguration(22,46,1,
                android.util.TypedValue.COMPLEX_UNIT_SP);
        heroText.setTextDirection(View.TEXT_DIRECTION_LTR);
        heroText.setGravity(Gravity.RIGHT);
        heroText.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
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

        addHeading(root, "نتائج النبضات • آخر 60");
        pingCards=vertical();
        pingCards.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.addView(pingCards);
        AppFooter.add(this,root);
    }

    /** Make it obvious which of the three Ping fields receives the preset. */
    private void highlightActiveHost() {
        if(hostInput!=null)
            hostInput.setBackground(shape(palette.panel,
                    lastFocusedHostIndex==0?GREEN:BORDER,12));
        if(secondHostInput!=null)
            secondHostInput.setBackground(shape(palette.panel,
                    lastFocusedHostIndex==1?GREEN:BORDER,12));
        if(thirdHostInput!=null)
            thirdHostInput.setBackground(shape(palette.panel,
                    lastFocusedHostIndex==2?GREEN:BORDER,12));
    }

    private EditText focusedHostField() {
        int index=PingPresets.fieldIndex(lastFocusedHostIndex);
        if(index==1)return secondHostInput;
        if(index==2)return thirdHostInput;
        return hostInput;
    }

    private String readPreset(int presetIndex) {
        String fallback=PingPresets.defaultAt(presetIndex);
        String saved=getSharedPreferences(PingPresets.PREFERENCES,MODE_PRIVATE)
                .getString(PingPresets.keyAt(presetIndex),fallback);
        try {
            return PingPresets.validate(saved);
        }catch(IllegalArgumentException ignored){
            return fallback;
        }
    }

    private void editPreset(int presetIndex,TextView chip) {
        EditText editor=additionalHostField("عنوان IP أو اسم موقع");
        String current=readPreset(presetIndex);
        editor.setText(current);
        editor.setSelection(current.length());
        LinearLayout wrapper=vertical();
        wrapper.setPadding(dp(16),dp(10),dp(16),0);
        wrapper.addView(editor,new LinearLayout.LayoutParams(-1,dp(52)));
        AlertDialog dialog=new AlertDialog.Builder(this)
                .setTitle("تعديل العنوان الجاهز "+(presetIndex+1))
                .setMessage("اكتب عنوان السيرفر أو الموقع الذي تريد حفظه للاستخدام القادم.")
                .setView(wrapper)
                .setNegativeButton("إلغاء",null)
                .setPositiveButton("حفظ",null)
                .create();
        dialog.setOnShowListener(unused->{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                String updated;
                try {
                    updated=PingPresets.validate(editor.getText().toString());
                }catch(IllegalArgumentException invalid){
                    editor.setError(invalid.getMessage());
                    return; // Keep the dialog open until the entry is valid.
                }
                boolean saved=getSharedPreferences(PingPresets.PREFERENCES,MODE_PRIVATE)
                        .edit().putString(PingPresets.keyAt(presetIndex),updated)
                        .commit();
                if(!saved){
                    editor.setError("تعذّر حفظ العنوان. حاول مجدداً.");
                    return;
                }
                chip.setText(updated);
                chip.setContentDescription("عنوان جاهز: "+updated+
                        "؛ اضغط لاستخدامه في الحقل المختار، أو اضغط مطولاً لتعديله");
                Toast.makeText(this,"تم حفظ العنوان الجاهز",Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
        });
        dialog.show();
        editor.requestFocus();
        if(dialog.getWindow()!=null)
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }

    private EditText additionalHostField(String hint) {
        EditText field=new EditText(this);
        field.setSingleLine(true);
        field.setTextSize(15);
        field.setTextColor(WHITE);
        field.setHintTextColor(MUTED);
        field.setHint(hint);
        field.setTypeface(Typeface.MONOSPACE);
        field.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        field.setTextDirection(View.TEXT_DIRECTION_LTR);
        field.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);
        field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE|EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setPadding(dp(13),dp(8),dp(13),dp(8));
        field.setBackground(shape(palette.panel,BORDER,12));
        return field;
    }

    private void selectTarget(int slot) {
        if(slot<0 || slot>2)return;
        if(!PingState.forSlot(slot).snapshot().running)return;
        selectedSlot=slot;
        lastRevision=Long.MIN_VALUE;
        lastPacketSequence=-1;
        packetsEmptyShown=false;
        if(pingCards!=null)pingCards.removeAllViews();
        paintLiveState();
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
        String[] targets;
        try {
            targets=MonitorTargets.normalize(hostInput.getText().toString(),
                    secondHostInput.getText().toString(),thirdHostInput.getText().toString());
        } catch(IllegalArgumentException e) {
            hostInput.setError(e.getMessage());
            Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();
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
                .putExtra(PingService.EXTRA_HOSTS, targets)
                .putExtra(PingService.EXTRA_INTERVAL, millis);
        try {
            startForegroundService(start);
            maybeRequestBatteryExemption();
        } catch (Exception e) {
            Toast.makeText(this, "تعذّر بدء المراقبة: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Android's battery-optimization exclusion is a special user-approved
     * Settings flow, not a normal requestPermissions() runtime permission.
     * Show it once, only after the user explicitly starts background Ping.
     */
    private void maybeRequestBatteryExemption() {
        if(Build.VERSION.SDK_INT<23)return;
        PowerManager manager=(PowerManager)getSystemService(Context.POWER_SERVICE);
        if(manager==null || manager.isIgnoringBatteryOptimizations(getPackageName()))return;
        SharedPreferences prefs=getSharedPreferences("background_monitoring",MODE_PRIVATE);
        if(prefs.getBoolean("battery_prompt_shown",false))return;
        prefs.edit().putBoolean("battery_prompt_shown",true).apply();
        new AlertDialog.Builder(this)
                .setTitle("استمرار مراقبة Ping بالخلفية")
                .setMessage("حتى يواصل MT_TOOLS فحص الاتصال وتسجيل الفصل أثناء إغلاق "
                        +"الواجهة، اسمح له بالعمل دون قيود تحسين البطارية.")
                .setPositiveButton("السماح", (dialog,which)->{
                    Intent direct=new Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    direct.setData(Uri.parse("package:"+getPackageName()));
                    try {
                        startActivity(direct);
                    }catch(Exception unavailable) {
                        try {
                            startActivity(new Intent(
                                    Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                        }catch(Exception unsupported) {
                            Toast.makeText(this,
                                    "افتح إعدادات التطبيق ثم البطارية، واختر غير مقيّد",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("لاحقاً",null)
                .show();
    }

    private void paintLiveState() {
        PingState.Snapshot[] snapshots=PingState.liveSnapshots();
        boolean anyRunning=snapshots[0].running;
        if(!anyRunning)selectedSlot=0;
        if(selectedSlot>0 && !snapshots[selectedSlot].running)selectedSlot=0;
        for(int i=0;i<3;i++){
            TextView card=targetStatusRows[i];
            PingState.Snapshot unit=snapshots[i];
            if(!unit.running){
                card.setVisibility(i==0?View.VISIBLE:View.GONE);
                if(i==0)card.setText("المراقبة متوقفة");
                continue;
            }
            card.setVisibility(View.VISIBLE);
            PingSample recent=unit.last();
            String label;
            int color;
            if(unit.isDown()){label="منقطع • "+duration(unit.downMs);color=RED;}
            else if(unit.verifyingLoss){label="فقد نبضة";color=ORANGE;}
            else if(recent==null){label="جار الفحص";color=MUTED;}
            else if(recent.kind==PingSample.Kind.REPLY){
                label=String.format(Locale.US,"%.2f ms",recent.rttMs);color=GREEN;
            } else if(recent.kind==PingSample.Kind.ERROR){label="خطأ الفحص";color=ORANGE;}
            else{label="فقد نبضة";color=ORANGE;}
            card.setText("\u2066"+unit.host+"\u2069  •  "+label);
            card.setTextColor(color);
            card.setBackground(shape(i==selectedSlot?palette.accentSoft:palette.panel,
                    i==selectedSlot?GREEN:BORDER,12));
        }
        PingState.Snapshot s=snapshots[selectedSlot];
        PingSample last = s.last();
        lastRunning = s.running;
        boolean down = s.running && s.isDown();
        boolean lostOne = s.running && s.verifyingLoss;
        boolean error = s.running && last != null && last.kind == PingSample.Kind.ERROR;
        statusText.setText(s.running ? (down ? "●  منقطع" :
                lostOne ? "●  فقد نبضة" : error ? "●  خطأ" : "●  متصل") : "●  متوقف");
        statusText.setTextColor(down ? RED : lostOne || error ? ORANGE : s.running ? GREEN : MUTED);
        startButton.setText(anyRunning ? "■  إيقاف المراقبة" : "▶  بدء المراقبة");
        startButton.setTextColor(anyRunning ? WHITE : palette.onAccent);
        startButton.setBackground(shape(anyRunning ? palette.stop : GREEN, 0, 14));
        hostInput.setEnabled(!anyRunning);
        secondHostInput.setEnabled(!anyRunning);
        thirdHostInput.setEnabled(!anyRunning);
        intervalInput.setEnabled(!anyRunning);
        if (!s.running && last == null) {
            heroText.setText("—");
            heroDetail.setText("ابدأ المراقبة لعرض نتيجة كل نبضة");
            heroText.setTextColor(GREEN);
        } else if (down) {
            heroText.setText("منقطع");
            heroText.setTextSize(40);
            heroText.setTextColor(RED);
            heroDetail.setText("لا توجد استجابة من " + s.host);
        } else if (lostOne) {
            heroText.setText("نبضة مفقودة");
            heroText.setTextSize(35);
            heroText.setTextColor(ORANGE);
            heroDetail.setText("جار التأكد من الاتصال • " + s.host);
        } else if (error) {
            heroText.setText("خطأ الفحص");
            heroText.setTextSize(40);
            heroText.setTextColor(ORANGE);
            heroDetail.setText(last.detail);
        } else if (last != null && last.kind == PingSample.Kind.REPLY) {
            heroText.setTextSize(46);
            heroText.setText(String.format(Locale.US, "%.2f ms", last.rttMs));
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
            showOutages(s.host,s.outages, s.totalDownMs, s.errors);
        }
    }

    private static String duration(long millis) {
        long secs = Math.max(0, millis / 1000);
        return String.format(Locale.US, "%02d:%02d:%02d", secs / 3600, (secs / 60) % 60, secs % 60);
    }

    private void showPackets(List<PingSample> items) {
        if(pingCards==null)return;
        if(items.isEmpty()){
            if(!packetsEmptyShown){
                pingCards.removeAllViews();
                LinearLayout card=ResultCards.newCard(this,pingCards);
                ResultCards.note(this,card,"بانتظار نتائج النبضات...",MUTED);
                packetsEmptyShown=true;
            }
            lastPacketSequence=-1;
            return;
        }
        long newest=items.get(items.size()-1).sequence;
        if(newest<lastPacketSequence || packetsEmptyShown){
            pingCards.removeAllViews();
            lastPacketSequence=-1;
            packetsEmptyShown=false;
        }
        // Only append newly received pulses. Recreating 60 cards every second
        // would cause jank and make long-running monitoring waste resources.
        if(newest==lastPacketSequence)return;
        SimpleDateFormat time=new SimpleDateFormat("HH:mm:ss",Locale.US);
        int start=Math.max(0,items.size()-60);
        for(int i=start;i<items.size();i++){
            PingSample p=items.get(i);
            if(p.sequence<=lastPacketSequence)continue;
            ResultCards.ping(this,pingCards,p,time.format(new Date(p.wallTime)));
        }
        lastPacketSequence=newest;
        while(pingCards.getChildCount()>60)
            pingCards.removeViewAt(pingCards.getChildCount()-1);
    }

    private void showOutages(String chosenHost,List<PingState.Outage> items,long downTime,long errors) {
        if(outageCards==null)return;
        outageCards.removeAllViews();
        List<OutageStore.Entry> history=new java.util.ArrayList<>();
        for(OutageStore.Entry e:outageStore.recent(200))
            if(chosenHost.equals(e.host))history.add(e);
        ResultCards.outageSummary(this,outageCards,history.size(),duration(downTime),errors);
        LinearLayout overview=(LinearLayout)outageCards.getChildAt(0);
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
            if(e.active() && PingState.forSlot(selectedSlot).snapshot().running)
                measured=PingState.forSlot(selectedSlot).snapshot().downMs;
            ResultCards.outage(this,outageCards,"الانقطاع رقم "+(i+1),
                    e.host,date.format(new Date(e.beganAt)),duration(measured),state);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        // Returning from a nested tools screen doesn't recreate this Activity.
        // Check SharedPreferences again before showing the previous palette.
        if(ThemePalette.synchronizeOnResume(this,
                appliedAppearanceMode,appliedAppearanceDark))return;
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
