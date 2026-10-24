package online.mtradius.mtping;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import java.util.List;
import java.util.Locale;

/**
 * Read-only Wi-Fi survey. Channel advice is approximate, based on visible
 * access points and signal strengths, not an RF spectrum analyzer.
 */
final class WifiAnalyzer {
    private WifiAnalyzer() { }
    /** LTR numeric runs isolated inside Arabic lines. */
    private static String ltr(String value) {
        return "\u2066" + value + "\u2069";
    }

    static final class Current {
        final String name;
        final int frequency,channel,rssi,linkSpeed;
        final String band;
        Current(String n,int f,int c,int r,int speed,String b){
            name=n;frequency=f;channel=c;rssi=r;linkSpeed=speed;band=b;
        }
        String describe(){
            if(frequency<=0)return "لا تتوفر معلومات الاتصال بشبكة Wi-Fi.";
            StringBuilder b=new StringBuilder("اسم الشبكة: ");
            b.append(ltr(name));
            b.append("\nالنطاق: ").append(ltr(band));
            b.append("\nالتردد الحالي: ").append(ltr(frequency+" MHz"));
            b.append("\nالقناة الحالية: ").append(ltr(Integer.toString(channel)));
            if(rssi>=-120 && rssi<0)
                b.append("\nقوة الإشارة: ").append(ltr(rssi+" dBm"));
            if(linkSpeed>0)
                b.append("\nسرعة ارتباط Wi-Fi: ").append(ltr(linkSpeed+" Mbps"));
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

    /**
     * Approximate local 2.4GHz interference from received AP beacons.
     * ScanResult.channelWidth distinguishes 20 and 40MHz neighbors;
     * never treat 11 overlapping primary channels as non-overlapping.
     */
    private static double score24(List<ScanResult> stations,int frequency) {
        double score=0;
        for(ScanResult ap:stations){
            if(ap==null || ap.frequency<2400 || ap.frequency>=2500)continue;
            int halfWidth=ap.channelWidth==ScanResult.CHANNEL_WIDTH_40MHZ?20:10;
            int actualCenter=(halfWidth==20 && ap.centerFreq0>0)
                    ?ap.centerFreq0:ap.frequency;
            double distance=Math.abs(actualCenter-frequency);
            double overlap=Math.max(0,1-distance/(halfWidth+10.0));
            if(overlap>0) {
                double strength=Math.max(1,Math.min(100,100+ap.level));
                score+=strength*overlap;
            }
        }
        return score;
    }

    static Advice recommend(Context ctx,Current current) {
        if(current.frequency<=0)
            return new Advice("اتصل بشبكة Wi-Fi أولاً.",0);
        final boolean is24=current.frequency>=2400 && current.frequency<2500;
        final boolean is5=current.frequency>=4900 && current.frequency<5925;
        if(!is24 && !is5)
            return new Advice("تحليل القنوات متاح لنطاقَي 2.4 و5 GHz.",0);

        WifiManager manager=(WifiManager)ctx.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        List<ScanResult> list=null;
        try {
            if(manager!=null)list=manager.getScanResults();
        } catch(SecurityException ignored) {
            // A missing permission is not a measured count of zero APs.
        }

        int seen=0;
        if(list!=null){
            for(ScanResult ap:list){
                if(ap==null)continue;
                if(is24 && ap.frequency>=2400 && ap.frequency<2500)seen++;
                if(is5 && ap.frequency>=4900 && ap.frequency<5925)seen++;
            }
        }
        StringBuilder out=new StringBuilder();
        out.append("الشبكات المرصودة على ").append(ltr(current.band))
                .append(": ").append(ltr(list==null || list.isEmpty()?"—":Integer.toString(seen)));
        out.append("\nالقناة الحالية: ").append(ltr(Integer.toString(current.channel)));

        if(is5) {
            // Product choice: 5GHz typically uses 80MHz; the recommended four
            // primary channels 36/40/44/48 share one 80MHz RF block.
            // Display "no change" unconditionally as requested.
            out.append("\nالتردد الحالي لا يحتاج إلى تغيير");
            return new Advice(out.toString(),seen);
        }

        if(list==null || seen==0){
            out.append("\nلا توجد بيانات كافية لاقتراح قناة.");
            return new Advice(out.toString(),seen);
        }

        // 2.4GHz has 11 commonly-supported primary channels. Evaluate all of
        // them with 20/40MHz overlap, while preferring 1/6/11 in close cases.
        int best=current.channel>=1 && current.channel<=11?current.channel:1;
        double bestScore=Double.POSITIVE_INFINITY;
        for(int channel=1;channel<=11;channel++){
            int mhz=2407+5*channel;
            double score=score24(list,mhz);
            if(channel!=1 && channel!=6 && channel!=11)score+=8;
            if(score<bestScore-0.00001){bestScore=score;best=channel;}
        }
        double currentScore=score24(list,current.frequency);
        if(current.channel!=1 && current.channel!=6 && current.channel!=11)
            currentScore+=8;
        boolean keep=currentScore <= bestScore+Math.max(8,bestScore*.15);
        if(keep) {
            out.append("\nالتردد الحالي لا يحتاج إلى تغيير");
        } else {
            out.append("\nالقناة الأقل ازدحاماً: ")
                    .append(ltr(Integer.toString(best)))
                    .append("  •  ").append(ltr((2407+5*best)+" MHz"));
        }
        return new Advice(out.toString(),seen);
    }
}
