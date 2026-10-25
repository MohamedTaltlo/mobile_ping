package online.mtradius.mtping;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

/** Reusable RTL result cards with separate headings, labels and LTR values. */
final class ResultCards {
    private ResultCards(){}

    private static int dp(Activity a,float units) {
        return Math.round(units*a.getResources().getDisplayMetrics().density);
    }
    private static GradientDrawable shape(Activity a,int fill,int border,int radius) {
        GradientDrawable d=new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(a,radius));
        d.setStroke(dp(a,1),border);
        return d;
    }
    static LinearLayout newCard(Activity a,LinearLayout parent) {
        ThemePalette t=ThemePalette.of(a);
        LinearLayout card=new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        card.setPadding(dp(a,14),dp(a,13),dp(a,14),dp(a,14));
        card.setBackground(shape(a,t.panel,t.border,14));
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);
        layout.bottomMargin=dp(a,9);
        parent.addView(card,layout);
        return card;
    }
    static TextView line(Activity a,String value,int sp,int color,boolean bold,boolean ltr) {
        TextView tv=new TextView(a);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setIncludeFontPadding(true);
        tv.setLineSpacing(dp(a,3),1.08f);
        tv.setGravity(ltr?Gravity.LEFT:Gravity.RIGHT);
        tv.setLayoutDirection(ltr?View.LAYOUT_DIRECTION_LTR:View.LAYOUT_DIRECTION_RTL);
        tv.setTextDirection(ltr?View.TEXT_DIRECTION_LTR:View.TEXT_DIRECTION_RTL);
        tv.setTextIsSelectable(true);
        if(bold)tv.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return tv;
    }
    static void title(Activity a,LinearLayout card,String heading) {
        ThemePalette t=ThemePalette.of(a);
        TextView tv=line(a,heading,16,t.primary,true,false);
        card.addView(tv,new LinearLayout.LayoutParams(-1,-2));
    }
    static void note(Activity a,LinearLayout card,String value,int color) {
        TextView tv=line(a,value,13,color,false,false);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.topMargin=dp(a,7);
        card.addView(tv,p);
    }
    static void field(Activity a,LinearLayout card,String label,String value,boolean ltr) {
        if(value==null || value.trim().isEmpty())return;
        ThemePalette t=ThemePalette.of(a);
        LinearLayout wrap=new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);
        layout.topMargin=dp(a,10);
        card.addView(wrap,layout);
        TextView heading=line(a,label,11,t.muted,false,false);
        wrap.addView(heading);
        TextView data=line(a,value,14,t.primary,false,ltr);
        LinearLayout.LayoutParams inner=new LinearLayout.LayoutParams(-1,-2);
        inner.topMargin=dp(a,3);
        wrap.addView(data,inner);
    }
    static void action(Activity a,LinearLayout card,String name,Runnable listener) {
        ThemePalette t=ThemePalette.of(a);
        TextView tv=line(a,name,12,t.accent,true,false);
        tv.setPadding(dp(a,12),dp(a,11),dp(a,12),dp(a,11));
        tv.setGravity(Gravity.CENTER);
        tv.setTextIsSelectable(false);
        tv.setBackground(shape(a,t.accentSoft,t.border,10));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.topMargin=dp(a,14);
        card.addView(tv,p);
        if(listener!=null)tv.setOnClickListener(v->listener.run());
    }
    static void ubnt(Activity a,LinearLayout card,UbntDiscovery.Device d,Runnable open) {
        card.removeAllViews();
        ThemePalette t=ThemePalette.of(a);
        title(a,card,!d.model.isEmpty()?d.model:!d.hostname.isEmpty()?d.hostname:"جهاز Ubiquiti");
        field(a,card,"عنوان IP",d.address,true);
        field(a,card,"الماك",d.mac,true);
        field(a,card,"اسم الجهاز",d.hostname,false);
        field(a,card,"إصدار البرنامج",d.firmware,true);
        field(a,card,"اسم الشبكة SSID",d.ssid,true);
        field(a,card,"مدة التشغيل",d.uptime>=0?UbntDiscovery.clock(d.uptime):"غير متاحة",false);
        action(a,card,"فتح واجهة الجهاز في المتصفح",open);
    }
    static void lan(Activity a,LinearLayout card,LanScanner.Device d,Runnable open) {
        card.removeAllViews();
        ThemePalette t=ThemePalette.of(a);
        title(a,card,d.displayName());
        field(a,card,"عنوان IP",d.ip,true);
        if(d.latency>=0)field(a,card,"زمن الاستجابة",
                String.format(Locale.US,"%.2f ms",d.latency),true);
        String kind=d.kind();
        if(!kind.isEmpty() && !kind.equals(d.displayName()))
            field(a,card,"نوع الجهاز",kind,false);
        if(d.mac!=null && d.mac.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}") &&
                !d.mac.equals("00:00:00:00:00:00"))field(a,card,"الماك",d.mac,true);
        if(!d.vendor.isEmpty())field(a,card,"الشركة المصنّعة",d.vendor,false);
        if(!d.self && open!=null)action(a,card,"فتح الجهاز عبر HTTP",open);
    }
    static void dns(Activity a,LinearLayout parent,String domain,String[] ips,long elapsed) {
        parent.removeAllViews();
        LinearLayout card=newCard(a,parent);
        title(a,card,"نتيجة تحليل DNS");
        field(a,card,"اسم الموقع",domain,true);
        if(ips==null || ips.length==0){
            note(a,card,"لا توجد عناوين في الاستجابة.",ThemePalette.of(a).muted);
        }else{
            for(int i=0;i<ips.length;i++)field(a,card,"عنوان IP "+(i+1),ips[i],true);
        }
        field(a,card,"مدة التحليل",elapsed+" ms",true);
    }
    static void port(Activity a,LinearLayout parent,int port,String service) {
        LinearLayout card=newCard(a,parent);
        title(a,card,"المنفذ "+port);
        field(a,card,"الخدمة",service,false);
        note(a,card,"●  مفتوح",ThemePalette.of(a).accent);
    }
    static void trace(Activity a,LinearLayout parent,String output) {
        if(output==null || output.trim().isEmpty())return;
        LinearLayout card=newCard(a,parent);
        String message=output.trim();
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("^([0-9]{1,2})\\s+(.*)").matcher(message);
        if(m.matches()){
            title(a,card,"القفزة رقم "+m.group(1));
            field(a,card,"العنوان أو النتيجة",m.group(2),true);
        }else{
            note(a,card,message,ThemePalette.of(a).muted);
        }
        if(parent.getChildCount()>75)parent.removeViewAt(0);
    }
    static void wifi(Activity a,LinearLayout parent,WifiAnalyzer.Current wifi) {
        parent.removeAllViews();
        LinearLayout card=newCard(a,parent);
        title(a,card,"معلومات شبكة Wi-Fi");
        field(a,card,"اسم الشبكة",wifi.name,true);
        field(a,card,"النطاق",wifi.band,true);
        if(wifi.frequency>0){
            field(a,card,"التردد الحالي",wifi.frequency+" MHz",true);
            field(a,card,"القناة الحالية",Integer.toString(wifi.channel),true);
        }
        if(wifi.rssi<0 && wifi.rssi>=-120)
            field(a,card,"قوة الإشارة",wifi.rssi+" dBm",true);
        if(wifi.linkSpeed>0)
            field(a,card,"سرعة الارتباط",wifi.linkSpeed+" Mbps",true);
    }
    static void wifiAdvice(Activity a,LinearLayout parent,String details) {
        parent.removeAllViews();
        LinearLayout card=newCard(a,parent);
        title(a,card,"نتيجة تحليل القنوات");
        if(details==null||details.isEmpty())return;
        String[] lines=details.split("\\n");
        for(String line:lines){
            int colon=line.indexOf(':');
            if(colon>0 && colon<line.length()-1)
                field(a,card,line.substring(0,colon).trim(),
                        line.substring(colon+1).trim(),true);
            else note(a,card,line,ThemePalette.of(a).accent);
        }
    }
    static void ping(Activity a,LinearLayout parent,PingSample p,String timestamp) {
        LinearLayout card=newCard(a,parent);
        ThemePalette t=ThemePalette.of(a);
        title(a,card,"النبضة "+p.sequence+"  •  "+timestamp);
        if(p.kind==PingSample.Kind.REPLY) {
            note(a,card,String.format(Locale.US,"%.2f ms",p.rttMs),t.accent);
            field(a,card,"TTL",Integer.toString(p.ttl),true);
        }else if(p.kind==PingSample.Kind.TIMEOUT){
            note(a,card,"انتهت المهلة دون استجابة",t.danger);
        }else{
            note(a,card,"خطأ في الفحص",t.warning);
            if(p.detail!=null)field(a,card,"التفاصيل",p.detail,false);
        }
    }
    static void outage(Activity a,LinearLayout parent,String heading,
                       String address,String start,String length,String outcome) {
        LinearLayout card=newCard(a,parent);
        title(a,card,heading);
        field(a,card,"العنوان",address,true);
        field(a,card,"وقت البداية",start,true);
        field(a,card,"مدة الفصل",length,true);
        note(a,card,outcome,ThemePalette.of(a).muted);
    }
}
