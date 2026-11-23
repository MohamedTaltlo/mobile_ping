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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** User-initiated diagnostics. Never blocks the main thread. */
final class InternetQualityEngine {
    interface Progress { void update(String step, Report partial); }
    static final class Report {
        int score=-1;
        String verdict="لم يبدأ الفحص";
        String transport="—";
        double googleMs=-1, cloudflareMs=-1, jitterMs=-1, lossPercent=-1;
        double dnsGoogleMs=-1, dnsCloudflareMs=-1, httpsGoogleMs=-1, httpsCloudflareMs=-1;
        double downloadMbps=-1, uploadMbps=-1, loadedMs=-1;
        String gateway="—", diagnosis="ابدأ الفحص لعرض التشخيص.";
        final List<String> notes=new ArrayList<>();
        Report copy() {
            Report c=new Report();
            c.score=score;c.verdict=verdict;c.transport=transport;
            c.googleMs=googleMs;c.cloudflareMs=cloudflareMs;c.jitterMs=jitterMs;
            c.lossPercent=lossPercent;c.dnsGoogleMs=dnsGoogleMs;
            c.dnsCloudflareMs=dnsCloudflareMs;c.httpsGoogleMs=httpsGoogleMs;
            c.httpsCloudflareMs=httpsCloudflareMs;c.downloadMbps=downloadMbps;
            c.uploadMbps=uploadMbps;c.loadedMs=loadedMs;c.gateway=gateway;
            c.diagnosis=diagnosis;c.notes.addAll(notes);
            return c;
        }
    }

    private static final Pattern RTT=Pattern.compile("time[=<]([0-9]+(?:\\.[0-9]+)?)",Pattern.CASE_INSENSITIVE);
    private final AtomicBoolean cancelled=new AtomicBoolean();
    void cancel(){cancelled.set(true);}
    private void check() throws InterruptedException {
        if(cancelled.get() || Thread.currentThread().isInterrupted())throw new InterruptedException();
    }
    private static double median(List<Double> values) {
        if(values.isEmpty())return -1;
        List<Double> list=new ArrayList<>(values);
        Collections.sort(list);
        int n=list.size();return n%2==1?list.get(n/2):(list.get(n/2-1)+list.get(n/2))/2;
    }
    private static final class PingResult {
        double median=-1,jitter=-1,loss=100;
        int received;
    }
    private PingResult ping(String host,int count) throws InterruptedException {
        PingResult result=new PingResult();
        List<Double> samples=new ArrayList<>();
        // One system ICMP request per process: each sample is independently counted.
        for(int i=0;i<count;i++){
            check();
            Process process=null;
            try {
                process=new ProcessBuilder("/system/bin/ping","-n","-c","1","-W","2",host)
                        .redirectErrorStream(true).start();
                long deadline=SystemClock.elapsedRealtime()+2600;
                while(true){
                    check();
                    if(process.waitFor(80,java.util.concurrent.TimeUnit.MILLISECONDS))break;
                    if(SystemClock.elapsedRealtime()>deadline){process.destroyForcibly();break;}
                }
                if(!process.isAlive()){
                    StringBuilder text=new StringBuilder();
                    try(InputStream in=process.getInputStream()){
                        byte[] b=new byte[512];int n;
                        while((n=in.read(b))!=-1 && text.length()<4096)
                            text.append(new String(b,0,n,StandardCharsets.US_ASCII));
                    }
                    Matcher match=RTT.matcher(text);
                    if(match.find()){
                        samples.add(Double.parseDouble(match.group(1)));
                        result.received++;
                    }
                }
            }catch(IOException ex){
                // Some Android vendors disable ICMP: report unavailable, not invented RTT.
                return new PingResult();
            }finally{if(process!=null)process.destroy();}
        }
        result.loss=100.0*(count-result.received)/count;
        result.median=median(samples);
        if(samples.size()>1){
            double total=0;
            for(int i=1;i<samples.size();i++)total+=Math.abs(samples.get(i)-samples.get(i-1));
            result.jitter=total/(samples.size()-1);
        }
        return result;
    }

