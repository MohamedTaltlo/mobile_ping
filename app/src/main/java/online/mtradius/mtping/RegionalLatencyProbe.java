package online.mtradius.mtping;

import android.os.SystemClock;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Direct TCP SYN/ACK RTT. Unlike remote ICMP, no artificial DNS-provider
 * reply throttling. It is NOT UDP game ping or an Ookla measurement.
 */
final class RegionalLatencyProbe {
    static final class Endpoint {
        final String host;
        final InetAddress ip;
        final double medianMs, minimumMs, jitterMs;
        final int sent, received;
        Endpoint(String h,InetAddress a,double med,double min,double jitter,int sent,int received){
            host=h;ip=a;medianMs=med;minimumMs=min;jitterMs=jitter;
            this.sent=sent;this.received=received;
        }
    }
    private static final String[] HOSTS={
        "www.google.com", "www.microsoft.com", "www.apple.com",
        "www.wikipedia.org", "www.facebook.com", "www.amazon.com"
    };
    private final AtomicBoolean cancelled;
    RegionalLatencyProbe(AtomicBoolean c){cancelled=c;}
    private void check()throws InterruptedException{
        if(cancelled.get()||Thread.currentThread().isInterrupted())throw new InterruptedException();
    }
    static double percentile(List<Double> numbers,double fraction) {
        if(numbers.size()==0)return -1;
        ArrayList<Double> sorted=new ArrayList<>(numbers);
        Collections.sort(sorted);
        double index=(sorted.size()-1)*fraction;
        int lo=(int)index,hi=Math.min(sorted.size()-1,lo+1);
        return sorted.get(lo)+(sorted.get(hi)-sorted.get(lo))*(index-lo);
    }
    static double jitter(List<Double> samples) {
        if(samples.size()<3)return -1;
        double diff=0;
        for(int i=1;i<samples.size();i++)diff+=Math.abs(samples.get(i)-samples.get(i-1));
        return diff/(samples.size()-1);
    }
    /** Returns -1 when connection failed; DOES NOT assume that a loss was on the ISP. */
    double sample(InetAddress address)throws InterruptedException {
        check();
        Socket s=new Socket();
        try{
            long start=SystemClock.elapsedRealtimeNanos();
            s.connect(new InetSocketAddress(address,443),1500);
            return (SystemClock.elapsedRealtimeNanos()-start)/1e6;
        }catch(IOException ex){return -1;}
        finally{try{s.close();}catch(IOException ignored){}}
    }
    Endpoint findNearest()throws InterruptedException {
        Endpoint best=null;
        for(String host:HOSTS) {
            check();
            try{
                InetAddress[] choices=InetAddress.getAllByName(host);
                // Use actual OS-selected resolution order; don't force IPv4 on IPv6-only networks.
                for(int j=0;j<Math.min(2,choices.length);j++){
                    InetAddress ip=choices[j];
                    ArrayList<Double> results=new ArrayList<>();
                    for(int i=0;i<6;i++){
                        double n=sample(ip);
                        if(n>=0)results.add(n);
                    }
                    if(results.size()<4)continue;
                    double med=percentile(results,.5);
                    Endpoint candidate=new Endpoint(host,ip,med,percentile(results,0),
                            jitter(results),6,results.size());
                    // Prefer shortest successful TCP path, not an average of distant hosts.
                    if(best==null||candidate.medianMs<best.medianMs)best=candidate;
                    break;
                }
            }catch(IOException ignored){
                // Resolve or connection failure for one provider must not fail the sweep.
            }
        }
        return best;
    }
    static double extraLatency(double base,double loaded){
        if(base<0||loaded<0)return -1;
        return Math.max(0,loaded-base);
    }
}
