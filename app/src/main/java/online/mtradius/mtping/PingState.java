package online.mtradius.mtping;

import android.os.SystemClock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Single process in-memory live session. All state access is synchronized. */
final class PingState {
    static final PingState INSTANCE = new PingState();
    private final ArrayDeque<PingSample> samples = new ArrayDeque<>();
    private final ArrayDeque<Outage> outages = new ArrayDeque<>();
    private String host = "10.0.0.1";
    private boolean running;
    private long startedAt;
    private long revision;
    private long sent, received, lost, errors;
    private long downSince = -1;
    private long completedDownMillis;
    private double minRtt = Double.POSITIVE_INFINITY, maxRtt, sumRtt;
    private double lastRtt = -1;
    private double totalJitter;
    private long jitterSamples;

    static final class Outage {
        final long startWall, durationMs;
        Outage(long startWall, long durationMs) {
            this.startWall = startWall;
            this.durationMs = durationMs;
        }
    }

    static final class Snapshot {
        final String host;
        final boolean running;
        final long startedAt, revision, sent, received, lost, errors, downMs, totalDownMs;
        final double minRtt, maxRtt, avgRtt, jitter;
        final List<PingSample> samples;
        final List<Outage> outages;

        Snapshot(PingState s) {
            this.host = s.host;
            this.running = s.running;
            this.startedAt = s.startedAt;
            this.revision = s.revision;
            this.sent = s.sent;
            this.received = s.received;
            this.lost = s.lost;
            this.errors = s.errors;
            long now = SystemClock.elapsedRealtime();
            this.downMs = s.downSince >= 0 ? Math.max(0, now - s.downSince) : 0;
            this.totalDownMs = s.completedDownMillis + this.downMs;
            this.minRtt = s.received == 0 ? 0 : s.minRtt;
            this.maxRtt = s.maxRtt;
            this.avgRtt = s.received == 0 ? 0 : s.sumRtt / s.received;
            this.jitter = s.jitterSamples == 0 ? 0 : s.totalJitter / s.jitterSamples;
            this.samples = new ArrayList<>(s.samples);
            this.outages = new ArrayList<>(s.outages);
        }

        boolean isDown() {
            return !samples.isEmpty() && samples.get(samples.size() - 1).kind == PingSample.Kind.TIMEOUT;
        }

        PingSample last() {
            return samples.isEmpty() ? null : samples.get(samples.size() - 1);
        }
    }

    synchronized void start(String target) {
        host = target;
        running = true;
        startedAt = SystemClock.elapsedRealtime();
        sent = received = lost = errors = 0;
        downSince = -1;
        completedDownMillis = 0;
        minRtt = Double.POSITIVE_INFINITY;
        maxRtt = sumRtt = totalJitter = 0;
        lastRtt = -1;
        jitterSamples = 0;
        samples.clear();
        outages.clear();
        revision++;
    }

    synchronized void stop() {
        if (downSince >= 0) {
            long duration = Math.max(0, SystemClock.elapsedRealtime() - downSince);
            completedDownMillis += duration;
            addOutage(System.currentTimeMillis() - duration, duration);
            downSince = -1;
        }
        running = false;
        revision++;
    }

    private void addOutage(long startWall, long duration) {
        outages.addLast(new Outage(startWall, duration));
        while (outages.size() > 30) outages.removeFirst();
    }

    synchronized void record(PingSample item) {
        if (!running) return;
        sent++;
        long now = SystemClock.elapsedRealtime();
        if (item.kind == PingSample.Kind.REPLY) {
            received++;
            minRtt = Math.min(minRtt, item.rttMs);
            maxRtt = Math.max(maxRtt, item.rttMs);
            sumRtt += item.rttMs;
            if (lastRtt >= 0) {
                totalJitter += Math.abs(lastRtt - item.rttMs);
                jitterSamples++;
            }
            lastRtt = item.rttMs;
            if (downSince >= 0) {
                long duration = Math.max(0, now - downSince);
                completedDownMillis += duration;
                addOutage(item.wallTime - duration, duration);
                downSince = -1;
            }
        } else if (item.kind == PingSample.Kind.TIMEOUT) {
            lost++;
            lastRtt = -1;
            if (downSince < 0) downSince = now;
        } else {
            errors++;
            lastRtt = -1;
            // A local probe failure is not evidence of network loss.
            // Close any previous outage rather than showing an incorrect running timer.
            if (downSince >= 0) {
                long duration = Math.max(0, now - downSince);
                completedDownMillis += duration;
                addOutage(item.wallTime - duration, duration);
                downSince = -1;
            }
        }
        samples.addLast(item);
        while (samples.size() > 240) samples.removeFirst();
        revision++;
    }

    synchronized Snapshot snapshot() { return new Snapshot(this); }
}