    /** Actual UDP DNS query to a named resolver, not a cached Java hostname lookup. */
    private double dns(String resolver,String host) throws InterruptedException {
        check();
        DatagramSocket socket=null;
        try{
            byte[] query=new byte[512];int p=0;
            int id=(int)(SystemClock.elapsedRealtime()&0xffff);
            query[p++]=(byte)(id>>>8);query[p++]=(byte)id;
            query[p++]=1;query[p++]=0;query[p++]=0;query[p++]=1;
            p+=6;
            for(String label:host.split("\\.")){
                byte[] bytes=label.getBytes(StandardCharsets.US_ASCII);
                if(bytes.length<1||bytes.length>63)return -1;
                query[p++]=(byte)bytes.length;
                System.arraycopy(bytes,0,query,p,bytes.length);p+=bytes.length;
            }
            query[p++]=0;query[p++]=0;query[p++]=1;query[p++]=0;query[p++]=1;
            socket=new DatagramSocket();socket.setSoTimeout(2500);
            long start=SystemClock.elapsedRealtimeNanos();
            socket.send(new DatagramPacket(query,p,InetAddress.getByName(resolver),53));
            byte[] reply=new byte[512];
            DatagramPacket packet=new DatagramPacket(reply,reply.length);
            socket.receive(packet);
            long elapsed=SystemClock.elapsedRealtimeNanos()-start;
            if(packet.getLength()<12 || (reply[0]&255)!=(id>>>8) || (reply[1]&255)!=(id&255)
                    || (reply[2]&0x80)==0 || (reply[3]&15)!=0 || (reply[7]&255)==0)return -1;
            return elapsed/1e6;
        }catch(IOException ex){return -1;}finally{if(socket!=null)socket.close();}
    }

    private double https(String uri) throws InterruptedException {
        check();
        HttpURLConnection conn=null;
        try{
            conn=(HttpURLConnection)new URL(uri).openConnection();
            conn.setConnectTimeout(4000);conn.setReadTimeout(4000);
            conn.setUseCaches(false);conn.setRequestProperty("Cache-Control","no-cache");
            long start=SystemClock.elapsedRealtimeNanos();
            int status=conn.getResponseCode();
            return status>=200&&status<400?(SystemClock.elapsedRealtimeNanos()-start)/1e6:-1;
        }catch(IOException ex){return -1;}finally{if(conn!=null)conn.disconnect();}
    }

    private double download(int bytes,Report r,Progress progress) throws InterruptedException {
        check();
        HttpURLConnection conn=null;
        try{
            conn=(HttpURLConnection)new URL("https://speed.cloudflare.com/__down?bytes="+bytes)
                    .openConnection();
            conn.setConnectTimeout(4500);conn.setReadTimeout(5500);
            conn.setUseCaches(false);conn.setRequestProperty("Accept-Encoding","identity");
            if(conn.getResponseCode()!=200)return -1;
            long start=SystemClock.elapsedRealtimeNanos();
            long n=0; byte[] buffer=new byte[32768];
            try(InputStream input=conn.getInputStream()){
                int size;
                while((size=input.read(buffer))!=-1){
                    check();
                    n+=size;
                    if(n>bytes+65536)break;
                }
            }
            double elapsed=(SystemClock.elapsedRealtimeNanos()-start)/1e9;
            return n>=bytes*0.9&&elapsed>0?n*8/elapsed/1e6:-1;
        }catch(IOException ex){return -1;}finally{if(conn!=null)conn.disconnect();}
    }

