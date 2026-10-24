package online.mtradius.mtping;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class PingLog {
    private static final byte[] HEADER = "timestamp_ms,host,sequence,status,rtt_ms,ttl,detail\n".getBytes(StandardCharsets.UTF_8);
    private final File current, old;

    PingLog(Context c) {
        File dir = c.getFilesDir();
        current = new File(dir, "mt_ping.csv");
        old = new File(dir, "mt_ping_previous.csv");
    }

    private static String quote(String s) {
        if (s == null) s = "";
        return "\"" + s.replace("\"", "\"\"").replace("\n", " ").replace("\r", " ") + "\"";
    }

    synchronized void append(String host, PingSample item) {
        try {
            if (current.exists() && current.length() >= 5L * 1024L * 1024L) {
                if (old.exists()) old.delete();
                if (!current.renameTo(old)) throw new IOException("Could not rotate CSV");
            }
            boolean empty = !current.exists() || current.length() == 0;
            try (FileOutputStream out = new FileOutputStream(current, true)) {
                if (empty) out.write(HEADER);
                String row = String.format(Locale.US, "%d,%s,%d,%s,%s,%d,%s\n",
                        item.wallTime, quote(host), item.sequence, item.kind.name(),
                        item.kind == PingSample.Kind.REPLY ? String.format(Locale.US, "%.3f", item.rttMs) : "",
                        item.ttl, quote(item.detail));
                out.write(row.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // A storage failure must never stop the ICMP worker.
        }
    }

    void exportTo(OutputStream out) throws IOException {
        if (old.exists()) copy(old, out);
        if (current.exists()) copy(current, out);
        if (!old.exists() && !current.exists()) out.write(HEADER);
        out.flush();
    }

    private void copy(File file, OutputStream to) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[8192];
            int count;
            while ((count = in.read(bytes)) != -1) to.write(bytes, 0, count);
        }
    }
}
