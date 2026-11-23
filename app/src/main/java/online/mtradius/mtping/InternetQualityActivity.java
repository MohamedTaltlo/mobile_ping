package online.mtradius.mtping;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Locale;

/** Independent RTL quality dashboard with the same palette and shared drawer. */
public final class InternetQualityActivity extends Activity {
    private ThemePalette theme;
    private String appearance;
    private boolean dark;
    private Sidebar sidebar;
    private InternetQualityGauge gauge;
    private TextView verdict,phase,confidence,grade,bufferStatus,down,up,diagnosis;
    private TextView[] indicators=new TextView[3];
    private TextView[] metrics;
    private TextView idle,loadedDown,loadedUp,addedDown,addedUp,downP95,upP95,bufferSamples;
    private TextView nearestRtt,nearestMin,nearestName,nearestJitter;
    private TextView mlabDown,mlabUp,cloudDown,cloudUp,mlabName;
    private Button quick,full;
    private boolean measuring;
    private boolean comprehensiveActive;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private volatile InternetQualityEngine engine;
    private volatile Thread worker;
    private int runId;
    private boolean destroyed;
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int fill,int stroke,int rad){
        GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(rad));
        if(stroke!=0)d.setStroke(dp(1),stroke);return d;
    }
    private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(0);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView text(String content,int size,int color,boolean bold){
        TextView v=new TextView(this);v.setText(content);v.setTextSize(size);v.setTextColor(color);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        v.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        v.setTextDirection(View.TEXT_DIRECTION_RTL);return v;
    }
    private LinearLayout.LayoutParams margin(int w,int h,int top){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);p.topMargin=dp(top);return p;
    }
    private LinearLayout panel(LinearLayout parent,String title){
        LinearLayout l=col();l.setPadding(dp(15),dp(15),dp(15),dp(16));
        l.setBackground(shape(theme.card,theme.border,18));
        if(title!=null)l.addView(text(title,16,theme.primary,true));
        parent.addView(l,margin(-1,-2,12));return l;
    }
    private Button button(String label,int fill,int color){
        Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextSize(13);
        b.setTextColor(color);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setBackground(shape(fill,theme.border,12));return b;
    }
    private int qualityColor(int score){
        return score<0?theme.muted:score>=75?theme.accent:score>=55?theme.warning:theme.danger;
    }
    private TextView rowMetric(LinearLayout group,String label){
        LinearLayout line=row();line.setPadding(0,dp(10),0,dp(10));
        TextView name=text(label,12,theme.muted,false);
        line.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        TextView value=text("—",13,theme.primary,true);
        value.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);
        value.setTextDirection(View.TEXT_DIRECTION_LTR);
        line.addView(value);group.addView(line);
        View rule=new View(this);rule.setBackgroundColor(theme.border);
        group.addView(rule,new LinearLayout.LayoutParams(-1,dp(1)));
        return value;
    }
    private TextView mini(LinearLayout container,String name){
        LinearLayout pane=col();
        pane.setPadding(dp(7),dp(11),dp(7),dp(11));
        pane.setGravity(Gravity.CENTER);
        pane.setBackground(shape(theme.panel,theme.border,13));
        TextView t=text("—",23,theme.primary,true);t.setGravity(Gravity.CENTER);
        TextView label=text(name,11,theme.muted,false);label.setGravity(Gravity.CENTER);
        pane.addView(t);pane.addView(label,margin(-1,-2,4));
        LinearLayout.LayoutParams w=new LinearLayout.LayoutParams(0,-2,1);
        w.leftMargin=dp(5);container.addView(pane,w);
        return t;
    }
    private TextView largeNumber(LinearLayout parent,String title){
        LinearLayout tile=col();tile.setPadding(dp(11),dp(13),dp(11),dp(13));
        tile.setBackground(shape(theme.panel,theme.border,14));
        tile.addView(text(title,12,theme.muted,true));
        TextView number=text("—",22,theme.accent,true);
        number.setTextDirection(View.TEXT_DIRECTION_LTR);
        tile.addView(number,margin(-1,-2,6));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);
        p.leftMargin=dp(6);parent.addView(tile,p);return number;
    }

    @Override public void onCreate(Bundle saved){
        ThemePalette.prepareActivity(this);super.onCreate(saved);
        theme=ThemePalette.of(this);
        appearance=ThemePalette.getMode(this);dark=theme.dark;
        ThemePalette.applyWindow(this,theme);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        ScrollView scroll=new ScrollView(this);
        scroll.setBackgroundColor(theme.bg);scroll.setFillViewport(true);
        if(Build.VERSION.SDK_INT>=35){
            scroll.setClipToPadding(true);
            scroll.setOnApplyWindowInsetsListener((v,insets)->{
                android.graphics.Insets top=insets.getInsets(
                        WindowInsets.Type.statusBars()|WindowInsets.Type.displayCutout());
                android.graphics.Insets bottom=insets.getInsets(WindowInsets.Type.navigationBars());
                v.setPadding(0,top.top,0,bottom.bottom);return insets;
            });
        }
        sidebar=Sidebar.attach(this,scroll,"quality");
        LinearLayout root=col();
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(17),dp(16),dp(17),dp(24));
        scroll.addView(root);

        LinearLayout header=row();header.setMinimumHeight(dp(65));
        header.setPadding(dp(8),dp(7),dp(8),dp(7));
        header.setBackground(shape(theme.card,theme.border,18));
        Button menu=button("☰",theme.accentSoft,theme.accent);
        menu.setTextSize(21);
        header.addView(menu,new LinearLayout.LayoutParams(dp(54),dp(48)));
        menu.setOnClickListener(v->sidebar.open());
        TextView logo=text("MT_TOOLS",23,theme.primary,true);
        logo.setTextDirection(View.TEXT_DIRECTION_LTR);
        LinearLayout.LayoutParams logoParams=new LinearLayout.LayoutParams(0,-2,1);
        logoParams.rightMargin=dp(10);
        header.addView(logo,logoParams);
        ThemeSelector.attachIcon(this,header);
        root.addView(header);
        root.addView(text("تقييم أداء الإنترنت",20,theme.primary,true),margin(-1,-2,18));
        root.addView(text("قياس السرعة، استقرار الاتصال، والاستجابة تحت الضغط",12,theme.muted,false),margin(-1,-2,5));

        LinearLayout summary=panel(root,null);
        gauge=new InternetQualityGauge(this);
        summary.addView(gauge,new LinearLayout.LayoutParams(-1,dp(184)));
        verdict=text("ابدأ الفحص لمعرفة حالة الإنترنت",17,theme.primary,true);
        verdict.setGravity(Gravity.CENTER);
        summary.addView(verdict,margin(-1,-2,-2));
        phase=text("جاهز للفحص",12,theme.muted,false);
        phase.setGravity(Gravity.CENTER);
        summary.addView(phase,margin(-1,-2,6));
        confidence=text("",11,theme.muted,false);
        confidence.setGravity(Gravity.CENTER);
        summary.addView(confidence,margin(-1,-2,5));

        LinearLayout actions=row();
        actions.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        full=button("▶  فحص شامل",theme.accent,theme.onAccent);
        quick=button("فحص سريع",theme.accentSoft,theme.accent);
        LinearLayout.LayoutParams b1=new LinearLayout.LayoutParams(0,dp(49),1);
        b1.leftMargin=dp(5);actions.addView(full,b1);
        LinearLayout.LayoutParams b2=new LinearLayout.LayoutParams(0,dp(49),1);
        b2.leftMargin=dp(5);actions.addView(quick,b2);
        root.addView(actions,margin(-1,-2,8));
        full.setOnClickListener(v->{if(measuring)cancel();else start(true);});
        quick.setOnClickListener(v->{if(measuring)cancel();else start(false);});
        updateButtons(false,false);

        LinearLayout nearest=panel(root,"أقرب مسار إنترنت · TCP");
        nearestRtt=rowMetric(nearest,"زمن الاستجابة");
        nearestMin=rowMetric(nearest,"أقل زمن");
        nearestJitter=rowMetric(nearest,"تذبذب المسار");
        nearestName=rowMetric(nearest,"الخادم المختار");
        LinearLayout fit=panel(root,"مناسب لأي استخدام؟");
        LinearLayout fitLine=row();fitLine.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        indicators[0]=mini(fitLine,"الألعاب");
        indicators[1]=mini(fitLine,"المكالمات");
        indicators[2]=mini(fitLine,"التصفح");
        fit.addView(fitLine,margin(-1,-2,12));

        LinearLayout speeds=panel(root,"سرعة الإنترنت");
        LinearLayout speedTiles=row();speedTiles.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        down=largeNumber(speedTiles,"↓  التنزيل");
        up=largeNumber(speedTiles,"↑  الرفع");
        speeds.addView(speedTiles,margin(-1,-2,13));
        mlabDown=rowMetric(speeds,"M-Lab · تنزيل");
        mlabUp=rowMetric(speeds,"M-Lab · رفع");
        cloudDown=rowMetric(speeds,"Cloudflare · تنزيل");
        cloudUp=rowMetric(speeds,"Cloudflare · رفع");
        mlabName=rowMetric(speeds,"خادم M-Lab");

        LinearLayout buffer=panel(root,"Bufferbloat · الاستجابة أثناء الضغط");
        LinearLayout bufferHead=row();
        grade=text("—",38,theme.accent,true);
        grade.setTextDirection(View.TEXT_DIRECTION_LTR);
        bufferHead.addView(grade,new LinearLayout.LayoutParams(dp(76),dp(55)));
        bufferStatus=text("يُقاس مع الفحص الشامل",13,theme.muted,false);
        bufferHead.addView(bufferStatus,new LinearLayout.LayoutParams(0,-2,1));
        buffer.addView(bufferHead,margin(-1,-2,12));
        idle=rowMetric(buffer,"قبل الضغط");
        loadedDown=rowMetric(buffer,"أثناء التنزيل");
        addedDown=rowMetric(buffer,"زيادة تأخير التنزيل");
        loadedUp=rowMetric(buffer,"أثناء الرفع");
        addedUp=rowMetric(buffer,"زيادة تأخير الرفع");
        downP95=rowMetric(buffer,"P95 أثناء التنزيل");
        upP95=rowMetric(buffer,"P95 أثناء الرفع");
        bufferSamples=rowMetric(buffer,"عينات الضغط · تنزيل / رفع");

        LinearLayout detail=panel(root,"تفاصيل الشبكة");
        String[] titles={
                "Google Ping · 8.8.8.8",
                "Cloudflare Ping · 1.1.1.1",
                "عدم استجابة ICMP Google",
                "عدم استجابة ICMP Cloudflare",
                "تذبذب أقرب مسار TCP",
                "DNS Google",
                "DNS Cloudflare",
                "HTTPS Google",
                "HTTPS Cloudflare",
                "نوع الاتصال",
                "Gateway"};
        metrics=new TextView[titles.length];
        for(int i=0;i<titles.length;i++)metrics[i]=rowMetric(detail,titles[i]);

        LinearLayout advice=panel(root,"التشخيص والتوصيات");
        diagnosis=text("ابدأ الفحص الشامل للحصول على تحليل الاستجابة تحت الضغط.",13,theme.primary,false);
        diagnosis.setLineSpacing(dp(4),1.1f);
        advice.addView(diagnosis,margin(-1,-2,12));
        AppFooter.add(this,root);
    }

    private static String ms(double n){return n<0?"—":String.format(Locale.US,"%.1f ms",n);}
    private static String plus(double n){return n<0?"—":String.format(Locale.US,"+%.1f ms",n);}
    private static String speed(double n){return n<0?"—":String.format(Locale.US,"%.2f Mbps",n);}
    private static String loss(double n){return n<0?"غير مؤكد":String.format(Locale.US,"%.1f%%",n);}
    private static String rating(int n){return n<0?"—":n+"/100";}
    private void show(InternetQualityEngine.Report r,String step){
        if(destroyed)return;
        gauge.setValue(r.score);
        verdict.setText(r.verdict);
        phase.setText(step);
        confidence.setText(r.score<0?"":"اكتمال القياسات: "+r.confidence+"%");
        indicators[0].setText(rating(r.gaming));
        indicators[1].setText(rating(r.calls));
        indicators[2].setText(rating(r.browsing));
        for(int i=0;i<indicators.length;i++){
            int s=i==0?r.gaming:i==1?r.calls:r.browsing;
            indicators[i].setTextColor(qualityColor(s));
        }
        down.setText(speed(r.downloadMbps));
        up.setText(speed(r.uploadMbps));
        mlabDown.setText(speed(r.mlabDownload));
        mlabUp.setText(speed(r.mlabUpload));
        cloudDown.setText(speed(r.cfDownload));
        cloudUp.setText(speed(r.cfUpload));
        mlabName.setText(r.mlabServer);
        nearestRtt.setText(ms(r.nearMs));
        nearestMin.setText(ms(r.nearMin));
        nearestJitter.setText(ms(r.nearJitter));
        nearestName.setText(r.nearHost);
        grade.setText(r.bufferGrade);
        grade.setTextColor("—".equals(r.bufferGrade)?theme.muted:
                "A+".equals(r.bufferGrade)||"A".equals(r.bufferGrade)||"B".equals(r.bufferGrade)?
                        theme.accent:"C".equals(r.bufferGrade)?theme.warning:theme.danger);
        boolean tailSpikes=r.idleMs>=0&&
                ((r.downSamples>=8&&r.downP95>=0&&r.downP95-r.idleMs>250)||
                 (r.upSamples>=8&&r.upP95>=0&&r.upP95-r.idleMs>250));
        bufferStatus.setText("—".equals(r.bufferGrade)?"لم تكتمل عينات الضغط بالاتجاهين":
                tailSpikes?"استجابة متقطعة · قفزات في P95":
                "A+".equals(r.bufferGrade)||"A".equals(r.bufferGrade)?"استجابة ممتازة تحت الحمل":
                "B".equals(r.bufferGrade)?"استجابة جيدة تحت الحمل":
                "C".equals(r.bufferGrade)?"تأخير متوسط تحت الحمل":"ازدحام ملحوظ تحت الحمل");
        idle.setText(ms(r.idleMs));
        loadedDown.setText(ms(r.loadDownMs));
        loadedUp.setText(ms(r.loadUpMs));
        addedDown.setText(plus(r.downDelta));
        addedUp.setText(plus(r.upDelta));
        addedDown.setTextColor(r.downDelta<0?theme.muted:r.downDelta<=30?theme.accent:
                r.downDelta<=60?theme.warning:theme.danger);
        addedUp.setTextColor(r.upDelta<0?theme.muted:r.upDelta<=30?theme.accent:
                r.upDelta<=60?theme.warning:theme.danger);
        downP95.setText(ms(r.downP95));upP95.setText(ms(r.upP95));
        bufferSamples.setText(r.downSamples==0&&r.upSamples==0?"—":
                r.downSamples+" / "+r.upSamples);
        metrics[0].setText(ms(r.googleMs));
        metrics[1].setText(ms(r.cloudflareMs));
        metrics[2].setText(loss(r.googleLoss));
        metrics[3].setText(loss(r.cloudflareLoss));
        metrics[4].setText(ms(r.nearJitter));
        metrics[5].setText(ms(r.dnsGoogleMs));
        metrics[6].setText(ms(r.dnsCloudflareMs));
        metrics[7].setText(ms(r.httpsGoogleMs));
        metrics[8].setText(ms(r.httpsCloudflareMs));
        metrics[9].setText(r.transport);
        metrics[10].setText(r.gateway);
        diagnosis.setText(r.diagnosis);
    }

    /** Only the button for the active mode remains, and it becomes Stop. */
    private void updateButtons(boolean running,boolean comprehensive){
        measuring=running;
        comprehensiveActive=comprehensive;
        if(full==null||quick==null)return;
        full.setVisibility(running&&!comprehensive?View.GONE:View.VISIBLE);
        quick.setVisibility(running&&comprehensive?View.GONE:View.VISIBLE);
        full.setText(running?"■  إيقاف القياس":"▶  فحص شامل");
        quick.setText(running?"■  إيقاف القياس":"فحص سريع");
        full.setBackground(shape(running?theme.danger:theme.accent,theme.border,12));
        full.setTextColor(theme.onAccent);
        quick.setBackground(shape(running?theme.danger:theme.accentSoft,theme.border,12));
        quick.setTextColor(running?theme.onAccent:theme.accent);
    }
    private void start(boolean comprehensive){
        if(measuring)return;
        final int ticket=++runId;
        final InternetQualityEngine job=new InternetQualityEngine();
        engine=job;
        updateButtons(true,comprehensive);
        show(new InternetQualityEngine.Report(),"جارٍ تجهيز الفحص...");
        worker=new Thread(()->{
            try{
                InternetQualityEngine.Report result=job.run(getApplicationContext(),comprehensive,
                        (step,partial)->ui.post(()->{
                            if(!destroyed&&ticket==runId)
                                show(partial,"اكتمل الفحص".equals(step)?step:"جارٍ "+step);
                        }));
                ui.post(()->{
                    if(!destroyed&&ticket==runId){show(result,"اكتمل الفحص");finishRun();}
                });
            }catch(InterruptedException ignored){
                Thread.currentThread().interrupt();
            }catch(Exception ex){
                ui.post(()->{
                    if(!destroyed&&ticket==runId){
                        phase.setText("تعذّر إكمال الفحص");
                        diagnosis.setText("حدث خطأ: "+ex.getClass().getSimpleName());
                        finishRun();
                    }
                });
            }
        },"quality-diagnostics");
        worker.start();
    }
    private void finishRun(){
        engine=null;worker=null;
        updateButtons(false,false);
    }
    private void cancel(){
        if(!measuring&&worker==null)return;
        runId++; // Discard queued results from an interrupted previous test.
        if(engine!=null)engine.cancel();
        if(worker!=null)worker.interrupt();
        engine=null;worker=null;
        updateButtons(false,false);
        if(phase!=null)phase.setText("تم إيقاف الفحص");
    }
    @Override protected void onResume(){
        super.onResume();
        ThemePalette.synchronizeOnResume(this,appearance,dark);
    }
    @Override protected void onDestroy(){destroyed=true;cancel();super.onDestroy();}
}
