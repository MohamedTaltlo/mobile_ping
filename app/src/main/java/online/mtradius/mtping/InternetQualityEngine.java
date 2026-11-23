package online.mtradius.mtping;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** On-device, user-initiated, active measurements. No privileged API or tracking. */
final class InternetQualityEngine {
    interface Progress { void update(String phase, Report result); }
    static final class Report {
        int score=-1, confidence=0, gaming=-1, calls=-1, browsing=-1;
        String verdict="جاهز للفحص", transport="—", gateway="—", diagnosis="ابدأ الفحص لعرض التفاصيل.";
        String bufferGrade="—";
        double googleMs=-1, cloudflareMs=-1, googleLoss=-1, cloudflareLoss=-1;
        double lossPercent=-1, jitterMs=-1, dnsGoogleMs=-1, dnsCloudflareMs=-1;
        double httpsGoogleMs=-1, httpsCloudflareMs=-1, downloadMbps=-1, uploadMbps=-1;
        double idleMs=-1, loadDownMs=-1, loadUpMs=-1, downDelta=-1, upDelta=-1;
        double downP95=-1,upP95=-1;
        double nearMs=-1,nearMin=-1,nearJitter=-1,cfDownload=-1,cfUpload=-1,mlabDownload=-1,mlabUpload=-1;
        String nearHost="—",mlabServer="—";
        final List<String> notes=new ArrayList<>();
        Report copy() {
            Report v=new Report();
            v.score=score;v.confidence=confidence;v.gaming=gaming;v.calls=calls;v.browsing=browsing;
            v.verdict=verdict;v.transport=transport;v.gateway=gateway;v.diagnosis=diagnosis;
            v.bufferGrade=bufferGrade;v.googleMs=googleMs;v.cloudflareMs=cloudflareMs;
            v.googleLoss=googleLoss;v.cloudflareLoss=cloudflareLoss;v.lossPercent=lossPercent;
            v.jitterMs=jitterMs;v.dnsGoogleMs=dnsGoogleMs;v.dnsCloudflareMs=dnsCloudflareMs;
            v.httpsGoogleMs=httpsGoogleMs;v.httpsCloudflareMs=httpsCloudflareMs;
            v.downloadMbps=downloadMbps;v.uploadMbps=uploadMbps;v.idleMs=idleMs;
            v.loadDownMs=loadDownMs;v.loadUpMs=loadUpMs;v.downDelta=downDelta;
            v.upDelta=upDelta;v.downP95=downP95;v.upP95=upP95;
            v.nearMs=nearMs;v.nearMin=nearMin;v.nearJitter=nearJitter;v.nearHost=nearHost;
            v.cfDownload=cfDownload;v.cfUpload=cfUpload;
            v.mlabDownload=mlabDownload;v.mlabUpload=mlabUpload;v.mlabServer=mlabServer;
            v.notes.addAll(notes);
            return v;
        }
    }
    private static final String BASE="https://speed.cloudflare.com";
    private static final Pattern RTT=Pattern.compile("time[=<]([0-9]+(?:\\.[0-9]+)?)",Pattern.CASE_INSENSITIVE);
    private final AtomicBoolean cancelled=new AtomicBoolean(false);
    private final RegionalLatencyProbe regional=new RegionalLatencyProbe(cancelled);
    private volatile MlabNdt7 mlab;
    private volatile RegionalLatencyProbe.Endpoint selected;
    private final ConcurrentLinkedQueue<HttpURLConnection> requests=new ConcurrentLinkedQueue<>();
    private static final class PingResult {
        double median=-1,loss=-1,jitter=-1;
        int replies,requested;
    }
    void cancel() {
        cancelled.set(true);
        if(mlab!=null)mlab.cancel();
        for(HttpURLConnection c:requests) c.disconnect();
    }
    private void check() throws InterruptedException {
        if(cancelled.get()||Thread.currentThread().isInterrupted())throw new InterruptedException();
    }
    static double percentile(List<Double> numbers,double fraction) {
        if(numbers.isEmpty())return -1;
        ArrayList<Double> n=new ArrayList<>(numbers);Collections.sort(n);
        double ix=(n.size()-1)*fraction;
        int lower=(int)ix,upper=Math.min(n.size()-1,lower+1);
        return n.get(lower)+(n.get(upper)-n.get(lower))*(ix-lower);
    }
    private static double median(List<Double> numbers){return percentile(numbers,0.5);}
    private static double best(double a,double b){return a<0?b:b<0?a:Math.min(a,b);}

