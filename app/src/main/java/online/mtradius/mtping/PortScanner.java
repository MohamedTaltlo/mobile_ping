package online.mtradius.mtping;

import android.content.Context;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Full 1..65535 TCP scan: Nmap -sT when available; async NIO fallback. */
final class PortScanner {
    interface Listener {
        void progress(int tested,int total,int open);
        void found(int port,String service);
        void message(String message);
    }
    static final class Stop {
        final AtomicBoolean cancelled=new AtomicBoolean(false);
        volatile Process process;
        void cancel(){
            cancelled.set(true);
            Process p=process;
            if(p!=null&&p.isAlive())p.destroyForcibly();
        }
        boolean isCancelled(){return cancelled.get()||Thread.currentThread().isInterrupted();}
    }
    private static String service(int port) {
        switch (port) {
            case 21: return "FTP";
            case 22: return "SSH";
            case 23: return "Telnet";
            case 25: return "SMTP";
            case 53: return "DNS / TCP";
            case 80: return "HTTP";
            case 110: return "POP3";
            case 139: return "NetBIOS";
            case 143: return "IMAP";
            case 443: return "HTTPS";
            case 445: return "SMB";
            case 554: return "RTSP";
            case 993: return "IMAPS";
            case 995: return "POP3S";
            case 1723: return "PPTP";
            case 1883: return "MQTT";
            case 3306: return "MySQL";
            case 3389: return "RDP";
            case 5060: return "SIP";
            case 5432: return "PostgreSQL";
            case 5900: return "VNC";
            case 7547: return "TR-069";
            case 8080: return "HTTP alternate";
            case 8291: return "MikroTik Winbox";
            case 8728: return "RouterOS API";
            case 8729: return "RouterOS API SSL";
            default: return "TCP";
        }
    }

    static void scan(Context context,String host,Stop stop,Listener output) throws InterruptedException {
        if(!PingService.validTarget(host)){
            output.message("العنوان غير صالح");return;
        }
        if(NmapEngine.run(context,host,stop,output))return;
        if(stop.isCancelled())throw new InterruptedException();
        fastConnect(host,stop,output);
    }

    private static final class Probe {
        final int port;
        final long began;
        Probe(int port,long began){this.port=port;this.began=began;}
    }
    private static void fastConnect(String host,Stop stop,Listener listener)throws InterruptedException {
        final InetAddress target;
        try{
            target=InetAddress.getByName(host);
        }catch(Exception e){
            listener.message("تعذّر معرفة العنوان: "+e.getMessage());return;
        }
        // Asynchronous connect() allows many sockets to be tested concurrently
        // without allocating hundreds of Java threads or waiting 400ms serially.
        final int maxInflight=256;
        final long timeoutNs=350_000_000L;
        int next=1,done=0,found=0;
        int lastReported=0;
        listener.message("جار فحص TCP سريع (بديل Nmap) للعنوان "+target.getHostAddress());
        try(Selector selector=Selector.open()){
            while((next<=65535||!selector.keys().isEmpty())&&!stop.isCancelled()){
                while(next<=65535 && selector.keys().size()<maxInflight && !stop.isCancelled()){
                    final int port=next++;
                    SocketChannel channel=null;
                    try{
                        channel=SocketChannel.open();
                        channel.configureBlocking(false);
                        boolean ready=channel.connect(new InetSocketAddress(target,port));
                        if(ready){
                            found++;
                            listener.found(port,service(port));
                            channel.close();
                            done++;
                        }else{
                            channel.register(selector,SelectionKey.OP_CONNECT,
                                    new Probe(port,System.nanoTime()));
                        }
                    }catch(Exception ex){
                        done++;
                        if(channel!=null)try{channel.close();}catch(Exception ignored){}
                    }
                }
                if(stop.isCancelled())throw new InterruptedException();
                selector.select(30);
                Iterator<SelectionKey> iterator=selector.selectedKeys().iterator();
                while(iterator.hasNext()){
                    SelectionKey key=iterator.next();
                    iterator.remove();
                    Probe probe=(Probe)key.attachment();
                    try{
                        if(key.isValid() && key.isConnectable() &&
                           ((SocketChannel)key.channel()).finishConnect()){
                            found++;listener.found(probe.port,service(probe.port));
                        }
                    }catch(Exception ignored){}
                    finally{
                        done++;
                        key.cancel();
                        try{key.channel().close();}catch(Exception ignored){}
                    }
                }
                long now=System.nanoTime();
                for(SelectionKey key:new ArrayList<>(selector.keys())){
                    Probe probe=(Probe)key.attachment();
                    if(probe!=null && now-probe.began>=timeoutNs){
                        done++;
                        key.cancel();
                        try{key.channel().close();}catch(Exception ignored){}
                    }
                }
                if(done-lastReported>=128 || done==65535){
                    lastReported=done;
                    listener.progress(done,65535,found);
                }
            }
        }catch(InterruptedException e){
            throw e;
        }catch(Exception e){
            listener.message("خطأ أثناء فحص TCP: "+e.getMessage());
        }
        if(stop.isCancelled())throw new InterruptedException();
        listener.progress(done,65535,found);
        listener.message(String.format(Locale.US,"انتهى فحص TCP: %d منفذ مفتوح من %d مفحوص",
                found,done));
    }
}
