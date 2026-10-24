package online.mtradius.mtping;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Read-only mobile diagnostics; local sweep never leaves the Wi-Fi LAN. */
final class NetworkTools {
    interface Output { void line(String text); }
    interface DeviceFound { void found(String ip, String mac); }
    static final class Connection {
        final String localIp, gateway, transport;
        final int prefix;
        Connection(String ip, String gw, String t, int p) {
            localIp = ip; gateway = gw; transport = t; prefix = p;
        }
        boolean wifiLan() { return !localIp.isEmpty() &&
                ("Wi-Fi".equals(transport) || "Ethernet".equals(transport)); }
    }

    private NetworkTools() { }

    static Connection connection(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = manager.getActiveNetwork();
        if (network == null) return new Connection("", "", "غير متصل", 0);
        NetworkCapabilities caps = manager.getNetworkCapabilities(network);
        String type = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi" :
                caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet" :
                caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "بيانات الهاتف" : "غير معروف";
        LinkProperties props = manager.getLinkProperties(network);
        if (props == null) return new Connection("", "", type, 0);
        String local = "", gateway = "";
        int prefix = 0;
        for (LinkAddress addr : props.getLinkAddresses()) {
            if (addr.getAddress() instanceof Inet4Address && !addr.getAddress().isLoopbackAddress()) {
                local = addr.getAddress().getHostAddress();
                prefix = addr.getPrefixLength(); break;
            }
        }
        for (RouteInfo route : props.getRoutes()) {
            if (route.isDefaultRoute() && route.getGateway() instanceof Inet4Address &&
                    !route.getGateway().isAnyLocalAddress()) {
                gateway = route.getGateway().getHostAddress(); break;
            }
        }
        return new Connection(local, gateway, type, prefix);
    }

