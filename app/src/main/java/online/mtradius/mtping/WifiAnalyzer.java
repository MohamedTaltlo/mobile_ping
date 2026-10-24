package online.mtradius.mtping;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Read-only Wi-Fi survey. Channel advice is approximate, based on visible
 * access points and signal strengths, not an RF spectrum analyzer.
 */
final class WifiAnalyzer {
    private WifiAnalyzer() { }

    static final class Current {
        final String name;
        final int frequency,channel,rssi,linkSpeed;
        final String band;
        Current(String n,int f,int c,int r,int speed,String b){
            name=n;frequency=f;channel=c;rssi=r;linkSpeed=speed;band=b;
        }
        String describe(){
            if(frequency<=0)return "لا تتوفر معلومات التردد؛ تأكد من اتصال Wi-Fi.";
            StringBuilder b=new StringBuilder("اسم الشبكة: ");
            b.append("\u2066").append(name).append("\u2069");
            b.append("\nالنطاق: ").append(band);
            b.append("\nالتردد الحالي: ").append(frequency).append(" MHz");
            b.append("\nالقناة الحالية: ").append(channel);
            if(rssi>=-120 && rssi<0)b.append("\nقوة الإشارة: ").append(rssi).append(" dBm");
            if(linkSpeed>0)b.append("\nسرعة ارتباط Wi-Fi: ").append(linkSpeed).append(" Mbps");
            return b.toString();
        }
    }

    static int channel(int f){
        if(f==2484)return 14;
        if(f>=2412&&f<=2472)return (f-2407)/5;
        if(f>=5000&&f<=5895)return (f-5000)/5;
        if(f>=5955&&f<=7115)return (f-5950)/5;
        return 0;
    }

    static Current current(Context ctx) {
        WifiManager mgr=(WifiManager)ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if(mgr==null || !mgr.isWifiEnabled())return new Current("غير متصل",0,0,0,0,"—");
        WifiInfo wifi;
        try {wifi=mgr.getConnectionInfo();}
        catch(SecurityException e){return new Current("إذن الموقع مطلوب",0,0,0,0,"—");}
        if(wifi==null)return new Current("غير متاح",0,0,0,0,"—");
        int frequency=wifi.getFrequency();
        String name=wifi.getSSID();
        if(name==null||name.equals("<unknown ssid>"))name="غير متاح (يلزم إذن الموقع)";
        else if(name.length()>=2 && name.startsWith("\"") && name.endsWith("\""))
            name=name.substring(1,name.length()-1);
        String band=frequency>=5925?"6 GHz":frequency>=4900?"5 GHz":
                frequency>=2400?"2.4 GHz":"غير معروف";
        return new Current(name,frequency,channel(frequency),
                wifi.getRssi(),wifi.getLinkSpeed(),band);
    }

    static final class Advice {
        final String label;
        final int detected;
        Advice(String text,int total){label=text;detected=total;}
    }

    static Advice recommend(Context ctx,Current current) {
        if(current.frequency<=0)return new Advice(
                "اتصل بشبكة Wi-Fi أولاً حتى نحدد النطاق والتردد.",0);
        WifiManager mgr=(WifiManager)ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if(mgr==null)return new Advice("خدمة Wi-Fi غير متاحة.",0);
        List<ScanResult> list;
        try {list=mgr.getScanResults();}
        catch(SecurityException e){return new Advice("يجب السماح بإذن الموقع وتشغيل خدمات الموقع لفحص الترددات.",0);}
        if(list==null||list.isEmpty())
            return new Advice("لم تُرجع الشبكة أي نقاط وصول قريبة. لا يمكن اقتراح قناة موثوقة.",0);

        boolean is24=current.frequency>=2400 && current.frequency<2500;
        boolean is5=current.frequency>=4900 && current.frequency<5925;
        if(!is24&&!is5)
            return new Advice("يظهر النطاق الحالي في الأعلى. اقتراح القناة متاح حالياً لنطاقَي 2.4 و5 GHz.",list.size());

        final int[] candidates;
        if(is24)candidates=new int[]{1,6,11};
        else candidates=new int[]{36,40,44,48,149,153,157,161};

        int best=-1;double lowest=Double.POSITIVE_INFINITY;
        int considered=0;
        for(int c:candidates){
            int center=is24?2407+5*c:5000+5*c;
            double score=0;
            int found=0;
            for(ScanResult ap:list){
                if(ap==null || ap.frequency==0)continue;
                if(is24&&(ap.frequency<2400||ap.frequency>2500))continue;
                if(is5&&(ap.frequency<4900||ap.frequency>5925))continue;
                found++;
                // Better overlap estimation: wide 5GHz radios can occupy +/-40 MHz.
                double footprint=is24?25:40;
                double overlap=Math.max(0,1-Math.abs(ap.frequency-center)/footprint);
                if(overlap>0){
                    // Stronger APs count more. Do not infer actual traffic/load from RSSI.
                    double strength=Math.max(1,Math.min(100,100+ap.level));
                    score+=strength*overlap;
                }
            }
            considered=Math.max(considered,found);
            if(score<lowest){lowest=score;best=c;}
        }
        if(considered==0)return new Advice(
                "لم يتم رصد شبكات على نطاق "+current.band+"؛ لا توجد بيانات كافية لاقتراح قناة.",list.size());

        StringBuilder out=new StringBuilder();
        out.append("الشبكات المرصودة على ").append(current.band).append(": ").append(considered);
        out.append("\nالقناة الحالية: ").append(current.channel);
        out.append("\nالقناة الأقل ازدحاماً وفق الإشارات المكتشفة: ").append(best);
        if(best==current.channel)out.append(" (أنت عليها حالياً)");
        else out.append(" (يمكن تجربتها في إعدادات الراوتر)");
        if(is24)out.append("\nيفضل عرض قناة 20 MHz على نطاق 2.4 GHz.");
        else out.append("\nالنتيجة تقريبية وتعتمد على عرض القناة والتداخل من الشبكات المحيطة.");
        out.append("\nتنبيه: استعمل فقط القنوات المسموحة في بلدك والمدعومة من الراوتر.");
        out.append("\nالتوصية مبنية على قوة إشارات Wi-Fi، وليس ازدحام البيانات أو قياس طيف RF.");
        return new Advice(out.toString(),list.size());
    }
}