    /** ICMP is separate per target; an ICMP-blocking destination is not "100% loss". */
    private PingResult ping(String target,int count) throws InterruptedException {
        PingResult r=new PingResult();
        ArrayList<Double> successful=new ArrayList<>();
        int attempts=0;
        for(int i=0;i<count;i++){
            check();Process p=null;
            try {
                p=new ProcessBuilder("/system/bin/ping","-n","-c","1","-W","1",target)
                        .redirectErrorStream(true).start();
                attempts++;
                long deadline=SystemClock.elapsedRealtime()+1700;
                while(!p.waitFor(60,TimeUnit.MILLISECONDS)){
                    check();
                    if(SystemClock.elapsedRealtime()>=deadline){p.destroyForcibly();break;}
                }
                if(!p.isAlive()){
                    StringBuilder output=new StringBuilder();
                    try(InputStream in=p.getInputStream()){
                        byte[] buf=new byte[512];int read;
                        while((read=in.read(buf))>0&&output.length()<3000)
                            output.append(new String(buf,0,read,StandardCharsets.US_ASCII));
                    }
                    Matcher m=RTT.matcher(output);
                    if(m.find())successful.add(Double.parseDouble(m.group(1)));
                }
            } catch(IOException ignored){
                // ROM may disable the native ping binary.
                break;
            } finally {if(p!=null)p.destroy();}
        }
        r.requested=attempts;r.replies=successful.size();r.median=median(successful);
        // Requires several valid replies; otherwise mark ICMP quality uncertain.
        if(attempts>=4&&r.replies>=3)r.loss=100.0*(attempts-r.replies)/attempts;
        if(successful.size()>=3){
            double total=0;
            for(int i=1;i<successful.size();i++)
                total+=Math.abs(successful.get(i)-successful.get(i-1));
            r.jitter=total/(successful.size()-1);
        }
        return r;
    }

    /** UDP/53 resolver request. Failure is not proof the phone's system DNS is broken. */
    private double dns(String resolver,String hostname) throws InterruptedException {
        check();DatagramSocket socket=null;
        try{
            byte[] query=new byte[512];int pos=0;
            int id=(int)(SystemClock.elapsedRealtimeNanos()&65535);
            query[pos++]=(byte)(id>>8);query[pos++]=(byte)id;
            query[pos++]=1;query[pos++]=0;query[pos++]=0;query[pos++]=1;pos+=6;
            for(String label:hostname.split("\\.")){
                byte[] b=label.getBytes(StandardCharsets.US_ASCII);
                if(b.length<1||b.length>63)return -1;
                query[pos++]=(byte)b.length;System.arraycopy(b,0,query,pos,b.length);pos+=b.length;
            }
            query[pos++]=0;query[pos++]=0;query[pos++]=1;query[pos++]=0;query[pos++]=1;
            InetAddress address=InetAddress.getByName(resolver);
            socket=new DatagramSocket();socket.setSoTimeout(2200);
            long start=SystemClock.elapsedRealtimeNanos();
            socket.send(new DatagramPacket(query,pos,address,53));
            byte[] response=new byte[512];
            DatagramPacket packet=new DatagramPacket(response,response.length);
            socket.receive(packet);
            double elapsed=(SystemClock.elapsedRealtimeNanos()-start)/1e6;
            if(packet.getLength()<12||!packet.getAddress().equals(address)||packet.getPort()!=53||
                    (response[0]&255)!=(id>>8)||(response[1]&255)!=(id&255)||
                    (response[2]&128)==0||(response[3]&15)!=0||
                    ((response[6]&255)*256+(response[7]&255))<1)return -1;
            return elapsed;
        }catch(IOException ignored){return -1;}finally{if(socket!=null)socket.close();}
    }

    /** Network round trip to response headers. Same test host for idle and loaded RTT. */
    private double http(String url,int timeout) throws InterruptedException {
        check();HttpURLConnection c=null;
        try{
            c=(HttpURLConnection)new URL(url).openConnection();
            requests.add(c);
            c.setConnectTimeout(timeout);c.setReadTimeout(timeout);
            c.setUseCaches(false);c.setRequestProperty("Cache-Control","no-store");
            c.setRequestProperty("Accept-Encoding","identity");
            long start=SystemClock.elapsedRealtimeNanos();
            int response=c.getResponseCode();
            if(response<200||response>=400)return -1;
            try(InputStream in=c.getInputStream()){
                byte[] buf=new byte[256];while(in.read(buf)!=-1){check();}
            }
            return (SystemClock.elapsedRealtimeNanos()-start)/1e6;
        }catch(IOException ignored){return -1;}
        finally{if(c!=null){requests.remove(c);c.disconnect();}}
    }
    private double cfProbe() throws InterruptedException {
        return http(BASE+"/__down?bytes=0",2600);
    }
    private void report(Progress listener,String text,Report r) {listener.update(text,r.copy());}