    static String getPublicIp() throws Exception {
        HttpURLConnection request = (HttpURLConnection) new URL("https://api.ipify.org").openConnection();
        request.setConnectTimeout(5000);
        request.setReadTimeout(5000);
        request.setRequestProperty("Accept", "text/plain");
        try {
            if (request.getResponseCode() != 200) throw new java.io.IOException("HTTP " + request.getResponseCode());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(request.getInputStream(), "UTF-8"))) {
                String ip = reader.readLine();
                if (ip == null || !ip.matches("[0-9a-fA-F:.]{3,48}")) throw new java.io.IOException("رد غير صالح");
                return ip;
            }
        } finally { request.disconnect(); }
    }

    static String run(String[] args, long timeoutMillis) throws InterruptedException {
        Process p = null;
        try {
            p = new ProcessBuilder(args).redirectErrorStream(true).start();
            if (!p.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return "انتهت مهلة الأمر";
            }
            StringBuilder b = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null && b.length() < 6000) b.append(line).append('\n');
            }
            return b.toString().trim();
        } catch (java.io.IOException ex) {
            return "تعذّر تشغيل الأمر: " + ex.getMessage();
        } finally {
            if (p != null) p.destroy();
        }
    }

    static void traceroute(String host, Output output) throws InterruptedException {
        if (!PingService.validTarget(host)) {
            output.line("العنوان غير صالح"); return;
        }
        // Prefer the system command; no superuser permissions or external binary downloads.
        String[] candidates = {"/system/bin/traceroute", "/system/xbin/traceroute"};
        for (String binary : candidates) {
            if (new java.io.File(binary).canExecute()) {
                output.line("Traceroute: " + host);
                output.line(run(new String[]{binary, "-n", "-m", "16", "-w", "1", host}, 45000));
                return;
            }
        }
        // Android's ping supports IP TTL (-t) on many, but not all, ROMs.
        output.line("Traceroute باستخدام ICMP TTL • بحد أقصى 16 قفزة");
        for (int ttl = 1; ttl <= 16 && !Thread.currentThread().isInterrupted(); ttl++) {
            String message = run(new String[]{"/system/bin/ping", "-n", "-c", "1",
                    "-W", "1", "-t", Integer.toString(ttl), host}, 2500);
            String router = "";
            java.util.regex.Matcher match = java.util.regex.Pattern.compile(
                    "(?:from|From)\\s+([0-9]{1,3}(?:\\.[0-9]{1,3}){3})").matcher(message);
            if (match.find()) router = match.group(1);
            boolean arrived = message.contains("bytes from") || message.contains("bytes from ");
            if (message.toLowerCase(Locale.US).contains("invalid option") ||
                    message.toLowerCase(Locale.US).contains("not permitted") ||
                    message.toLowerCase(Locale.US).contains("usage:")) {
                output.line("جهازك لا يدعم تغيير TTL في ping؛ لا يمكن تنفيذ Traceroute بدون الصلاحية المناسبة.");
                return;
            }
            output.line(String.format(Locale.US, "%02d  %s", ttl,
                    router.isEmpty() ? "*  لا توجد استجابة" : router));
            if (arrived) { output.line("تم الوصول للهدف."); return; }
        }
    }

    static String localArpMac(String ip) {
        // Android 10+ often blocks /proc/net/arp; return unknown, never invent a MAC.
        try (BufferedReader r = new BufferedReader(new java.io.FileReader("/proc/net/arp"))) {
            String row;
            while ((row = r.readLine()) != null) {
                String[] tokens = row.trim().split("\\s+");
                if (tokens.length > 3 && tokens[0].equals(ip) &&
                        tokens[3].matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}") &&
                        !tokens[3].equals("00:00:00:00:00:00")) return tokens[3].toUpperCase(Locale.US);
            }
        } catch (Exception ignored) { }
        return "غير متاح";
    }

    static void scanLan(Connection net, Output info, DeviceFound result) throws InterruptedException {
        if (!net.wifiLan()) {
            info.line("اتصل بشبكة Wi-Fi أو Ethernet أولاً."); return;
        }
        String[] octets = net.localIp.split("\\.");
        if (octets.length != 4) { info.line("يتطلب الفحص عنوان IPv4."); return; }
        String network = octets[0] + "." + octets[1] + "." + octets[2] + ".";
        if (net.prefix < 24) {
            info.line("الشبكة واسعة؛ سيُفحص نطاق /24 المحيط بهاتفك فقط حفاظاً على سرعة وسلامة الفحص.");
        } else if (net.prefix > 24) {
            info.line("سيقتصر الفحص على عناوين الشبكة المحلية الفعلية.");
        }
        info.line("بدء فحص الأجهزة القابلة للاستجابة في " + network + "0/24");
        final int own = Integer.parseInt(octets[3]);
        final int gwLast = net.gateway.startsWith(network) ?
                Integer.parseInt(net.gateway.substring(network.length())) : -1;
        int start = 1, end = 254;
        if (net.prefix > 24 && net.prefix <= 30) {
            int block = 1 << (32 - net.prefix);
            start = (own / block) * block + 1;
            end = Math.min(254, ((own / block) + 1) * block - 2);
        }
        ExecutorService pool = Executors.newFixedThreadPool(12);
        List<Future<?>> futures = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger online = new java.util.concurrent.atomic.AtomicInteger();
        try {
            for (int i = start; i <= end; i++) {
                final int last = i;
                if (last == own) continue;
                futures.add(pool.submit((Runnable) () -> {
                    try {
                        String ip = network + last;
                        String command = run(new String[]{"/system/bin/ping", "-n", "-c", "1",
                                "-W", "1", ip}, 1800);
                        if (command.contains("bytes from")) {
                            online.incrementAndGet();
                            result.found(ip, localArpMac(ip));
                        }
                    } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }));
            }
            for (Future<?> task : futures) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                try { task.get(); } catch (java.util.concurrent.ExecutionException ignored) { }
            }
        } finally { pool.shutdownNow(); }
        info.line("انتهى الفحص • الأجهزة المستجيبة: " + online.get() +
                " • الأجهزة التي تمنع Ping قد لا تظهر.");
    }
}
