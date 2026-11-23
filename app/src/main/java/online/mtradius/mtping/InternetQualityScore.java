package online.mtradius.mtping;

import java.util.Locale;

/** Transparent heuristic score; unknown measurements are never silently treated as zero. */
final class InternetQualityScore {
    private InternetQualityScore(){}
    static int band(double value,double excellent,double good,double fair){
        if(value<0)return -1;
        if(value<=excellent)return 100;
        if(value<=good)return 80;
        if(value<=fair)return 50;
        return 15;
    }
    private static double best(double a,double b){
        if(a<0)return b;if(b<0)return a;return Math.min(a,b);
    }
    static void evaluate(InternetQualityEngine.Report r,boolean full){
        r.notes.clear();
        double latency=best(r.googleMs,r.cloudflareMs);
        double dns=best(r.dnsGoogleMs,r.dnsCloudflareMs);
        double https=best(r.httpsGoogleMs,r.httpsCloudflareMs);
        // Packet loss is usable only when at least one target replied.
        boolean icmpAvailable=latency>=0;
        int weighted=0,total=0;
        if(icmpAvailable){
            weighted+=band(latency,35,80,160)*20;total+=20;
            weighted+=band(r.lossPercent,0,1,5)*20;total+=20;
            if(r.jitterMs>=0){weighted+=band(r.jitterMs,8,20,45)*15;total+=15;}
            if(r.lossPercent>2)r.notes.add("يوجد فقد بحزم ICMP؛ تحقق من الازدحام أو جودة الإشارة.");
            if(latency>100)r.notes.add("زمن الاستجابة مرتفع وقد يؤثر على الألعاب والمكالمات.");
            if(r.jitterMs>30)r.notes.add("تذبذب الاتصال مرتفع وقد يسبب تقطع الصوت.");
        } else {
            r.lossPercent=-1;
            r.notes.add("تعذّر قياس ICMP؛ بعض الشبكات تحجبه. لا يمكن استنتاج فقد الحزم.");
        }
        if(dns>=0){weighted+=band(dns,45,110,250)*15;total+=15;}
        else r.notes.add("فشل استعلام DNS المباشر؛ قد يكون UDP/53 محجوباً.");
        if(https>=0){weighted+=band(https,180,450,1000)*15;total+=15;}
        else r.notes.add("فشل الوصول إلى وجهات HTTPS المستخدمة في الفحص.");
        if(full){
            // Throughput is rated for general usability, not an ISP plan compliance claim.
            if(r.downloadMbps>=0){
                weighted+=(r.downloadMbps>=25?100:r.downloadMbps>=10?80:
                        r.downloadMbps>=3?50:15)*10;total+=10;
                if(r.downloadMbps<5)r.notes.add("سرعة التنزيل منخفضة للتطبيقات عالية الاستهلاك.");
            }else r.notes.add("تعذّر قياس التنزيل من خادم الاختبار.");
            if(r.uploadMbps>=0){
                weighted+=(r.uploadMbps>=10?100:r.uploadMbps>=3?80:
                        r.uploadMbps>=1?50:15)*5;total+=5;
                if(r.uploadMbps<1)r.notes.add("سرعة الرفع قد تؤثر على مكالمات الفيديو.");
            }else r.notes.add("تعذّر قياس الرفع من خادم الاختبار.");
        }
        if(total==0){r.score=0;r.verdict="تعذّر التقييم";r.diagnosis="لم ينجح أي قياس؛ تحقق من اتصال الإنترنت.";return;}
        r.score=Math.round(weighted/(float)total);
        if(https<0 && dns<0 && !icmpAvailable){
            r.verdict="تعذّر الوصول للإنترنت";r.score=0;
        }else if(r.score>=90)r.verdict="اتصال ممتاز";
        else if(r.score>=75)r.verdict="اتصال جيد";
        else if(r.score>=55)r.verdict="اتصال متوسط";
        else r.verdict="الاتصال يحتاج فحصاً";
        StringBuilder msg=new StringBuilder(r.verdict+".");
        if(r.notes.isEmpty())msg.append(" لم تُرصد مؤشرات واضحة لمشكلة ضمن الفحوص المنفذة.");
        else for(String note:r.notes)msg.append("\n• ").append(note);
        msg.append("\nالنتيجة تقديرية للقياسات المتاحة، وليست ضماناً لسرعة الاشتراك.");
        r.diagnosis=msg.toString();
    }
}