    private static final class Phase {
        double rate=-1,loaded=-1,p95=-1;
        int samples;
    }
    /** 3 simultaneous TCP flows, with RTT probes WHILE the transfers are active. */
    private Phase stressedPhase(boolean upload,Report report,Progress progress)
            throws InterruptedException {
        check();
        Phase phase=new Phase();
        final long timeStart=SystemClock.elapsedRealtime();
        final long deadline=timeStart+10500;
        final long cap=upload?256L*1024*1024:512L*1024*1024;
        final AtomicLong transferred=new AtomicLong(),activity=new AtomicLong();
        final AtomicInteger completed=new AtomicInteger();
        final AtomicBoolean phaseStop=new AtomicBoolean();
        final ExecutorService pool=Executors.newFixedThreadPool(4);
        final List<Future<?>> jobs=new ArrayList<>();
        for(int lane=0;lane<4;lane++)jobs.add(pool.submit((Runnable)()->{
            while(!phaseStop.get()&&!cancelled.get()&&
                    !Thread.currentThread().isInterrupted()&&
                    SystemClock.elapsedRealtime()<deadline&&activity.get()<cap){
                if(upload)uploadBatch(1024*1024,activity,transferred,completed,phaseStop);
                else downloadBatch(8*1024*1024,activity,transferred,completed,phaseStop);
            }
        }));
        ArrayList<Double> latencySamples=new ArrayList<>();
        long lastObservedActivity=0;
        try{
            long warmup=SystemClock.elapsedRealtime()+850;
            while(SystemClock.elapsedRealtime()<warmup){check();Thread.sleep(60);}
            long probeDeadline=timeStart+9500;
            while(SystemClock.elapsedRealtime()<probeDeadline){
                check();
                double sample=selected==null?-1:regional.sample(selected.ip);
                // Count traffic since the previous probe. This detects sustained
                // upload traffic even if a sampled TCP connect spans a brief pause.
                long amount=activity.get();
                if(sample>=0 && amount>lastObservedActivity+8192 && amount>65536)
                    latencySamples.add(sample);
                lastObservedActivity=amount;
                report(progress,upload?"ضغط الرفع وقياس الاستجابة":"ضغط التنزيل وقياس الاستجابة",report);
                long nap=SystemClock.elapsedRealtime()+220;
                while(SystemClock.elapsedRealtime()<nap&&SystemClock.elapsedRealtime()<probeDeadline){
                    check();Thread.sleep(45);
                }
            }
        }finally{
            phaseStop.set(true);
            pool.shutdownNow();
            // Abort active connections, otherwise a stalled read can continue to stress the line.
            for(HttpURLConnection c:requests)c.disconnect();
            pool.awaitTermination(3200,TimeUnit.MILLISECONDS);
        }
        long elapsed=Math.max(1,SystemClock.elapsedRealtime()-timeStart);
        if(completed.get()>0&&transferred.get()>0)
            phase.rate=transferred.get()*8.0/(elapsed*1000.0);
        // A valid loaded sample requires visible concurrent payload traffic.
        if(latencySamples.size()>=5){
            phase.loaded=median(latencySamples);
            phase.p95=percentile(latencySamples,0.95);
            phase.samples=latencySamples.size();
        }
        return phase;
    }

    private void downloadBatch(int bytes,AtomicLong activity,AtomicLong transferred,
                               AtomicInteger completed,AtomicBoolean stop) {
        HttpURLConnection c=null;
        try{
            c=(HttpURLConnection)new URL(BASE+"/__down?bytes="+bytes).openConnection();
            requests.add(c);c.setConnectTimeout(3100);c.setReadTimeout(3000);
            c.setUseCaches(false);c.setRequestProperty("Accept-Encoding","identity");
            if(c.getResponseCode()!=200)return;
            completed.incrementAndGet();
            long done=0;
            try(InputStream in=c.getInputStream()){
                byte[] buffer=new byte[32768];int n;
                while(!stop.get()&&!cancelled.get()&&
                        !Thread.currentThread().isInterrupted()&&(n=in.read(buffer))!=-1){
                    activity.addAndGet(n);transferred.addAndGet(n);done+=n;
                }
            }
            
        }catch(IOException ignored){}finally{if(c!=null){requests.remove(c);c.disconnect();}}
    }
    private void uploadBatch(int bytes,AtomicLong activity,AtomicLong transferred,
                             AtomicInteger completed,AtomicBoolean stop) {
        HttpURLConnection c=null;
        try{
            c=(HttpURLConnection)new URL(BASE+"/__up").openConnection();
            requests.add(c);c.setRequestMethod("POST");c.setDoOutput(true);
            c.setConnectTimeout(3100);c.setReadTimeout(3000);
            c.setRequestProperty("Content-Type","application/octet-stream");
            c.setFixedLengthStreamingMode(bytes);
            byte[] buffer=new byte[16384];new java.security.SecureRandom().nextBytes(buffer);
            int sent=0;
            try(OutputStream out=c.getOutputStream()){
                while(sent<bytes&&!stop.get()&&!cancelled.get()&&
                        !Thread.currentThread().isInterrupted()){
                    int n=Math.min(buffer.length,bytes-sent);
                    out.write(buffer,0,n);sent+=n;activity.addAndGet(n);
                }
                if(sent==bytes)out.flush();
            }
            if(sent==bytes&&c.getResponseCode()>=200&&c.getResponseCode()<300){
                transferred.addAndGet(sent);completed.incrementAndGet();
            }
        }catch(IOException ignored){}finally{if(c!=null){requests.remove(c);c.disconnect();}}
    }

