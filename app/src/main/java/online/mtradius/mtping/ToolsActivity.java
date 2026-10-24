package online.mtradius.mtping;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Map;

/** Separate service technician page. Network scans run off the UI thread. */
public final class ToolsActivity extends Activity {
    private static final int BG=0xFF07121E, CARD=0xFF101F30, BORDER=0xFF20364B,
            WHITE=0xFFF1F5F9, MUTED=0xFF90A2B8, GREEN=0xFF3EDAB6, RED=0xFFFF6278;
    private TextView localText, gatewayText, publicText, traceLog, scanLog, ubntLog;
    private LinearLayout lanResults, ubntResults;
    private EditText traceHost;
    private volatile boolean closed;
    private volatile int generation;
    private NetworkTools.Connection current;
    private final Map<String,TextView> ubntRows=new LinkedHashMap<>();

    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable surface(int color,int stroke,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));
        if(stroke!=0)d.setStroke(dp(1),stroke);return d;
    }
    private LinearLayout col(){LinearLayout x=new LinearLayout(this);x.setOrientation(1);return x;}
    private LinearLayout row(){LinearLayout x=new LinearLayout(this);x.setOrientation(0);x.setGravity(Gravity.CENTER_VERTICAL);return x;}
    private TextView text(String value,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        return t;
    }
    private LinearLayout.LayoutParams margins(int w,int h,int top,int bottom){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);
        p.topMargin=dp(top);p.bottomMargin=dp(bottom);return p;
    }
    private LinearLayout card(LinearLayout root,String title){
        LinearLayout box=col();box.setPadding(dp(15),dp(15),dp(15),dp(15));
        box.setBackground(surface(CARD,BORDER,16));
        box.addView(text(title,16,WHITE,true),margins(-1,-2,0,12));
        root.addView(box,margins(-1,-2,12,0));
        return box;
    }
    private Button button(String title,int background){
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextColor(WHITE);
        b.setBackground(surface(background,BORDER,12));return b;
    }
    private void post(Runnable r){runOnUiThread(()->{if(!closed)r.run();});}
    private void log(TextView out,String msg){
        post(()->{String before=out.getText().toString();
            if(before.length()>13000)before=before.substring(before.length()-9500);
            out.setText(before+msg+"\n");
        });
    }
    private void openRouter(String ip){
        if(!ip.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))return;
        Intent browser=new Intent(Intent.ACTION_VIEW,Uri.parse("http://"+ip+"/"));
        try{startActivity(browser);}catch(Exception e){log(scanLog,"تعذّر فتح المتصفح: "+e.getMessage());}
    }
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(BG);scroll.setFillViewport(true);
        if(Build.VERSION.SDK_INT>=35){
            scroll.setClipToPadding(false);
            scroll.setOnApplyWindowInsetsListener((v,insets)->{
                android.graphics.Insets t=insets.getInsets(WindowInsets.Type.statusBars()|WindowInsets.Type.displayCutout());
                android.graphics.Insets b=insets.getInsets(WindowInsets.Type.navigationBars());
                v.setPadding(0,t.top,0,b.bottom);return insets;
            });
        }
        setContentView(scroll);
        LinearLayout root=col();root.setPadding(dp(18),dp(16),dp(18),dp(24));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);scroll.addView(root);

        LinearLayout header=row();
        TextView logo=text("MT_TOOLS",24,WHITE,true);header.addView(logo,new LinearLayout.LayoutParams(0,-2,1));
        Button back=button("← بينغ",0xFF173245);header.addView(back,new LinearLayout.LayoutParams(dp(99),dp(43)));
        back.setOnClickListener(v->finish());
        root.addView(header);
        root.addView(text("أدوات الشبكة والصيانة",13,MUTED,false),margins(-1,-2,4,6));

        LinearLayout connection=card(root,"عناوين الشبكة والراوتر");
        localText=text("عنوان الهاتف: —",14,WHITE,false);
        gatewayText=text("البوابة Gateway: —",14,GREEN,true);
        publicText=text("عنوان الإنترنت الخارجي: —",14,WHITE,false);
        connection.addView(localText,margins(-1,-2,2,8));
        connection.addView(gatewayText,margins(-1,-2,0,8));
        gatewayText.setPadding(dp(9),dp(12),dp(9),dp(12));
        gatewayText.setBackground(surface(0xFF173245,BORDER,10));
        gatewayText.setOnClickListener(v->{if(current!=null && !current.gateway.isEmpty())openRouter(current.gateway);});
        connection.addView(text("اضغط على عنوان Gateway لفتح لوحة الراوتر عبر HTTP.",11,MUTED,false),margins(-1,-2,0,12));
        connection.addView(publicText,margins(-1,-2,0,12));
        Button refresh=button("تحديث عناوين الشبكة",0xFF173245);
        connection.addView(refresh,new LinearLayout.LayoutParams(-1,dp(48)));
        refresh.setOnClickListener(v->refreshConnection());
        refreshConnection();

        LinearLayout tracer=card(root,"Traceroute • تتبّع المسار");
        tracer.addView(text("وجهة الفحص (IP أو دومين)",11,MUTED,false));
        traceHost=new EditText(this);
        traceHost.setSingleLine(true);traceHost.setText("8.8.8.8");
        traceHost.setTextColor(WHITE);traceHost.setTextSize(16);
        traceHost.setBackground(surface(BG,BORDER,12));
        traceHost.setPadding(dp(12),dp(10),dp(12),dp(10));
        traceHost.setTextDirection(View.TEXT_DIRECTION_LTR);
        traceHost.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        tracer.addView(traceHost,margins(-1,dp(48),8,8));
        Button route=button("ابدأ تتبّع المسار",0xFF173245);
        tracer.addView(route,new LinearLayout.LayoutParams(-1,dp(48)));
        traceLog=text("ستظهر القفزات التي تستجيب، وقد تحجب بعض الشبكات رسائل ICMP.",12,GREEN,false);
        traceLog.setTypeface(Typeface.MONOSPACE);traceLog.setTextIsSelectable(true);
        tracer.addView(traceLog,margins(-1,-2,12,0));
        route.setOnClickListener(v->{
            String host=traceHost.getText().toString().trim();
            if(!PingService.validTarget(host)){traceHost.setError("أدخل عنواناً صالحاً");return;}
            final int ticket=++generation;
            route.setEnabled(false);traceLog.setText("");
            new Thread(()->{
                try{
                    NetworkTools.traceroute(host,line->{if(ticket==generation)log(traceLog,line);});
                }catch(Exception e){log(traceLog,"فشل التتبع: "+e.getMessage());}
                post(()->route.setEnabled(true));
            },"MT_TOOLS-trace").start();
        });

        LinearLayout scan=card(root,"الأجهزة المتصلة على الشبكة المحلية");
        scan.addView(text("فحص IPv4 على Wi-Fi الحالي (بحد أقصى /24). الأجهزة التي لا تجيب على Ping قد لا تظهر.",11,MUTED,false));
        Button scanButton=button("فحص الأجهزة الآن",0xFF173245);
        scan.addView(scanButton,margins(-1,dp(48),10,0));
        scanLog=text("",12,MUTED,false);scan.addView(scanLog,margins(-1,-2,8,0));
        lanResults=col();scan.addView(lanResults);
        scanButton.setOnClickListener(v->{
            current=NetworkTools.connection(this);
            scanButton.setEnabled(false);scanLog.setText("");lanResults.removeAllViews();
            new Thread(()->{
                try{
                    NetworkTools.scanLan(current,line->log(scanLog,line),(ip,mac)->post(()->{
                        TextView row=text("●  "+ip+"   •   MAC: "+mac+"\nاضغط لفتح http://"+ip,13,GREEN,false);
                        row.setPadding(dp(8),dp(10),dp(8),dp(10));
                        row.setBackground(surface(BG,BORDER,9));
                        lanResults.addView(row,margins(-1,-2,4,0));row.setOnClickListener(z->openRouter(ip));
                    }));
                }catch(Exception e){log(scanLog,"فشل الفحص: "+e.getMessage());}
                post(()->scanButton.setEnabled(true));
            },"MT_TOOLS-LAN-scan").start();
        });

        LinearLayout ubnt=card(root,"اكتشاف أجهزة Ubiquiti • UBNT");
        ubnt.addView(text("اكتشاف أصلي عبر UDP/10001 وليس تخميناً من ARP. الموديل وUptime يظهران فقط عند إرسالهما من الجهاز.",11,MUTED,false));
        Button find=button("بدء UBNT Discovery",0xFF173245);
        ubnt.addView(find,margins(-1,dp(48),10,0));
        ubntLog=text("",12,MUTED,false);ubnt.addView(ubntLog,margins(-1,-2,8,0));
        ubntResults=col();ubnt.addView(ubntResults);
        find.setOnClickListener(v->{
            current=NetworkTools.connection(this);
            find.setEnabled(false);ubntLog.setText("");ubntResults.removeAllViews();ubntRows.clear();
            new Thread(()->{
                try{
                    UbntDiscovery.scan(this,current,d->post(()->{
                        String key=d.mac.isEmpty()?d.address:d.mac;
                        TextView tile=ubntRows.get(key);
                        if(tile==null){
                            tile=text("",12,GREEN,false);
                            tile.setPadding(dp(10),dp(12),dp(10),dp(12));
                            tile.setBackground(surface(BG,BORDER,10));
                            ubntRows.put(key,tile);
                            ubntResults.addView(tile,margins(-1,-2,5,0));
                        }
                        tile.setText(d.describe()+"\nاضغط لفتح واجهة القطعة (HTTP)");
                        tile.setOnClickListener(z->openRouter(d.address));
                    }),line->log(ubntLog,line));
                }catch(Exception e){log(ubntLog,"فشل اكتشاف UBNT: "+e.getMessage());}
                post(()->find.setEnabled(true));
            },"MT_TOOLS-UBNT").start();
        });
        root.addView(text("MT_RADIUS • أدوات صيانة الشبكات",11,MUTED,false),margins(-1,-2,20,0));
    }

    private void refreshConnection(){
        current=NetworkTools.connection(this);
        localText.setText("نوع الاتصال: "+current.transport+"\nعنوان الهاتف: "+
                (current.localIp.isEmpty()?"غير متاح":current.localIp)+
                (current.prefix>0?" /"+current.prefix:""));
        gatewayText.setText("Gateway: "+(current.gateway.isEmpty()?"غير متاح":current.gateway));
        gatewayText.setEnabled(!current.gateway.isEmpty());
        publicText.setText("الآيبي الخارجي: جاري الاستعلام...");
        new Thread(()->{
            String data;
            try{data=NetworkTools.getPublicIp();}catch(Exception e){data="غير متاح • تحقق من الإنترنت";}
            String response=data;
            post(()->publicText.setText("الآيبي الخارجي: "+response));
        },"MT_TOOLS-Public-IP").start();
    }
    @Override protected void onDestroy(){closed=true;generation++;super.onDestroy();}
}
