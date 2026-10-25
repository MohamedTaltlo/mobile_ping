package online.mtradius.mtping;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
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
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

/** Separate service technician page. Network scans run off the UI thread. */
public final class ToolsActivity extends Activity {
    private int BG,CARD,BORDER,WHITE,MUTED,GREEN,RED;
    private ThemePalette palette;
    private TextView localText, gatewayText, publicText, traceLog, scanLog, ubntLog;
    private LinearLayout wifiDisplay,wifiAdviceResults;
    private TextView wifiStatus;
    private static final int WIFI_LOCATION_REQUEST = 231;
    private final Handler wifiHandler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver wifiReceiver;
    private Runnable wifiFallback;
    private int wifiScanGeneration = 0;
    private LinearLayout lanResults, ubntResults, traceResults;
    private EditText traceHost;
    private volatile boolean closed;
    private volatile int generation;
    private NetworkTools.Connection current;
    private final Map<String,LinearLayout> ubntRows=new LinkedHashMap<>();
    private final Map<String,LinearLayout> scanRows=new LinkedHashMap<>();
    private final Map<String,LanScanner.Device> scannedDevices=new LinkedHashMap<>();
    private volatile Thread scanThread;
    private volatile Thread portsThread;
    private volatile PortScanner.Stop portStop;
    private final AtomicInteger scanGeneration=new AtomicInteger();
    private volatile boolean scanStopping;
    private Sidebar sidebar;

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
        t.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        t.setGravity(Gravity.RIGHT);
        t.setLineSpacing(dp(3),1.09f);
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
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);
        b.setTextColor(background==palette.accentSoft ? GREEN : WHITE);
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
        try{startActivity(browser);}catch(Exception e){android.widget.Toast.makeText(this,"تعذّر فتح المتصفح",android.widget.Toast.LENGTH_SHORT).show();}
    }
    @Override public void onCreate(Bundle saved){
        ThemePalette.prepareActivity(this);
        super.onCreate(saved);
        palette=ThemePalette.of(this);
        BG=palette.bg;CARD=palette.card;BORDER=palette.border;
        WHITE=palette.primary;MUTED=palette.muted;GREEN=palette.accent;
        RED=palette.danger;
        ThemePalette.applyWindow(this,palette);
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
        String page=getIntent().getStringExtra("page");
        if(page==null || !java.util.Arrays.asList("network","scanner","trace","ubnt","diagnostics").contains(page))
            page="network";
        sidebar=Sidebar.attach(this,scroll,page);
        LinearLayout root=col();root.setPadding(dp(18),dp(16),dp(18),dp(24));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);scroll.addView(root);

        LinearLayout header=row();
        header.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        Button menu=button("☰",palette.accentSoft);
        menu.setTextColor(GREEN);menu.setTextSize(21);
        header.addView(menu,new LinearLayout.LayoutParams(dp(54),dp(47)));
        TextView logo=text("MT_TOOLS",24,WHITE,true);
        logo.setTextDirection(View.TEXT_DIRECTION_LTR);
        logo.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams logoLayout=new LinearLayout.LayoutParams(0,-2,1);
        logoLayout.rightMargin=dp(11);
        header.addView(logo,logoLayout);
        menu.setOnClickListener(v->sidebar.open());
        root.addView(header);
        ThemeSelector.attach(this,root);
        final String section=page;
        if(section.equals("network")) {
        LinearLayout connection=card(root,"عناوين الشبكة والراوتر");
        localText=text("عنوان الهاتف: —",14,WHITE,false);
        gatewayText=text("البوابة Gateway: —",14,GREEN,true);
        publicText=text("عنوان الإنترنت الخارجي: —",14,WHITE,false);
        connection.addView(localText,margins(-1,-2,2,8));
        connection.addView(gatewayText,margins(-1,-2,0,8));
        gatewayText.setPadding(dp(9),dp(12),dp(9),dp(12));
        gatewayText.setBackground(surface(palette.accentSoft,BORDER,10));
        gatewayText.setOnClickListener(v->{if(current!=null && !current.gateway.isEmpty())openRouter(current.gateway);});

        connection.addView(publicText,margins(-1,-2,0,12));
        Button refresh=button("تحديث عناوين الشبكة",palette.accentSoft);
        connection.addView(refresh,new LinearLayout.LayoutParams(-1,dp(48)));
        refresh.setOnClickListener(v->{refreshConnection();refreshWifi();});
        refreshConnection();

        View wifiDivider=new View(this);
        wifiDivider.setBackgroundColor(BORDER);
        connection.addView(wifiDivider,margins(-1,dp(1),18,17));
        connection.addView(text("التردد والقناة الحالية",16,WHITE,true),margins(-1,-2,0,11));
        wifiDisplay=col();
        connection.addView(wifiDisplay,margins(-1,-2,0,12));
        Button wifiChannelButton=button("تحليل القنوات المحيطة",palette.accentSoft);
        connection.addView(wifiChannelButton,new LinearLayout.LayoutParams(-1,dp(48)));
        wifiStatus=text("اضغط تحليل القنوات لعرض النتيجة.",12,MUTED,false);
        connection.addView(wifiStatus,margins(-1,-2,10,0));
        wifiAdviceResults=col();
        connection.addView(wifiAdviceResults,margins(-1,-2,10,0));
        wifiChannelButton.setOnClickListener(v->{
            // Always obtain location permission for a real AP survey on both
            // 2.4 and 5 GHz. The 5 GHz channel recommendation remains fixed.
            if(Build.VERSION.SDK_INT>=23 &&
                checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)!=
                        android.content.pm.PackageManager.PERMISSION_GRANTED){
                requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION},
                        WIFI_LOCATION_REQUEST);
            } else analyzeWifi();
        });
        refreshWifi();

        }
        if(section.equals("trace")) {
        LinearLayout tracer=card(root,"Traceroute • تتبّع المسار");

        traceHost=new EditText(this);
        traceHost.setSingleLine(true);traceHost.setText("8.8.8.8");
        traceHost.setTextColor(WHITE);traceHost.setTextSize(16);
        traceHost.setBackground(surface(BG,BORDER,12));
        traceHost.setPadding(dp(12),dp(10),dp(12),dp(10));
        traceHost.setTextDirection(View.TEXT_DIRECTION_LTR);
        traceHost.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        tracer.addView(traceHost,margins(-1,dp(48),8,8));
        Button route=button("ابدأ تتبّع المسار",palette.accentSoft);
        tracer.addView(route,new LinearLayout.LayoutParams(-1,dp(48)));
        traceResults=col();
        tracer.addView(traceResults,margins(-1,-2,12,0));
        route.setOnClickListener(v->{
            String host=traceHost.getText().toString().trim();
            if(!PingService.validTarget(host)){traceHost.setError("أدخل عنواناً صالحاً");return;}
            final int ticket=++generation;
            route.setEnabled(false);traceResults.removeAllViews();
            new Thread(()->{
                try{
                    NetworkTools.traceroute(host,line->{if(ticket==generation)post(()->ResultCards.trace(this,traceResults,line));});
                }catch(Exception e){post(()->ResultCards.trace(this,traceResults,"فشل التتبع: "+e.getMessage()));}
                post(()->route.setEnabled(true));
            },"MT_TOOLS-trace").start();
        });

        }
        if(section.equals("scanner")) {
        LinearLayout scan=card(root,"فحص الأجهزة المحلية");


        Button scanButton=button("بدء كشف الأجهزة",palette.accentSoft);
        scan.addView(scanButton,margins(-1,dp(50),12,0));
        ProgressBar scanProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        scanProgress.setMax(100);
        scanProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));
        scanProgress.setVisibility(View.GONE);
        scan.addView(scanProgress,margins(-1,dp(7),11,2));
        scanLog=text("جاهز للفحص",13,MUTED,false);
        scan.addView(scanLog,margins(-1,-2,8,10));
        lanResults=col(); scan.addView(lanResults);
        scanButton.setOnClickListener(v->{
            Thread existing=scanThread;
            if(existing!=null && existing.isAlive()){
                scanStopping=true;
                scanGeneration.incrementAndGet();
                existing.interrupt();
                scanButton.setEnabled(false);
                scanButton.setText("جار إيقاف الفحص...");
                scanLog.setText("تم طلب إيقاف الفحص، وستبقى النتائج التي ظهرت.");
                return;
            }
            final int ticket=scanGeneration.incrementAndGet();
            scanStopping=false;
            final NetworkTools.Connection net=NetworkTools.connection(this);
            scanButton.setText("■ إيقاف الفحص");
            scanButton.setBackground(surface(palette.stop,BORDER,12));
            scanProgress.setVisibility(View.VISIBLE);
            scanProgress.setProgress(0);
            scanLog.setText("جار التحضير للفحص...");
            lanResults.removeAllViews();
            scanRows.clear();scannedDevices.clear();
            Thread worker=new Thread(()->{
                final Thread workerSelf=Thread.currentThread();
                boolean interrupted=false;
                try{
                    LanScanner.scan(this,net,new LanScanner.Update(){
                        @Override public void device(LanScanner.Device result){
                            if(ticket!=scanGeneration.get() || Thread.currentThread().isInterrupted())return;
                            post(()->{
                                if(ticket!=scanGeneration.get())return;
                                LanScanner.Device merged=LanScanner.Device.merge(
                                        scannedDevices.get(result.ip),result);
                                scannedDevices.put(result.ip,merged);
                                LinearLayout item=scanRows.get(result.ip);
                                if(item==null){
                                    item=ResultCards.newCard(ToolsActivity.this,lanResults);
                                    scanRows.put(result.ip,item);
                                }
                                ResultCards.lan(ToolsActivity.this,item,merged,
                                        merged.self?null:()->openRouter(merged.ip));
                            });
                        }
                        @Override public void progress(int done,int total,int detected){
                            if(ticket!=scanGeneration.get())return;
                            post(()->{
                                if(ticket!=scanGeneration.get())return;
                                int pct=total>0?Math.min(100,Math.round(done*100f/total)):100;
                                scanProgress.setProgress(pct);
                                scanLog.setText(String.format(Locale.US,
                                    "جار الفحص: %d / %d  •  %d%%  •  الأجهزة المستجيبة: %d",
                                    done,total,pct,detected));
                            });
                        }
                        @Override public void info(String line){
                            if(ticket==scanGeneration.get())log(scanLog,line);
                        }
                    });
                }catch(InterruptedException ignored){interrupted=true;}
                catch(Exception e){
                    if(ticket==scanGeneration.get())log(scanLog,"تعذّر إكمال الفحص: "+e.getMessage());
                }finally{
                    final boolean didStop=interrupted||scanStopping||ticket!=scanGeneration.get();
                    post(()->{
                        if(scanThread==workerSelf)scanThread=null;
                        scanStopping=false;
                        scanButton.setText("إعادة فحص الأجهزة");
                        scanButton.setBackground(surface(palette.accentSoft,BORDER,12));
                        scanButton.setEnabled(true);
                        if(didStop)scanLog.setText("تم إيقاف الفحص • الأجهزة المعروضة: "+scanRows.size());
                        else{
                            scanProgress.setProgress(100);
                            scanLog.append("\nاكتمل الفحص • الأجهزة المعروضة: "+scanRows.size());
                        }
                    });
                }
            },"MT_TOOLS-LAN-scan");
            scanThread=worker;worker.start();
        });
        }
        if(section.equals("ubnt")) {
        LinearLayout ubnt=card(root,"اكتشاف أجهزة Ubiquiti • UBNT");

        Button find=button("بدء UBNT Discovery",palette.accentSoft);
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
                        LinearLayout tile=ubntRows.get(key);
                        if(tile==null){
                            tile=ResultCards.newCard(this,ubntResults);
                            ubntRows.put(key,tile);
                        }
                        ResultCards.ubnt(this,tile,d,()->openRouter(d.address));
                    }),line->log(ubntLog,line));
                }catch(Exception e){log(ubntLog,"فشل اكتشاف UBNT: "+e.getMessage());}
                post(()->find.setEnabled(true));
            },"MT_TOOLS-UBNT").start();
        });
        }
        if(section.equals("diagnostics")){
            LinearLayout dns=card(root,"DNS • تحليل اسم الموقع");
            EditText dnsTarget=new EditText(this);
            dnsTarget.setSingleLine(true);dnsTarget.setText("www.google.com");
            dnsTarget.setTextColor(WHITE);dnsTarget.setHintTextColor(MUTED);
            dnsTarget.setBackground(surface(BG,BORDER,12));
            dnsTarget.setPadding(dp(12),dp(10),dp(12),dp(10));
            dnsTarget.setTextDirection(View.TEXT_DIRECTION_LTR);
            dns.addView(dnsTarget,margins(-1,dp(48),0,8));
            Button dnsButton=button("اختبار DNS",palette.accentSoft);
            dns.addView(dnsButton,new LinearLayout.LayoutParams(-1,dp(49)));
            TextView dnsStatus=text("جاهز لتحليل DNS",12,MUTED,false);
            dns.addView(dnsStatus,margins(-1,-2,10,6));
            LinearLayout dnsResults=col();
            dns.addView(dnsResults);
            dnsButton.setOnClickListener(v->{
                String domain=dnsTarget.getText().toString().trim();
                if(!PingService.validTarget(domain)){dnsTarget.setError("عنوان غير صحيح");return;}
                dnsButton.setEnabled(false);dnsStatus.setText("جار تحليل DNS...");dnsResults.removeAllViews();
                new Thread(()->{
                    long started=android.os.SystemClock.elapsedRealtime();
                    try{
                        InetAddress[] ips=InetAddress.getAllByName(domain);
                        String[] addresses=new String[ips.length];
                        for(int i=0;i<ips.length;i++)addresses[i]=ips[i].getHostAddress();
                        long elapsed=android.os.SystemClock.elapsedRealtime()-started;
                        post(()->{
                            dnsStatus.setText("اكتمل تحليل DNS");
                            ResultCards.dns(this,dnsResults,domain,addresses,elapsed);
                        });
                    }catch(Exception e){
                        post(()->{
                            dnsStatus.setText("تعذّر تحليل DNS");
                            LinearLayout card=ResultCards.newCard(this,dnsResults);
                            ResultCards.title(this,card,"تعذّر معرفة العنوان");
                            ResultCards.note(this,card,e.getMessage(),RED);
                        });
                    }
                    post(()->dnsButton.setEnabled(true));
                },"MT_TOOLS-DNS").start();
            });

            LinearLayout ports=card(root,"فحص المنافذ المفتوحة");
    
            EditText portHost=new EditText(this);portHost.setSingleLine(true);portHost.setText("192.168.1.1");
            portHost.setTextColor(WHITE);portHost.setBackground(surface(BG,BORDER,12));
            portHost.setTextDirection(View.TEXT_DIRECTION_LTR);
            portHost.setPadding(dp(12),dp(10),dp(12),dp(10));
            ports.addView(portHost,margins(-1,dp(48),9,9));
            Button check=button("فحص جميع منافذ TCP",palette.accentSoft);
            ports.addView(check,new LinearLayout.LayoutParams(-1,dp(53)));
            ProgressBar portsProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
            portsProgress.setMax(65535);
            portsProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));
            portsProgress.setVisibility(View.GONE);
            ports.addView(portsProgress,margins(-1,dp(7),10,8));
            TextView portSummary=text("",12,MUTED,false);
            ports.addView(portSummary);
            LinearLayout portResults=col();
            ports.addView(portResults,margins(-1,-2,10,0));
            check.setOnClickListener(v->{
                Thread existing=portsThread;
                if(existing!=null && existing.isAlive()){
                    if(portStop!=null)portStop.cancel();
                    existing.interrupt();
                    check.setEnabled(false);
                    check.setText("جار إيقاف فحص المنافذ...");
                    return;
                }
                String host=portHost.getText().toString().trim();
                if(!PingService.validTarget(host)){portHost.setError("عنوان غير صحيح");return;}
                final PortScanner.Stop stop=new PortScanner.Stop();
                portStop=stop;
                portResults.removeAllViews();
                portSummary.setText("جار تحضير فحص المنافذ...");
                portsProgress.setProgress(0);
                portsProgress.setVisibility(View.VISIBLE);
                check.setText("■ إيقاف فحص المنافذ");
                check.setBackground(surface(palette.stop,BORDER,12));
                Thread worker=new Thread(()->{
                    final Thread self=Thread.currentThread();
                    try{
                        PortScanner.scan(this,host,stop,new PortScanner.Listener(){
                            @Override public void progress(int done,int total,int open){
                                post(()->{
                                    if(portStop!=stop || stop.isCancelled())return;
                                    portsProgress.setProgress(done);
                                    portSummary.setText(String.format(Locale.US,
                                        "تم اختبار %d / %d  •  %d%%  •  المفتوحة: %d",
                                        done,total,(int)(done*100L/total),open));
                                });
                            }
                            @Override public void found(int port,String service){
                                post(()->{
                                    if(portStop==stop && !stop.isCancelled())
                                        ResultCards.port(ToolsActivity.this,portResults,port,service);
                                });
                            }
                            @Override public void message(String line){
                                if(!stop.isCancelled())post(()->portSummary.setText(line));
                            }
                        });
                    }catch(InterruptedException ignored) { }
                    finally{
                        final boolean wasStopped=stop.isCancelled();
                        post(()->{
                            if(portsThread==self)portsThread=null;
                            check.setEnabled(true);
                            check.setText("إعادة فحص المنافذ");
                            check.setBackground(surface(palette.accentSoft,BORDER,12));
                            if(wasStopped)portSummary.append("\nتوقف الفحص قبل اكتماله.");
                            else portsProgress.setProgress(65535);
                        });
                    }
                },"MT_TOOLS-All-TCP");
                portsThread=worker;worker.start();
            });
        }
        AppFooter.add(this,root);
    }

    private void refreshConnection(){
        current=NetworkTools.connection(this);
        String cidr=current.localIp.isEmpty()?"غير متاح":current.localIp+
                (current.prefix>0?"/"+current.prefix:"");
        localText.setText("نوع الاتصال: "+current.transport+
                "\nعنوان الهاتف: \u2066"+cidr+"\u2069");
        gatewayText.setText("البوابة: "+(current.gateway.isEmpty()?"غير متاح":"\u2066"+current.gateway+"\u2069"));
        gatewayText.setEnabled(!current.gateway.isEmpty());
        publicText.setText("الآيبي الخارجي: جار الاستعلام...");
        new Thread(()->{
            String data;
            try{data=NetworkTools.getPublicIp();}catch(Exception e){data="غير متاح • تحقق من الإنترنت";}
            String response=data;
            post(()->publicText.setText("الآيبي الخارجي: "+(response.contains(".")?"\u2066"+response+"\u2069":response)));
        },"MT_TOOLS-Public-IP").start();
    }
    private void refreshWifi(){
        if(wifiDisplay==null)return;
        WifiAnalyzer.Current now=WifiAnalyzer.current(this);
        ResultCards.wifi(this,wifiDisplay,now);
    }

    private boolean isLocationServiceEnabled(){
        LocationManager lm=(LocationManager)getSystemService(Context.LOCATION_SERVICE);
        if(lm==null)return false;
        try{
            if(Build.VERSION.SDK_INT>=28)return lm.isLocationEnabled();
            return lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        }catch(Exception ignored){return false;}
    }

    private void finishWifiScan(int generation,boolean fresh){
        if(generation!=wifiScanGeneration || closed || wifiAdviceResults==null)return;
        releaseWifiReceiver();
        refreshWifi();
        WifiAnalyzer.Current now=WifiAnalyzer.current(this);
        WifiAnalyzer.Advice advice=WifiAnalyzer.recommend(this,now);
        wifiStatus.setText("");
        ResultCards.wifiAdvice(this,wifiAdviceResults,advice.label);
    }

    private void releaseWifiReceiver(){
        if(wifiReceiver!=null){
            try{unregisterReceiver(wifiReceiver);}catch(Exception ignored){}
            wifiReceiver=null;
        }
        if(wifiFallback!=null){
            wifiHandler.removeCallbacks(wifiFallback);
            wifiFallback=null;
        }
    }

    private void analyzeWifi(){
        if(wifiStatus==null)return;
        refreshWifi();
        if(!isLocationServiceEnabled()){
            wifiStatus.setText("لتعداد الشبكات القريبة، يرجى تشغيل خدمات الموقع ثم إعادة الفحص.");
            return;
        }
        final WifiManager manager=(WifiManager)getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if(manager==null || !manager.isWifiEnabled()){
            wifiStatus.setText("شبكة Wi-Fi غير مفعّلة.");
            return;
        }
        releaseWifiReceiver();
        final int request=++wifiScanGeneration;
        wifiStatus.setText("جار فحص شبكات Wi-Fi القريبة...");
        wifiReceiver=new BroadcastReceiver(){
            @Override public void onReceive(Context context,Intent intent){
                if(!WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction()))return;
                finishWifiScan(request,intent.getBooleanExtra(
                        WifiManager.EXTRA_RESULTS_UPDATED,false));
            }
        };
        try{
            IntentFilter filter=new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            if(Build.VERSION.SDK_INT>=33)
                registerReceiver(wifiReceiver,filter,Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(wifiReceiver,filter);
        }catch(Exception ignored){
            wifiReceiver=null;
        }
        boolean started=false;
        try{started=manager.startScan();}catch(Exception ignored){}
        if(!started){
            // Android throttles scans. Read the latest cached results if present,
            // but never label an unavailable result as zero detected networks.
            finishWifiScan(request,false);
        }else{
            wifiFallback=()->finishWifiScan(request,false);
            wifiHandler.postDelayed(wifiFallback,6500);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode==WIFI_LOCATION_REQUEST){
            if(results.length>0 && results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED){
                analyzeWifi();
            } else if(wifiStatus!=null) {
                wifiStatus.setText("لعرض اسم Wi-Fi وفحص القنوات يلزم السماح بإذن الموقع وتشغيل خدمة الموقع.");
                refreshWifi();
            }
        }
    }

    @Override public void onBackPressed(){
        if(sidebar!=null && sidebar.isOpen()){sidebar.close();return;}
        super.onBackPressed();
    }
    @Override protected void onDestroy(){
        releaseWifiReceiver();
        closed=true;generation++;
        scanGeneration.incrementAndGet();
        Thread scanning=scanThread;if(scanning!=null)scanning.interrupt();
        if(portStop!=null)portStop.cancel();
        Thread ports=portsThread;if(ports!=null)ports.interrupt();
        super.onDestroy();
    }
}
