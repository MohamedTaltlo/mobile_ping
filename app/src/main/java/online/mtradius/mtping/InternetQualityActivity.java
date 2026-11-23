package online.mtradius.mtping;

import android.app.Activity;
import android.content.Intent;
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

/** Dedicated RTL internet-quality page using the shared MT_TOOLS palette/drawer. */
public final class InternetQualityActivity extends Activity {
    private ThemePalette theme;
    private String appearance;
    private boolean dark;
    private Sidebar sidebar;
    private TextView score,verdict,progress,diagnosis;
    private TextView[] metrics;
    private Button quick,full,stop;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private volatile InternetQualityEngine engine;
    private volatile Thread worker;
    private int runId;
    private boolean destroyed;
    private int dp(float x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private GradientDrawable surface(int fill,int stroke,int r){
        GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(r));
        if(stroke!=0)d.setStroke(dp(1),stroke);return d;
    }
    private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(0);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView text(String s,int size,int color,boolean bold){
        TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        v.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        v.setTextDirection(View.TEXT_DIRECTION_RTL);return v;
    }
    private LinearLayout.LayoutParams margin(int w,int h,int top){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);p.topMargin=dp(top);return p;
    }
    private LinearLayout panel(LinearLayout parent,String title){
        LinearLayout box=col();box.setPadding(dp(15),dp(14),dp(15),dp(14));
        box.setBackground(surface(theme.card,theme.border,17));
        if(title!=null)box.addView(text(title,16,theme.primary,true),margin(-1,-2,0));
        parent.addView(box,margin(-1,-2,12));return box;
    }
    private Button button(String title,int fill,int fg){
        Button b=new Button(this);b.setAllCaps(false);b.setText(title);b.setTextSize(13);
        b.setTextColor(fg);b.setBackground(surface(fill,theme.border,12));
        return b;
    }
    @Override public void onCreate(Bundle b){
        ThemePalette.prepareActivity(this);super.onCreate(b);
        theme=ThemePalette.of(this);appearance=ThemePalette.getMode(this);dark=theme.dark;
        ThemePalette.applyWindow(this,theme);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(theme.bg);
        scroll.setFillViewport(true);
        if(Build.VERSION.SDK_INT>=35){
            scroll.setClipToPadding(true);
            scroll.setOnApplyWindowInsetsListener((v,insets)->{
                android.graphics.Insets a=insets.getInsets(WindowInsets.Type.statusBars()|WindowInsets.Type.displayCutout());
                android.graphics.Insets z=insets.getInsets(WindowInsets.Type.navigationBars());
                v.setPadding(0,a.top,0,z.bottom);return insets;
            });
        }
        sidebar=Sidebar.attach(this,scroll,"quality");
        LinearLayout root=col();root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(17),dp(16),dp(17),dp(25));scroll.addView(root);
        LinearLayout header=row();header.setPadding(dp(8),dp(7),dp(8),dp(7));
        header.setBackground(surface(theme.card,theme.border,18));
        Button menu=button("☰",theme.accentSoft,theme.accent);
        menu.setTextSize(22);
        header.addView(menu,new LinearLayout.LayoutParams(dp(53),dp(48)));
        menu.setOnClickListener(v->sidebar.open());
        TextView logo=text("MT_TOOLS",22,theme.primary,true);
        logo.setTextDirection(View.TEXT_DIRECTION_LTR);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.rightMargin=dp(10);
        header.addView(logo,lp);
        ThemeSelector.attachIcon(this,header);
        root.addView(header);
        root.addView(text("تقييم أداء الإنترنت",20,theme.primary,true),margin(-1,-2,18));
        root.addView(text("تشخيص جودة الاتصال ومشاكل الاستجابة وDNS وسرعة النقل",12,theme.muted,false),margin(-1,-2,5));

        LinearLayout hero=panel(root,null);
        score=text("— / 100",40,theme.accent,true);score.setGravity(Gravity.CENTER);
        score.setTextDirection(View.TEXT_DIRECTION_LTR);hero.addView(score);
        verdict=text("لم يبدأ الفحص",17,theme.primary,true);verdict.setGravity(Gravity.CENTER);
        hero.addView(verdict,margin(-1,-2,3));
        progress=text("اختر نوع الفحص",12,theme.muted,false);progress.setGravity(Gravity.CENTER);
        hero.addView(progress,margin(-1,-2,6));

        LinearLayout actions=row();actions.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        quick=button("فحص سريع",theme.accent,theme.onAccent);
        full=button("فحص شامل",theme.accentSoft,theme.accent);
        stop=button("إيقاف",theme.card,theme.danger);
        LinearLayout.LayoutParams a=new LinearLayout.LayoutParams(0,dp(50),1);
        a.leftMargin=dp(5);actions.addView(quick,a);
        LinearLayout.LayoutParams b2=new LinearLayout.LayoutParams(0,dp(50),1);
        b2.leftMargin=dp(5);actions.addView(full,b2);
        actions.addView(stop,new LinearLayout.LayoutParams(0,dp(50),.75f));
        root.addView(actions,margin(-1,-2,10));
        quick.setOnClickListener(v->start(false));
        full.setOnClickListener(v->start(true));
        stop.setOnClickListener(v->cancel());
        stop.setEnabled(false);
        root.addView(text("السريع: Ping وDNS وHTTPS بدون اختبار سرعة كبير. الشامل: يضيف تنزيل نحو 3 MB ورفع نحو 1 MB، وقد تزيد حركة الاتصال قليلاً.",11,theme.muted,false),margin(-1,-2,9));

        LinearLayout measurements=panel(root,"القياسات الفعلية");
        String[] names={"Google Ping (8.8.8.8)","Cloudflare Ping (1.1.1.1)","فقد الحزم ICMP","التذبذب Jitter","DNS Google (8.8.8.8)","DNS Cloudflare (1.1.1.1)","HTTPS Google","HTTPS Cloudflare","تنزيل Cloudflare","رفع Cloudflare","نوع الاتصال","البوابة Gateway"};
        metrics=new TextView[names.length];
        for(int i=0;i<names.length;i++){
            LinearLayout line=row();
            line.setPadding(0,dp(10),0,dp(10));
            TextView label=text(names[i],12,theme.muted,false);
            line.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            TextView value=text("—",13,theme.primary,true);
            value.setTextDirection(View.TEXT_DIRECTION_LTR);value.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);
            line.addView(value);
            metrics[i]=value;measurements.addView(line);
            if(i!=names.length-1){
                View separator=new View(this);separator.setBackgroundColor(theme.border);
                measurements.addView(separator,new LinearLayout.LayoutParams(-1,dp(1)));
            }
        }
        LinearLayout advice=panel(root,"التشخيص والتوصيات");
        diagnosis=text("ابدأ الفحص حتى تظهر حالة الإنترنت وأي مؤشرات للمشاكل.",13,theme.primary,false);
        diagnosis.setLineSpacing(dp(5),1.1f);advice.addView(diagnosis,margin(-1,-2,10));
        root.addView(text("ملاحظة: نتائج ICMP تتأثر بحجب الردود. اختبار سرعة Cloudflare تقريبي لخادم واحد، ولا يمثل بالضرورة السرعة القصوى لاشتراكك. لا يتم قياس Bufferbloat في هذه النسخة.",11,theme.muted,false),margin(-1,-2,13));
        AppFooter.add(this,root);
    }
    private static String ms(double n){return n<0?"غير متاح":String.format(Locale.US,"%.1f ms",n);}
    private static String mbps(double n){return n<0?"لم يُقَس":String.format(Locale.US,"%.2f Mbps",n);}
    private void display(InternetQualityEngine.Report r,String phase){
        if(destroyed)return;
        progress.setText(phase);verdict.setText(r.verdict);
        score.setText(r.score<0?"— / 100":r.score+" / 100");
        score.setTextColor(r.score<0?theme.accent:r.score>=75?theme.accent:
                r.score>=55?theme.warning:theme.danger);
        metrics[0].setText(ms(r.googleMs));
        metrics[1].setText(ms(r.cloudflareMs));
        metrics[2].setText(r.lossPercent<0?"غير متاح":String.format(Locale.US,"%.1f%%",r.lossPercent));
        metrics[3].setText(ms(r.jitterMs));
        metrics[4].setText(ms(r.dnsGoogleMs));
        metrics[5].setText(ms(r.dnsCloudflareMs));
        metrics[6].setText(ms(r.httpsGoogleMs));
        metrics[7].setText(ms(r.httpsCloudflareMs));
        metrics[8].setText(mbps(r.downloadMbps));
        metrics[9].setText(mbps(r.uploadMbps));
        metrics[10].setText(r.transport);
        metrics[11].setText(r.gateway);
        diagnosis.setText(r.diagnosis);
    }
    private void start(boolean complete){
        cancel();
        final int ticket=++runId;
        final InternetQualityEngine run=new InternetQualityEngine();
        engine=run;
        quick.setEnabled(false);full.setEnabled(false);stop.setEnabled(true);
        InternetQualityEngine.Report blank=new InternetQualityEngine.Report();
        display(blank,"جارٍ تجهيز الفحص...");
        worker=new Thread(()->{
            try{
                InternetQualityEngine.Report result=run.run(getApplicationContext(),complete,
                        (step,partial)->ui.post(()->{
                            if(!destroyed&&ticket==runId)display(partial,"جارٍ "+step);
                        }));
                ui.post(()->{if(!destroyed&&ticket==runId){
                    display(result,"اكتمل الفحص");finishRun();
                }});
            }catch(InterruptedException ignored){
                Thread.currentThread().interrupt();
            }catch(Exception ex){
                ui.post(()->{if(!destroyed&&ticket==runId){
                    progress.setText("تعذّر إكمال الفحص");
                    diagnosis.setText("حدث خطأ في الفحص: "+ex.getClass().getSimpleName());
                    finishRun();
                }});
            }
        },"internet-quality");
        worker.start();
    }
    private void finishRun(){
        quick.setEnabled(true);full.setEnabled(true);stop.setEnabled(false);
        engine=null;worker=null;
    }
    private void cancel(){
        runId++;
        if(engine!=null)engine.cancel();
        if(worker!=null)worker.interrupt();
        engine=null;worker=null;
        if(quick!=null){quick.setEnabled(true);full.setEnabled(true);stop.setEnabled(false);}
        if(progress!=null)progress.setText("تم إيقاف الفحص");
    }
    @Override protected void onResume(){
        super.onResume();
        ThemePalette.synchronizeOnResume(this,appearance,dark);
    }
    @Override protected void onDestroy(){destroyed=true;cancel();super.onDestroy();}
}
