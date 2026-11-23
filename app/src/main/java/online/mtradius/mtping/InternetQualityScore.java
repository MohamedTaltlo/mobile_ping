package online.mtradius.mtping;

/**
 * Heuristic score over available measures. The Bufferbloat grade reflects
 * measured ADDED delay, not throughput and not a claimed external certification.
 */
final class InternetQualityScore {
    private InternetQualityScore(){}
    private static int band(double n,double excellent,double good,double fair){
        if(n<0)return -1;
        return n<=excellent?100:n<=good?80:n<=fair?50:15;
    }
    private static double best(double a,double b){
        return a<0?b:b<0?a:Math.min(a,b);
    }
    static String grade(double down,double up){
        if(down<0||up<0)return "—";
        double delay=Math.max(down,up);
        if(delay<=5)return "A+";
        if(delay<=15)return "A";
        if(delay<=30)return "B";
        if(delay<=60)return "C";
        if(delay<=120)return "D";
        return "F";
    }
    private static int gradeScore(String grade){
        if("A+".equals(grade))return 100;
        if("A".equals(grade))return 92;
        if("B".equals(grade))return 75;
        if("C".equals(grade))return 50;
        if("D".equals(grade))return 25;
        return "F".equals(grade)?5:-1;
    }
    private static int speedScore(double speed,boolean download){
        if(speed<0)return -1;
        if(download)return speed>=40?100:speed>=15?85:speed>=5?65:speed>=2?40:15;
        return speed>=10?100:speed>=4?85:speed>=1.5?65:speed>=.5?40:15;
    }
    private static final class Acc {
        int weighted,weight,max;
        void add(int result,int contribution){
            max+=contribution;
            if(result<0)return;
            weight+=contribution;weighted+=result*contribution;
        }
        int score(){return weight==0?-1:Math.round(weighted/(float)weight);}
        int confidence(){return max==0?0:Math.round(100f*weight/max);}
    }
    static void evaluate(InternetQualityEngine.Report r,boolean full){
        r.notes.clear();
        double latency=best(r.googleMs,r.cloudflareMs);
        if(latency<0)r.lossPercent=-1;
        double dns=best(r.dnsGoogleMs,r.dnsCloudflareMs);
        double https=best(r.httpsGoogleMs,r.httpsCloudflareMs);
        int ping=band(latency,35,80,160);
        int loss=band(r.lossPercent,0,1,5);
        int jitter=band(r.jitterMs,8,20,45);
        int resolver=band(dns,45,110,250);
        int websites=band(https,180,450,1000);
        int dl=speedScore(r.downloadMbps,true),up=speedScore(r.uploadMbps,false);
        r.bufferGrade=grade(r.downDelta,r.upDelta);
        int bloat=gradeScore(r.bufferGrade);
        Acc main=new Acc();
        if(full){
            main.add(ping,15);main.add(loss,15);main.add(jitter,10);
            main.add(resolver,10);main.add(websites,10);
            main.add(dl,10);main.add(up,5);main.add(bloat,25);
        }else{
            main.add(ping,30);main.add(loss,25);main.add(jitter,15);
            main.add(resolver,15);main.add(websites,15);
        }
        r.confidence=main.confidence();
        if(main.weight==0){
            r.score=0;r.verdict="تعذّر التقييم";
            r.diagnosis="لم تستجب وجهات الاختبار. تأكد من اتصال الإنترنت ثم أعد الفحص.";
            return;
        }
        r.score=main.score();
        if(latency<0 && dns<0 && https<0){
            r.score=0;r.verdict="لا يوجد اتصال مؤكد بالإنترنت";
        }else if(r.score>=90)r.verdict="اتصال ممتاز";
        else if(r.score>=75)r.verdict="اتصال جيد";
        else if(r.score>=55)r.verdict="اتصال متوسط";
        else r.verdict="الاتصال يحتاج معالجة";
        Acc games=new Acc();
        games.add(ping,35);games.add(loss,25);games.add(jitter,20);
        if(full)games.add(bloat,20);
        r.gaming=games.score();
        Acc calls=new Acc();
        calls.add(ping,20);calls.add(loss,25);calls.add(jitter,20);
        if(full){calls.add(up,10);calls.add(bloat,25);}
        r.calls=calls.score();
        Acc browsing=new Acc();
        browsing.add(resolver,35);browsing.add(websites,35);
        if(full){browsing.add(dl,20);browsing.add(bloat,10);}
        r.browsing=browsing.score();

        if(r.lossPercent>=5)
            r.notes.add("هناك فقد ملحوظ في ردود Ping؛ افحص استقرار Wi-Fi أو المسار نحو الخادم.");
        else if(r.lossPercent<0)
            r.notes.add("فقد حزم ICMP غير مؤكد؛ الخوادم قد تحدّ من ردود Ping.");
        if(latency>110)r.notes.add("زمن الاستجابة مرتفع وقد يؤثر على الألعاب والمكالمات.");
        if(r.jitterMs>30)r.notes.add("تذبذب الاستجابة مرتفع وقد يسبب تقطع الصوت.");
        if(r.dnsGoogleMs<0&&r.dnsCloudflareMs<0)
            r.notes.add("استعلامات DNS المباشرة إلى UDP/53 فشلت؛ قد تكون الشبكة تحجبها.");
        else if(r.dnsGoogleMs<0 || r.dnsCloudflareMs<0)
            r.notes.add("أحد خادمي DNS لم يستجب للاستعلام المباشر؛ افحص الحظر أو التوجيه.");
        if(https<0)r.notes.add("تعذّر الوصول إلى وجهات HTTPS المختبرة.");
        if(full){
            if(bloat>=0){
                double worst=Math.max(r.downDelta,r.upDelta);
                if(worst>60)r.notes.add("Bufferbloat مرتفع تحت الضغط: جرّب SQM (CAKE أو FQ-CoDel) وضبط الرفع والتنزيل تحت سرعة الخط الحقيقية.");
                else if(worst>30)r.notes.add("تأخير إضافي أثناء ضغط الخط؛ قد يفيد تفعيل SQM للحد من ازدحام الطوابير.");
            }else r.notes.add("لم تتوفر عينات متزامنة كافية لتحديد درجة Bufferbloat.");
            if(r.downloadMbps<0)r.notes.add("تعذّر إكمال قياس التنزيل.");
            if(r.uploadMbps<0)r.notes.add("تعذّر إكمال قياس الرفع.");
            if(r.uploadMbps>=0&&r.uploadMbps<1)
                r.notes.add("سرعة الرفع منخفضة وقد تؤثر على مكالمات الفيديو.");
        }
        if(r.confidence<75)r.notes.add("بعض المقاييس غير متاحة، لذا التقييم مبني على الفحوص الناجحة فقط.");
        if(r.notes.isEmpty())r.notes.add("لم تظهر مشاكل مؤثرة ضمن القياسات الحالية.");
        StringBuilder result=new StringBuilder();
        for(String note:r.notes){
            if(result.length()>0)result.append("\n\n");
            result.append("• ").append(note);
        }
        r.diagnosis=result.toString();
    }
}