    Report run(Context context,boolean full,Progress listener) throws InterruptedException {
        Report r=new Report();
        ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network=cm==null?null:cm.getActiveNetwork();
        NetworkCapabilities caps=network==null?null:cm.getNetworkCapabilities(network);
        r.transport=caps==null?"غير متصل":
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"Wi-Fi":
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?"بيانات الهاتف":
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)?"Ethernet":"شبكة أخرى";
        NetworkTools.Connection con=NetworkTools.connection(context);
        r.gateway=con.gateway.isEmpty()?"غير متاح":con.gateway;
        if(network==null){
            r.score=0;r.verdict="لا توجد شبكة";r.diagnosis="لا يوجد اتصال نشط بالشبكة.";
            report(listener,"اكتمل الفحص",r);return r;
        }
        report(listener,"اختيار أسرع مسار من عدة وجهات",r);
        selected=regional.findNearest();
        if(selected!=null){
            r.nearMs=selected.medianMs;
            r.nearMin=selected.minimumMs;
            r.nearJitter=selected.jitterMs;
            r.nearHost=selected.host;
        }
        check();report(listener,"فحص Google وCloudflare الإضافي",r);
        PingResult google=ping("8.8.8.8",6);
        r.googleMs=google.median;r.googleLoss=google.loss;
        check();
        PingResult cloud=ping("1.1.1.1",6);
        r.cloudflareMs=cloud.median;r.cloudflareLoss=cloud.loss;
        // Neither resolver ICMP loss nor blocked Echo replies represent data-packet loss.
        r.lossPercent=-1;
        r.jitterMs=r.nearJitter;
        check();report(listener,"التحقق من DNS",r);
        r.dnsGoogleMs=dns("8.8.8.8","example.com");
        r.dnsCloudflareMs=dns("1.1.1.1","example.org");
        check();report(listener,"التحقق من المواقع",r);
        r.httpsGoogleMs=http("https://www.google.com/generate_204",3200);
        r.httpsCloudflareMs=http("https://www.cloudflare.com/cdn-cgi/trace",3200);
        if(full){
            check();report(listener,"قياس TCP قبل الضغط",r);
            ArrayList<Double> quiet=new ArrayList<>();
            if(selected!=null){
                for(int i=0;i<9;i++){
                    check();
                    double p=regional.sample(selected.ip);
                    if(p>=0)quiet.add(p);
                    if(i<8)Thread.sleep(95);
                }
            }
            if(quiet.size()>=5)r.idleMs=median(quiet);
            check();report(listener,"تنزيل متعدد الاتصالات واختبار الاستجابة",r);
            Phase down=stressedPhase(false,r,listener);
            r.cfDownload=down.rate;r.loadDownMs=down.loaded;r.downP95=down.p95;
            if(r.idleMs>=0&&r.loadDownMs>=0)
                r.downDelta=RegionalLatencyProbe.extraLatency(r.idleMs,r.loadDownMs);
            check();report(listener,"رفع متعدد الاتصالات واختبار الاستجابة",r);
            Phase up=stressedPhase(true,r,listener);
            r.cfUpload=up.rate;r.loadUpMs=up.loaded;r.upP95=up.p95;
            if(r.idleMs>=0&&r.loadUpMs>=0)
                r.upDelta=RegionalLatencyProbe.extraLatency(r.idleMs,r.loadUpMs);
            check();report(listener,"اختبار مستقل عبر M-Lab",r);
            mlab=new MlabNdt7(cancelled);
            try{
                MlabNdt7.Result independent=mlab.run();
                r.mlabDownload=independent.download;
                r.mlabUpload=independent.upload;
                r.mlabServer=independent.server;
            }finally{mlab=null;}
            r.downloadMbps=Math.max(r.cfDownload,r.mlabDownload);
            r.uploadMbps=Math.max(r.cfUpload,r.mlabUpload);
        }
        check();
        InternetQualityScore.evaluate(r,full);
        report(listener,"اكتمل الفحص",r);
        return r;
    }
}
