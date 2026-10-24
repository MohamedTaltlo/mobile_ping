package online.mtradius.mtping;

import android.system.NetlinkSocketAddress;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructTimeval;
import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Best-effort neighbor table access. It never invents MAC addresses. */
final class NeighborLookup {
    private static final Pattern MAC = Pattern.compile("(?i)([0-9a-f]{2}(?::[0-9a-f]{2}){5})");
    private final Map<String,String> known=new HashMap<>();

    synchronized String get(String ip) {
        String m=known.get(ip);
        return m==null?"":m;
    }
    synchronized void put(String ip,String mac) {
        if(ip!=null && mac!=null && MAC.matcher(mac).matches() &&
                !mac.equalsIgnoreCase("00:00:00:00:00:00"))
            known.put(ip,mac.toUpperCase(Locale.US));
    }
    void refresh() {
        readProc();
        readShell();
        readNetlink();
    }
    private void readProc(){
        try(BufferedReader r=new BufferedReader(new FileReader("/proc/net/arp"))){
            String line;
            while((line=r.readLine())!=null){
                String[] v=line.trim().split("\\s+");
                if(v.length>3)put(v[0],v[3]);
            }
        }catch(Exception ignored){}
    }
    private void readShell(){
        for(String cmd:new String[]{"/system/bin/ip","/system/xbin/ip"}){
            if(!new java.io.File(cmd).canExecute())continue;
            Process p=null;
            try{
                p=new ProcessBuilder(cmd,"-4","neigh","show").redirectErrorStream(true).start();
                if(!p.waitFor(700,TimeUnit.MILLISECONDS)){p.destroyForcibly();continue;}
                try(BufferedReader r=new BufferedReader(new java.io.InputStreamReader(p.getInputStream()))){
                    String line;
                    while((line=r.readLine())!=null){
                        String[] bits=line.split("\\s+");
                        Matcher mac=MAC.matcher(line);
                        if(bits.length>0 && mac.find())put(bits[0],mac.group(1));
                    }
                }
            }catch(Exception ignored){}finally{if(p!=null)p.destroy();}
        }
    }

    /** Linux RTM_GETNEIGH read-only netlink dump. Some Android ROMs deny it. */
    private void readNetlink(){
        FileDescriptor fd=null;
        try{
            fd=Os.socket(OsConstants.AF_NETLINK,OsConstants.SOCK_DGRAM,OsConstants.NETLINK_ROUTE);
            Os.bind(fd,new NetlinkSocketAddress(0,0));
            Os.setsockoptTimeval(fd,OsConstants.SOL_SOCKET,OsConstants.SO_RCVTIMEO,
                    StructTimeval.fromMillis(350));
            ByteBuffer req=ByteBuffer.allocate(28).order(ByteOrder.nativeOrder());
            req.putInt(28).putShort((short)30).putShort((short)0x301);
            req.putInt(1234).putInt(0);
            req.put((byte)OsConstants.AF_INET);
            req.put(new byte[11]);
            byte[] query=req.array();
            Os.sendto(fd,query,0,query.length,0,new NetlinkSocketAddress(0,0));
            byte[] packet=new byte[16384];
            for(int attempt=0;attempt<9;attempt++){
                int size=Os.read(fd,packet,0,packet.length);
                if(size<=0)break;
                ByteBuffer buffer=ByteBuffer.wrap(packet,0,size).order(ByteOrder.nativeOrder());
                int pos=0;
                boolean finished=false;
                while(pos+16<=size){
                    int msgLength=buffer.getInt(pos);
                    int type=buffer.getShort(pos+4)&65535;
                    if(msgLength<16 || pos+msgLength>size)break;
                    if(type==3 || type==2){finished=true;break;}
                    if(type==28 && msgLength>=28 && (packet[pos+16]&255)==OsConstants.AF_INET){
                        int attr=pos+28,until=pos+msgLength;
                        String address="", mac="";
                        while(attr+4<=until){
                            int len=buffer.getShort(attr)&65535;
                            int kind=buffer.getShort(attr+2)&65535;
                            if(len<4 || attr+len>until)break;
                            if(kind==1 && len>=8){
                                address=(packet[attr+4]&255)+"."+(packet[attr+5]&255)+"."+
                                    (packet[attr+6]&255)+"."+(packet[attr+7]&255);
                            }else if(kind==2 && len>=10){
                                mac=String.format(Locale.US,"%02X:%02X:%02X:%02X:%02X:%02X",
                                    packet[attr+4]&255,packet[attr+5]&255,packet[attr+6]&255,
                                    packet[attr+7]&255,packet[attr+8]&255,packet[attr+9]&255);
                            }
                            attr+=(len+3)&~3;
                        }
                        if(!address.isEmpty())put(address,mac);
                    }
                    pos+=(msgLength+3)&~3;
                }
                if(finished)break;
            }
        }catch(Exception ignored){}finally{
            if(fd!=null)try{Os.close(fd);}catch(Exception ignored){}
        }
    }
}
