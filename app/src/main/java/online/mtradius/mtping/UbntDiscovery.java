package online.mtradius.mtping;

import android.content.Context;
import android.net.wifi.WifiManager;
import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Ubiquiti Discovery protocol, UDP port 10001; v1/v2 TLV.
 * ARP only supplies MAC when readable. Model/firmware/uptime are never inferred
 * from MAC prefixes or ping.
 */
final class UbntDiscovery {
    static final class Device {
        String address = "";
        String mac = "";
        String hostname = "";
        String model = "";
        String firmware = "";
        String ssid = "";
        long uptime = -1;
        String describe() {
            String title = !model.isEmpty() ? model : !hostname.isEmpty() ? hostname : "جهاز UBNT";
            StringBuilder b = new StringBuilder(title).append("\nIP: ").append(address);
            if (!mac.isEmpty()) b.append("  |  MAC: ").append(mac);
            if (!hostname.isEmpty()) b.append("\nالاسم: ").append(hostname);
            if (!firmware.isEmpty()) b.append("\nالبرنامج: ").append(firmware);
            if (!ssid.isEmpty()) b.append("\nSSID: ").append(ssid);
            b.append("\nUptime: ").append(uptime < 0 ? "غير متاح" : clock(uptime));
            return b.toString();
        }
    }
    interface Found { void result(Device device); }
    private UbntDiscovery() {}

    static String clock(long seconds) {
        long d = seconds / 86400, h = (seconds / 3600) % 24, m = (seconds / 60) % 60, s = seconds % 60;
        return String.format(Locale.US, "%d يوم %02d:%02d:%02d", d, h, m, s);
    }

    private static String string(byte[] buf, int offset, int len) {
        return new String(buf, offset, len, StandardCharsets.UTF_8).replace("\u0000", "").trim();
    }
    private static String mac(byte[] buf, int offset) {
        return String.format(Locale.US, "%02X:%02X:%02X:%02X:%02X:%02X",
                buf[offset]&255,buf[offset+1]&255,buf[offset+2]&255,
                buf[offset+3]&255,buf[offset+4]&255,buf[offset+5]&255);
    }
    static Device parse(byte[] data, int len, String sender) {
        if (len < 4 || (data[0] != 1 && data[0] != 2) || data[1] != 0) return null;
        Device out = new Device();
        out.address = sender;
        boolean any = false;
        int p = 4;
        while (p + 3 <= len) {
            int type = data[p] & 255;
            int size = (data[p+1]&255)*256 + (data[p+2]&255);
            p += 3;
            if (size < 0 || p + size > len) return null;
            if (type == 1 && size >= 6) { out.mac = mac(data,p); any = true; }
            else if (type == 2 && size >= 10) {
                out.mac = mac(data,p);
                out.address = String.format(Locale.US, "%d.%d.%d.%d",
                    data[p+6]&255,data[p+7]&255,data[p+8]&255,data[p+9]&255);
                any = true;
            }
            else if (type == 3 && size > 0) { out.firmware = string(data,p,size); any = true; }
            else if (type == 11 && size > 0) { out.hostname = string(data,p,size); any = true; }
            else if ((type == 12 || type == 20 || type == 21) && size > 0) {
                String model = string(data,p,size);
                if (!model.isEmpty()) out.model = model;
                any = true;
            }
            else if (type == 13 && size > 0) { out.ssid = string(data,p,size); }
            else if (type == 10 && (size == 4 || size == 8)) {
                long v = 0;
                for (int i=0;i<size;i++) v=(v << 8)|(data[p+i]&255L);
                if (v >= 0 && v <= 31536000000L) out.uptime = v;
            }
            p += size;
        }
        return any ? out : null;
    }

    static void scan(Context context, NetworkTools.Connection net, Found callback,
                     NetworkTools.Output message) throws Exception {
        if (!net.wifiLan()) {
            message.line("يحتاج كشف UBNT للاتصال بنفس Wi-Fi أو الشبكة السلكية."); return;
        }
        WifiManager.MulticastLock lock = null;
        DatagramSocket socket = null;
        Map<String,Device> discovered = new LinkedHashMap<>();
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null && "Wi-Fi".equals(net.transport)) {
                lock=wifi.createMulticastLock("MT_TOOLS_UBNT_DISCOVERY");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(350);
            String[] destinations = {"255.255.255.255", broadcastFor(net)};
            byte[][] queries = {{1,0,0,0},{2,0,0,0}};
            for (String host : destinations) {
                if (host.isEmpty()) continue;
                InetAddress dst = InetAddress.getByName(host);
                for(byte[] query : queries) {
                    socket.send(new DatagramPacket(query, query.length, dst, 10001));
                }
            }
            // Compatibility with the WiFiman discovery handshake.
            byte[] hello={0,0,0,0};
            socket.send(new DatagramPacket(hello,hello.length,
                    InetAddress.getByName("255.255.255.255"),5678));
            message.line("تم إرسال طلب اكتشاف UBNT على UDP/10001...");
            long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            byte[] packet = new byte[4096];
            while(System.nanoTime()<deadline && !Thread.currentThread().isInterrupted()) {
                try {
                    DatagramPacket response = new DatagramPacket(packet,packet.length);
                    socket.receive(response);
                    Device d=parse(response.getData(),response.getLength(),response.getAddress().getHostAddress());
                    if(d==null)continue;
                    String key=!d.mac.isEmpty()?d.mac:d.address;
                    Device previous=discovered.get(key);
                    if(previous!=null) {
                        if(d.model.isEmpty())d.model=previous.model;
                        if(d.firmware.isEmpty())d.firmware=previous.firmware;
                        if(d.hostname.isEmpty())d.hostname=previous.hostname;
                        if(d.uptime<0)d.uptime=previous.uptime;
                    }
                    discovered.put(key,d);
                    callback.result(d);
                } catch(SocketTimeoutException ignored) { }
            }
            message.line("اكتمل الاكتشاف. عدد أجهزة UBNT المستجيبة: "+discovered.size());
            if(discovered.isEmpty())message.line("بعض الأجهزة تعطل Discovery أو تمنع UDP Broadcast.");
        } finally {
            if (socket!=null)socket.close();
            if (lock!=null && lock.isHeld())lock.release();
        }
    }

    private static String broadcastFor(NetworkTools.Connection net) {
        try {
            String[] parts = net.localIp.split("\\.");
            if(parts.length!=4 || net.prefix<1 || net.prefix>30)return "";
            long ip=0;
            for(String p:parts)ip=(ip<<8)|Integer.parseInt(p);
            long mask=0xFFFFFFFFL << (32-net.prefix) & 0xFFFFFFFFL;
            long bcast=(ip&mask)|(~mask&0xFFFFFFFFL);
            return ((bcast>>>24)&255)+"."+((bcast>>>16)&255)+"."+((bcast>>>8)&255)+"."+(bcast&255);
        } catch(Exception ignored){ return ""; }
    }
}