    private double upload(int bytes) throws InterruptedException {
        check();
        HttpURLConnection conn=null;
        try{
            conn=(HttpURLConnection)new URL("https://speed.cloudflare.com/__up").openConnection();
            conn.setRequestMethod("POST");conn.setDoOutput(true);
            conn.setConnectTimeout(4500);conn.setReadTimeout(6500);
            conn.setRequestProperty("Content-Type","application/octet-stream");
            conn.setFixedLengthStreamingMode(bytes);
            byte[] buf=new byte[16384];new java.security.SecureRandom().nextBytes(buf);
            long start=SystemClock.elapsedRealtimeNanos();
            try(OutputStream out=conn.getOutputStream()){
                int written=0;
                while(written<bytes){
                    check();int len=Math.min(buf.length,bytes-written);
                    out.write(buf,0,len);written+=len;
                }
                out.flush();
            }
            int response=conn.getResponseCode();
            long elapsed=SystemClock.elapsedRealtimeNanos()-start;
            return response>=200&&response<300&&elapsed>0
                    ?bytes*8/(elapsed/1e9)/1e6:-1;
        }catch(IOException ex){return -1;}finally{if(conn!=null)conn.disconnect();}
    }

    private void publish(Progress p,String step,Report r){p.update(step,r.copy());}
    Report run(Context context,boolean full,Progress progress) throws InterruptedException {
        Report r=new Report();
        ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network active=manager==null?null:manager.getActiveNetwork();
        NetworkCapabilities cap=active==null?null:manager.getNetworkCapabilities(active);
        r.transport=cap==null?"غير متصل":
                cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"Wi-Fi":
                cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?"بيانات الهاتف":
                cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)?"Ethernet":"شبكة أخرى";
        NetworkTools.Connection connection=NetworkTools.connection(context);
        r.gateway=connection.gateway.isEmpty()?"غير متاح":connection.gateway;
        if(active==null){
            r.score=0;r.verdict="لا يوجد اتصال بالشبكة";
            r.diagnosis="الهاتف غير متصل بأي شبكة. تحقق من Wi-Fi أو بيانات الهاتف.";
            publish(progress,"اكتمل الفحص",r);return r;
        }
        publish(progress,"قياس Ping إلى Google",r);
        PingResult google=ping("8.8.8.8",5);
        r.googleMs=google.median;r.lossPercent=google.loss;r.jitterMs=google.jitter;
        check();publish(progress,"قياس Ping إلى Cloudflare",r);
        PingResult cloud=ping("1.1.1.1",5);
        r.cloudflareMs=cloud.median;
        if(google.received==0&&cloud.received>0){
            r.lossPercent=cloud.loss;r.jitterMs=cloud.jitter;
        }else if(google.received>0&&cloud.received>0){
            r.lossPercent=(google.loss+cloud.loss)/2;
            if(google.jitter<0)r.jitterMs=cloud.jitter;
            else if(cloud.jitter>=0)r.jitterMs=(google.jitter+cloud.jitter)/2;
        }
        check();publish(progress,"فحص DNS عبر Google وCloudflare",r);
        r.dnsGoogleMs=dns("8.8.8.8","example.com");
        r.dnsCloudflareMs=dns("1.1.1.1","example.org");
        check();publish(progress,"فحص اتصال HTTPS",r);
        r.httpsGoogleMs=https("https://www.google.com/generate_204");
        r.httpsCloudflareMs=https("https://www.cloudflare.com/cdn-cgi/trace");
        if(full){
            check();publish(progress,"قياس سرعة التنزيل (يستهلك بيانات)",r);
            r.downloadMbps=download(3*1024*1024,r,progress);
            check();publish(progress,"قياس سرعة الرفع (يستهلك بيانات)",r);
            r.uploadMbps=upload(1024*1024);
            // Loaded latency is a best-effort HTTP probe after transfers,
            // NOT a concurrent load measurement; do not label it bufferbloat.
        }
        check();
        InternetQualityScore.evaluate(r,full);
        publish(progress,"اكتمل الفحص",r);
        return r;
    }
}
