package online.mtradius.mtping;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Uses the operating system's ICMP ping binary, not TCP reachability. */
final class PingRunner {
    private static final Pattern RTT = Pattern.compile("\\btime\\s*[=<]\\s*(\\d+(?:[.,]\\d+)?)\\s*ms\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TTL = Pattern.compile("\\bttl\\s*=\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private PingRunner() {}

    static PingSample.Kind classify(String output, int exitCode) {
        String lower = output.toLowerCase(Locale.US);
        if (RTT.matcher(output).find() && lower.contains("bytes from")) {
            return PingSample.Kind.REPLY;
        }
        if (lower.contains("unknown host") || lower.contains("bad address") ||
                lower.contains("name or service not known") ||
                lower.contains("temporary failure in name resolution") ||
                lower.contains("permission denied") ||
                lower.contains("operation not permitted") ||
                lower.contains("invalid option") ||
                lower.contains("usage:") ||
                lower.contains("not found") ||
                lower.contains("couldn't resolve") ||
                lower.contains("cannot resolve")) {
            return PingSample.Kind.ERROR;
        }
        if (exitCode == 2 && !lower.contains("packet loss") && !lower.contains("unreachable")) {
            return PingSample.Kind.ERROR;
        }
        return PingSample.Kind.TIMEOUT;
    }

    static PingSample parse(long seq, long wall, String output, int code) {
        PingSample.Kind kind = classify(output, code);
        if (kind == PingSample.Kind.REPLY) {
            Matcher time = RTT.matcher(output);
            Matcher ttl = TTL.matcher(output);
            double ms = time.find() ? Double.parseDouble(time.group(1).replace(',', '.')) : -1;
            int t = ttl.find() ? Integer.parseInt(ttl.group(1)) : -1;
            return new PingSample(seq, wall, kind, ms, t, "");
        }
        String detail = "";
        if (kind == PingSample.Kind.ERROR) {
            String[] lines = output.trim().split("\\r?\\n");
            for (String line : lines) {
                if (line.toLowerCase(Locale.US).contains("ping:") || line.toLowerCase(Locale.US).contains("error")) {
                    detail = line.trim();
                    break;
                }
            }
            if (detail.length() == 0) detail = "ICMP command failed (exit " + code + ")";
            if (detail.length() > 120) detail = detail.substring(0, 120);
        }
        return new PingSample(seq, wall, kind, -1, -1, detail);
    }

    static PingSample probe(String host, long seq) throws InterruptedException {
        Process process = null;
        long timestamp = System.currentTimeMillis();
        try {
            process = new ProcessBuilder("/system/bin/ping", "-n", "-c", "1", "-W", "1", host)
                    .redirectErrorStream(true).start();
            boolean finished = process.waitFor(2200, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(300, TimeUnit.MILLISECONDS);
                return new PingSample(seq, timestamp, PingSample.Kind.TIMEOUT, -1, -1, "");
            }
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && text.length() < 8192) {
                    text.append(line).append('\n');
                }
            }
            return parse(seq, timestamp, text.toString(), process.exitValue());
        } catch (java.io.IOException e) {
            return new PingSample(seq, timestamp, PingSample.Kind.ERROR, -1, -1,
                    "System ping unavailable: " + e.getMessage());
        } finally {
            if (process != null) process.destroy();
        }
    }
}
