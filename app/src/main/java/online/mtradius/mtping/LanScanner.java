package online.mtradius.mtping;

import android.content.Context;
import android.net.wifi.WifiManager;
import java.io.ByteArrayOutputStream;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local-only, bounded diagnostic sweep using multiple independent discovery signals. */
final class LanScanner {
    interface Update {
        void device(Device device);
        void progress(int completed,int total,int detected);
        void info(String line);
    }
    static final class Device {
        final String ip;
        String mac="", name="", vendor="", type="جهاز شبكة", source="";
        double latency=-1;
        boolean gateway, self;
        boolean port80,port443,port22,port554,port9100,port445;
        Device(String ip){this.ip=ip;}
        String displayName(){
            if(self)return "هذا الهاتف";
            if(gateway)return "البوابة • Gateway";
            if(!name.isEmpty())return name;
            if(!vendor.isEmpty())return vendor+" • جهاز";
            return type;
        }
        String kind(){
            if(self)return "الهاتف الحالي";
            if(gateway)return "البوابة الرئيسية";
            if(port9100)return "طابعة (احتمال قوي: منفذ 9100)";
            if(port554)return "كاميرا أو جهاز بث (RTSP)";
            if(port445)return "كمبيوتر / تخزين شبكي (SMB)";
            if(port22)return "جهاز إدارة SSH";
            if(port80||port443)return "واجهة إدارة ويب";
            return type;
        }
        Device copy(){
            Device c=new Device(ip);c.mac=mac;c.name=name;c.vendor=vendor;c.type=type;
            c.source=source;c.latency=latency;c.gateway=gateway;c.self=self;
            c.port80=port80;c.port443=port443;c.port22=port22;c.port554=port554;
            c.port9100=port9100;c.port445=port445;return c;
        }
    }
    private LanScanner(){}
    private static final Pattern RTT=Pattern.compile("(?i)\\btime\\s*[=<]\\s*(\\d+(?:[.,]\\d+)?)\\s*ms");
    private static final Pattern MAC=Pattern.compile("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}");
    private static final String[] OUI={
        "24:A4:3C|Ubiquiti","80:2A:A8|Ubiquiti","04:18:D6|Ubiquiti",
        "FC:EC:DA|Ubiquiti","68:72:51|Ubiquiti",
        "4C:5E:0C|MikroTik","DC:2C:6E|MikroTik","2C:C8:1B|MikroTik",
        "48:8F:5A|MikroTik","D4:01:C3|MikroTik",
        "50:C7:BF|TP-Link","EC:08:6B|TP-Link","F4:F2:6D|TP-Link",
        "E8:DE:27|TP-Link","B0:4E:26|TP-Link",
        "18:31:BF|ASUS","2C:56:DC|ASUS",
        "D8:07:B6|Hikvision","44:19:B6|Hangzhou Hikvision",
        "3C:EF:8C|Dahua","A0:BD:1D|Dahua",
        "28:CF:E9|Apple","F0:18:98|Apple","BC:52:B7|Apple",
        "CC:2D:E0|MikroTik"
    };
    static String vendor(String mac){
        if(mac==null || !MAC.matcher(mac).matches())return "";
        int first=Integer.parseInt(mac.substring(0,2),16);
        if((first&2)!=0)return ""; // locally administered / randomized
        for(String pair:OUI){
            String[] p=pair.split("\\|");
            if(mac.toUpperCase(Locale.US).startsWith(p[0]))return p[1];
        }
        return "";
    }
    private static final class NbInfo {
        String name="",mac="";
    }
    static NbInfo netbios(String ip) {
        NbInfo out=new NbInfo();
        byte[] query=new byte[50];
        query[0]=0x44; query[1]=0x21;query[2]=0;query[3]=0;
        query[4]=0;query[5]=1;
        query[12]=0x20;
        byte[] value=new byte[16];
        value[0]='*';
        for(int i=0;i<16;i++){
            int b=value[i]&255;
            query[13+i*2]=(byte)('A'+((b>>>4)&15));
            query[14+i*2]=(byte)('A'+(b&15));
        }
        query[45]=0;
        query[46]=0;query[47]=0x21;query[48]=0;query[49]=1;
        try(DatagramSocket socket=new DatagramSocket()){
            socket.setSoTimeout(240);
            socket.send(new DatagramPacket(query,query.length,new InetSocketAddress(ip,137)));
            byte[] reply=new byte[1500]; DatagramPacket packet=new DatagramPacket(reply,reply.length);
            socket.receive(packet);
            int length=packet.getLength();
            // Scan name-stats RR: type NBSTAT (0x0021) followed by 1-byte name count.
            for(int i=12;i+15<length;i++){
                if((reply[i]&255)==0 && (reply[i+1]&255)==0x21 &&
                   (reply[i+2]&255)==0 && (reply[i+3]&255)==1){
                    int rdlen=((reply[i+8]&255)<<8)|(reply[i+9]&255);
                    int data=i+10;
                    if(data>=length || rdlen<1 || data+rdlen>length)continue;
                    int entries=reply[data]&255;
                    if(entries<1 || entries>40 || 1+18*entries>rdlen)continue;
                    for(int n=0;n<entries;n++){
                        int base=data+1+18*n;
                        int suffix=reply[base+15]&255;
                        String hostname=new String(reply,base,15,StandardCharsets.US_ASCII).trim();
                        if(!hostname.isEmpty() && !hostname.startsWith("*") &&
                           (suffix==0 || suffix==0x20)){
                            out.name=hostname;break;
                        }
                    }
                    int macat=data+1+18*entries;
                    if(macat+6<=length)out.mac=String.format(Locale.US,"%02X:%02X:%02X:%02X:%02X:%02X",
                        reply[macat]&255,reply[macat+1]&255,reply[macat+2]&255,
                        reply[macat+3]&255,reply[macat+4]&255,reply[macat+5]&255);
                    return out;
                }
            }
        }catch(Exception ignored){}
        return out;
    }
    private static double ping(String ip) throws InterruptedException {
        String response=NetworkTools.run(new String[]{"/system/bin/ping","-n","-c","1","-W","1",ip},1700);
        if(!response.toLowerCase(Locale.US).contains("bytes from"))return -1;
        Matcher m=RTT.matcher(response);
        if(!m.find())return -1;
        try{return Double.parseDouble(m.group(1).replace(',','.'));}catch(Exception e){return -1;}
    }
    private static int tcp(String ip,int port,int timeout){
        try(Socket s=new Socket()){
            s.connect(new InetSocketAddress(ip,port),timeout);
            return 2; // open
        }catch(ConnectException e){
            return 1; // actively refused: reachable host but port closed
        }catch(Exception ignored){
            return 0; // filtered, silent or unavailable
        }
    }
    private static Map<String,String> ssdp(Context context){
        Map<String,String> found=new HashMap<>();
        WifiManager.MulticastLock lock=null;
        try{
            WifiManager wifi=(WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if(wifi!=null){lock=wifi.createMulticastLock("MT_TOOLS_SSDP");
                lock.setReferenceCounted(false);lock.acquire();}
            try(DatagramSocket sock=new DatagramSocket()){
                sock.setSoTimeout(190);
                String packet="M-SEARCH * HTTP/1.1\r\nHOST:239.255.255.250:1900\r\nMAN:\"ssdp:discover\"\r\nMX:1\r\nST:ssdp:all\r\n\r\n";
                byte[] raw=packet.getBytes(StandardCharsets.US_ASCII);
                sock.send(new DatagramPacket(raw,raw.length,new InetSocketAddress("239.255.255.250",1900)));
                long cutoff=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(1500);
                while(System.nanoTime()<cutoff){
                    try{
                        byte[] bytes=new byte[2000];DatagramPacket answer=new DatagramPacket(bytes,bytes.length);
                        sock.receive(answer);
                        String ip=answer.getAddress().getHostAddress();
                        String body=new String(answer.getData(),0,answer.getLength(),StandardCharsets.UTF_8);
                        String label="جهاز UPnP";
                        for(String row:body.split("\\r?\\n")){
                            if(row.toLowerCase(Locale.US).startsWith("server:")){
                                String server=row.substring(7).trim();
                                if(!server.isEmpty())label="UPnP • "+server.substring(0,Math.min(server.length(),65));
                                break;
                            }
                        }
                        found.put(ip,label);
                    }catch(SocketTimeoutException ignored){}
                }
            }
        }catch(Exception ignored){}finally{if(lock!=null&&lock.isHeld())lock.release();}
        return found;
    }
    static void scan(Context ctx,NetworkTools.Connection net,Update update)throws InterruptedException {
        if(!net.wifiLan()){update.info("اتصل بشبكة Wi-Fi أو Ethernet أولاً.");return;}
        String[] chunks=net.localIp.split("\\.");
        if(chunks.length!=4){update.info("تعذّر قراءة شبكة IPv4 المحلية.");return;}
        String prefix=chunks[0]+"."+chunks[1]+"."+chunks[2]+".";
        int own=Integer.parseInt(chunks[3]);
        int low=1, high=254;
        if(net.prefix>24 && net.prefix<=30){
            int block=1<<(32-net.prefix);
            low=(own/block)*block+1;
            high=Math.min(254,(own/block+1)*block-2);
        }
        if(net.prefix<24) update.info("الشبكة أوسع من /24؛ سيُفحص /24 المحيط بهاتفك فقط.");
        update.info("استكشاف الشبكة "+prefix+"0/24 باستخدام Ping وTCP وUPnP وNetBIOS...");
        Device self=new Device(net.localIp);self.self=true;self.type="هذا الهاتف";self.source="عنوان الواجهة";
        update.device(self);
        if(!net.gateway.isEmpty()){
            Device gateway=new Device(net.gateway);gateway.gateway=true;
            gateway.type="راوتر / بوابة";gateway.source="إعدادات Wi-Fi";
            update.device(gateway);
        }
        Map<String,String> upnp=ssdp(ctx);
        for(Map.Entry<String,String> entry:upnp.entrySet()){
            if(entry.getKey().startsWith(prefix)){
                Device d=new Device(entry.getKey());d.type=entry.getValue();d.source="UPnP/SSDP";
                update.device(d);
            }
        }
        NeighborLookup arp=new NeighborLookup();
        arp.refresh();
        final int first=low,last=high;
        int total=last-first+1-(own>=first&&own<=last?1:0);
        AtomicInteger completed=new AtomicInteger(), detected=new AtomicInteger();
        update.progress(0,total,0);
        ExecutorService pool=Executors.newFixedThreadPool(14);
        List<Future<?>> tasks=new ArrayList<>();
        try{
            for(int i=first;i<=last;i++){
                if(i==own)continue;
                final String ip=prefix+i;
                tasks.add(pool.submit(()->{
                    try{
                        double ms=ping(ip);
                        int http=0,https=0,ssh=0;
                        boolean alive=ms>=0;
                        if(!alive){
                            http=tcp(ip,80,240);
                            if(http==0) https=tcp(ip,443,240);
                            if(http==0&&https==0)ssh=tcp(ip,22,200);
                            alive=http>0||https>0||ssh>0;
                        }
                        if(alive){
                            Device d=new Device(ip);
                            d.gateway=ip.equals(net.gateway);
                            d.latency=ms;
                            d.source=ms>=0?"ICMP Ping":"TCP";
                            d.port80=http==2;d.port443=https==2;d.port22=ssh==2;
                            d.mac=arp.get(ip);
                            if(d.mac.isEmpty()) d.mac=NetworkTools.localArpMac(ip);
                            d.vendor=vendor(d.mac);
                            if(upnp.containsKey(ip))d.type=upnp.get(ip);
                            update.device(d);detected.incrementAndGet();
                            // Enrichment is non-blocking for the rest of the subnet.
                            NbInfo nb=netbios(ip);
                            if(!nb.mac.isEmpty() && MAC.matcher(nb.mac).matches() &&
                                    !nb.mac.equals("00:00:00:00:00:00"))d.mac=nb.mac;
                            if(!nb.name.isEmpty())d.name=nb.name;
                            if(d.vendor.isEmpty())d.vendor=vendor(d.mac);
                            if(!d.name.isEmpty()||!d.mac.isEmpty())update.device(d);
                        }
                    }catch(InterruptedException e){Thread.currentThread().interrupt();}
                    catch(Exception ignored){}
                    finally{update.progress(completed.incrementAndGet(),total,detected.get());}
                }));
            }
            for(Future<?> job:tasks){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                try{job.get();}catch(java.util.concurrent.ExecutionException ignored){}
            }
        }finally{pool.shutdownNow();}
        arp.refresh();
        // A populated kernel neighbor cache may only be accessible after the probes.
        update.info("انتهى الفحص. الأجهزة المستجيبة: "+detected.get()+
                " • MAC: عبر ARP/Netlink/NetBIOS إن توفّر، والتصنيف تقريبي ما لم يرسل الجهاز هويته.");
    }
}
