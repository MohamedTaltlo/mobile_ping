package online.mtradius.mtping;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Full TCP connect scan, explicitly started by the user, bounded parallelism. */
final class PortScanner {
    interface Listener {
        void progress(int tested, int total, int open);
        void found(int port, String service);
        void message(String message);
    }
    static final class Stop {
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        void cancel() { cancelled.set(true); }
        boolean isCancelled() { return cancelled.get() || Thread.currentThread().isInterrupted(); }
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
    static void scan(String host, Stop stop, Listener listener) throws InterruptedException {
        if (!PingService.validTarget(host)) {
            listener.message("العنوان غير صالح.");
            return;
        }
        final InetAddress target;
        try {
            target = InetAddress.getByName(host);
        } catch (Exception e) {
            listener.message("تعذّر معرفة IP الهدف: " + e.getMessage());
            return;
        }
        final AtomicInteger next = new AtomicInteger(1);
        final AtomicInteger tested = new AtomicInteger();
        final AtomicInteger found = new AtomicInteger();
        final int workers = 48;
        final ExecutorService pool = Executors.newFixedThreadPool(workers);
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(workers);
        listener.message("جار فحص جميع منافذ TCP (1–65535) للهدف " + target.getHostAddress());
        try {
            for (int i = 0; i < workers; i++) {
                pool.submit(() -> {
                    try {
                        while (!stop.isCancelled()) {
                            int port = next.getAndIncrement();
                            if (port > 65535) break;
                            try (Socket socket = new Socket()) {
                                socket.connect(new InetSocketAddress(target, port), 400);
                                if (!stop.isCancelled()) {
                                    found.incrementAndGet();
                                    listener.found(port, service(port));
                                }
                            } catch (Exception ignored) {
                                // A closed/filtered port is simply not listed as open.
                            }
                            int count = tested.incrementAndGet();
                            if ((count & 127) == 0 || count == 65535) {
                                listener.progress(count, 65535, found.get());
                            }
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            while (done.getCount() > 0) {
                if (stop.isCancelled()) throw new InterruptedException();
                done.await(200, TimeUnit.MILLISECONDS);
            }
            listener.progress(tested.get(), 65535, found.get());
            listener.message(String.format(Locale.US,
                    "اكتمل فحص TCP • %d منفذ مفتوح من %d منفذاً تم اختبارها.",
                    found.get(), tested.get()));
        } catch (InterruptedException e) {
            stop.cancel();
            listener.message("تم إيقاف الفحص عند المنفذ المفحوص رقم " + tested.get() +
                    " • المنافذ المفتوحة المكتشفة: " + found.get());
            throw e;
        } finally {
            pool.shutdownNow();
        }
    }
}
