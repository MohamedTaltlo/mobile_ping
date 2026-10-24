package online.mtradius.mtping;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a bundled Android Nmap binary in unprivileged TCP-connect mode.
 * False means native Nmap was unavailable or could not execute;
 * the caller then uses the built-in TCP scanner.
 */
final class NmapEngine {
    private static final Pattern OPEN=Pattern.compile("^\\s*(\\d+)/tcp\\s+open\\s+(\\S+)",Pattern.CASE_INSENSITIVE);
    private static final Pattern DISCOVER=Pattern.compile("Discovered open port (\\d+)/tcp",Pattern.CASE_INSENSITIVE);
    private static final Pattern PERCENT=Pattern.compile("(\\d+(?:\\.\\d+)?)% done",Pattern.CASE_INSENSITIVE);
    private NmapEngine(){}

    static boolean run(Context ctx,String host,PortScanner.Stop stop,
                       PortScanner.Listener output) throws InterruptedException {
        File bin=new File(ctx.getApplicationInfo().nativeLibraryDir,"libnmap.so");
        if(!bin.isFile() || !bin.canExecute())return false;
        Process process=null;
        boolean gotReady=false;
        try {
            ProcessBuilder builder=new ProcessBuilder(bin.getAbsolutePath(),
                    "-sT","-Pn","-n","--open","-p","1-65535","-T4",
                    "--max-retries","0","--stats-every","2s","-v",host);
            builder.redirectErrorStream(true);
            builder.environment().put("NMAPDIR",ctx.getFilesDir().getAbsolutePath());
            process=builder.start();
            stop.process=process;
            output.message("جار فحص منافذ TCP المفتوحة...");
            Set<Integer> seen=new HashSet<>();
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(process.getInputStream(),"UTF-8"))){
                String line;
                while(!stop.isCancelled() && (line=reader.readLine())!=null) {
                    if(line.contains("Starting Nmap")||line.contains("Initiating Connect Scan"))gotReady=true;
                    if(line.toLowerCase(Locale.US).contains("failed to load")||
                       line.toLowerCase(Locale.US).contains("cannot open shared object")){
                        output.message("تعذّر تحميل محرك الفحص، جار استخدام الطريقة البديلة.");
                        return false;
                    }
                    Matcher current=OPEN.matcher(line);
                    if(current.find())report(current.group(1),current.group(2),seen,output);
                    Matcher discovered=DISCOVER.matcher(line);
                    if(discovered.find())report(discovered.group(1),"TCP",seen,output);
                    Matcher progress=PERCENT.matcher(line);
                    if(progress.find()){
                        double percentage=Double.parseDouble(progress.group(1));
                        output.progress((int)Math.min(65535,Math.round(percentage*65535/100d)),
                                65535,seen.size());
                    }
                }
            }
            while(!stop.isCancelled() && process.isAlive()){
                process.waitFor(100,TimeUnit.MILLISECONDS);
            }
            if(stop.isCancelled())throw new InterruptedException();
            int exit=process.exitValue();
            if(exit!=0 && !gotReady){
                output.message("محرك الفحص غير متوافق مع الجهاز؛ جار استخدام الفحص السريع.");
                return false;
            }
            if(exit!=0){
                output.message("توقف المحرك، جار استكمال فحص TCP.");
                return false;
            }
            output.progress(65535,65535,seen.size());
            output.message("اكتمل فحص المنافذ: "+seen.size()+" منفذ TCP مفتوح.");
            return true;
        } catch(java.io.IOException e) {
            output.message("تعذّر تشغيل محرك الفحص: "+e.getMessage());
            return false;
        } finally {
            if(process!=null && process.isAlive())process.destroyForcibly();
            stop.process=null;
        }
    }

    private static void report(String raw,String service,Set<Integer> seen,PortScanner.Listener output){
        try{
            int port=Integer.parseInt(raw);
            if(port>=1&&port<=65535&&seen.add(port)){
                output.found(port,service);
            }
        }catch(NumberFormatException ignored){}
    }
}
