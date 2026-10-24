package online.mtradius.mtping;

import java.io.BufferedReader;
import java.io.FileReader;
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
        readToyboxArp();
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

    private void readToyboxArp() {
        String[] binaries={"/system/bin/arp","/system/xbin/arp"};
        for(String exe:binaries){
            if(!new java.io.File(exe).canExecute())continue;
            Process p=null;
            try{
                p=new ProcessBuilder(exe,"-an").redirectErrorStream(true).start();
                if(!p.waitFor(650,TimeUnit.MILLISECONDS)){
                    p.destroyForcibly();continue;
                }
                try(BufferedReader r=new BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()))){
                    String line;
                    Pattern ipPattern=Pattern.compile(
                            "(?:\\\\d{1,3}\\\\.){3}\\\\d{1,3}");
                    while((line=r.readLine())!=null){
                        Matcher ip=ipPattern.matcher(line);
                        Matcher mac=MAC.matcher(line);
                        if(ip.find()&&mac.find())put(ip.group(),mac.group(1));
                    }
                }
            }catch(Exception ignored){}finally{if(p!=null)p.destroy();}
        }
    }
}
