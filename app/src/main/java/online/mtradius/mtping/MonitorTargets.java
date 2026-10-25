package online.mtradius.mtping;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Validation for up to three concurrent Ping targets. */
final class MonitorTargets {
    private MonitorTargets(){}
    static String[] normalize(String... inputs){
        if(inputs==null)throw new IllegalArgumentException("أدخل عنواناً واحداً على الأقل");
        List<String> targets=new ArrayList<>();
        for(String input:inputs){
            String value=input==null?"":input.trim();
            if(value.isEmpty())continue;
            if(!validHost(value))throw new IllegalArgumentException("عنوان غير صالح: "+value);
            for(String used:targets)
                if(used.equalsIgnoreCase(value))
                    throw new IllegalArgumentException("لا تكرر نفس العنوان: "+value);
            targets.add(value);
            if(targets.size()>3)throw new IllegalArgumentException("الحد الأعلى 3 عناوين");
        }
        if(targets.isEmpty())throw new IllegalArgumentException("أدخل عنواناً واحداً على الأقل");
        return targets.toArray(new String[0]);
    }
    static boolean valid(String[] hosts){
        if(hosts==null||hosts.length<1||hosts.length>3)return false;
        try{
            return normalize(hosts).length==hosts.length;
        }catch(IllegalArgumentException e){return false;}
    }
    static boolean validHost(String host){
        if(host==null||host.length()<1||host.length()>253)return false;
        if(!host.matches("[A-Za-z0-9][A-Za-z0-9.\\-]{0,252}"))return false;
        return !host.endsWith(".")&&!host.contains("..");
    }
}
