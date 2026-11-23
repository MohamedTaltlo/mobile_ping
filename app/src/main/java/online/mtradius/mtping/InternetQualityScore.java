package online.mtradius.mtping;

/**
 * Assessment of confirmed, independent probes.
 * Remote ICMP rate-limiting, Cloudflare routing and remote HTTPS server time
 * must not be mistaken for last-mile congestion or packet loss.
 */
final class InternetQualityScore {
    private InternetQualityScore(){}
    static int band(double value,double excellent,double good,double fair) {
        if(value<0)return -1;
        return value<=excellent?100:value<=good?85:value<=fair?60:15;
    }
    private static double best(double a,double b){return a<0?b:b<0?a:Math.min(a,b);}
    static String grade(double down,double up){
        if(down<0||up<0)return "—";
        double worst=Math.max(down,up);
        if(worst<=5)return "A+";
        if(worst<=15)return "A";
        if(worst<=30)return "B";
        if(worst<=60)return "C";
        if(worst<=120)return "D";
        return "F";
    }
    private static int gradeScore(String g){
        if("A+".equals(g))return 100;
        if("A".equals(g))return 92;
        if("B".equals(g))return 78;
        if("C".equals(g))return 53;
        if("D".equals(g))return 28;
        return "F".equals(g)?5:-1;
    }
    private static int speed(double mbps,boolean download){
        if(mbps<0)return -1;
        if(download)return mbps>=25?100:mbps>=10?90:mbps>=4?75:mbps>=1?50:15;
        return mbps>=8?100:mbps>=3?90:mbps>=1?75:mbps>=.35?50:15;
    }
    private static final class Weighted {
        int sum,valid,possible;
        void add(int value,int weight){
            possible+=weight;
            if(value>=0){sum+=value*weight;valid+=weight;}
        }
        int value(){return valid==0?-1:Math.round(sum/(float)valid);}
        int confidence(){return possible==0?0:Math.round(100f*valid/possible);}
    }
    static void evaluate(InternetQualityEngine.Report r,boolean comprehensive){
        r.notes.clear();
        // A local/nearby TCP handshake is a better baseline than distant ICMP to 8.8.8.8.
        double latency=r.nearMs>=0?r.nearMs:best(r.googleMs,r.cloudflareMs);
        double variation=r.nearJitter>=0?r.nearJitter:r.jitterMs;
        double dns=best(r.dnsGoogleMs,r.dnsCloudflareMs);
        double web=best(r.httpsGoogleMs,r.httpsCloudflareMs);
        int near=band(latency,30,65,120);
        int stable=band(variation,6,16,36);
        int resolver=band(dns,50,130,300);
        // HTTPS response time includes TLS, server processing & geography; test availability only.
        int reachable=web<0?-1:100;
        int dl=speed(r.downloadMbps,true),ul=speed(r.uploadMbps,false);
        r.bufferGrade=grade(r.downDelta,r.upDelta);
        int buffer=gradeScore(r.bufferGrade);
        // No direct packet-loss test: ICMP provider reply rate is NOT reliable packet loss.
        r.lossPercent=-1;
        Weighted total=new Weighted();
        if(comprehensive){
            total.add(near,25);total.add(stable,12);
            total.add(resolver,8);total.add(reachable,5);
            total.add(dl,10);total.add(ul,10);total.add(buffer,30);
        }else{
            total.add(near,55);total.add(stable,25);
            total.add(resolver,10);total.add(reachable,10);
        }
        r.confidence=total.confidence();
        if(total.valid==0){
            r.score=0;r.verdict="لم تكتمل القياسات";
            r.diagnosis="تعذر الوصول إلى وجهات القياس. تحقق من الاتصال ثم أعد الفحص.";
            return;
        }
        r.score=total.value();
        if(latency<0&&dns<0&&web<0){
            r.score=0;r.verdict="لا يوجد اتصال مؤكد بالإنترنت";
        }else if(r.score>=90)r.verdict="اتصال ممتاز";
        else if(r.score>=75)r.verdict="اتصال جيد";
        else if(r.score>=55)r.verdict="اتصال متوسط";
        else r.verdict="الاتصال يحتاج متابعة";

        Weighted games=new Weighted();
        games.add(near,65);games.add(stable,25);
        if(comprehensive)games.add(buffer,10);
        r.gaming=games.value();
        Weighted calls=new Weighted();
        calls.add(near,25);calls.add(stable,40);
        if(comprehensive){calls.add(ul,10);calls.add(buffer,25);}
        r.calls=calls.value();
        Weighted browsing=new Weighted();
        browsing.add(resolver,30);browsing.add(reachable,30);
        if(comprehensive){browsing.add(dl,20);browsing.add(buffer,20);}
        r.browsing=browsing.value();

        if(r.nearMs<0){
            r.notes.add("لم يتوفر خادم TCP سريع للقياس؛ اعتمد التقدير على الفحوص البديلة.");
        }else if(r.nearMs>120)r.notes.add("أقرب وجهة استجابت ببطء؛ تحقق من مسار الاتصال.");
        if(variation>35)r.notes.add("الاستجابة متذبذبة حتى بدون ضغط؛ افحص Wi-Fi والازدحام.");
        if(r.dnsGoogleMs<0&&r.dnsCloudflareMs<0&&web>=0)
            r.notes.add("الإنترنت يعمل، لكن استعلامات UDP/53 المباشرة محجوبة أو لا تستجيب.");
        if(comprehensive){
            if(buffer>=0){
                if(Math.max(r.downDelta,r.upDelta)>120)
                    r.notes.add("ازدحام طوابير قوي تحت الضغط. افحص Bufferbloat وفعّل SQM/CAKE أو FQ-CoDel على الراوتر إذا لزم.");
                else if(Math.max(r.downDelta,r.upDelta)>45)
                    r.notes.add("الاستجابة تتأخر عند تشبع الخط؛ قد يساعد ضبط الطوابير.");
            }else r.notes.add("لا توجد عينات متزامنة كافية في الاتجاهين لتقييم Bufferbloat.");
            if(r.mlabDownload<0&&r.mlabUpload<0)
                r.notes.add("لم يتوفر خادم M-Lab مستقل لهذه التجربة؛ السرعة المعروضة تخص قياس Cloudflare فقط.");
        }
        if(r.nearMs<0&&r.googleMs<0&&r.cloudflareMs<0)
            r.notes.add("غياب ردود ICMP لا يثبت وجود فقد حزم؛ قد تكون الردود محدودة.");
        if(r.confidence<70)
            r.notes.add("التقييم جزئي لأن بعض الاختبارات لم تُرجع قياسات صالحة.");
        if(r.notes.isEmpty())r.notes.add("لم تُرصد مشكلة واضحة في المقاييس المستقلة المتاحة.");
        StringBuilder summary=new StringBuilder();
        for(String note:r.notes){
            if(summary.length()>0)summary.append("\n\n");
            summary.append("• ").append(note);
        }
        r.diagnosis=summary.toString();
    }
}
