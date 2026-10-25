package online.mtradius.mtping;

import android.os.SystemClock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Single process in-memory live session. All state access is synchronized. */
final class PingState {
    static final PingState INSTANCE = new PingState();
    static final PingState SECOND = new PingState();
    static final PingState THIRD = new PingState();
    static PingState forSlot(int slot) {
        if(slot==0)return INSTANCE;
        if(slot==1)return SECOND;
        if(slot==2)return THIRD;
        throw new IllegalArgumentException("Invalid ping slot");
    }
    static PingState.Snapshot[] liveSnapshots() {
        return new PingState.Snapshot[]{INSTANCE.snapshot(),SECOND.snapshot(),THIRD.snapshot()};
    }
    private final ArrayDeque<PingSample> samples = new ArrayDeque<>();
    private final ArrayDeque<Outage> outages = new ArrayDeque<>();
    private String host = "10.0.0.1";
    private boolean running;
    private long startedAt;
    private long revision;
    private long sent, received, lost, errors;
    private final OutageTracker outage = new OutageTracker();
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
        final long startedAt, revision, sent, received, lost, errors, downMs, totalDownMs, downStartWall;
        final boolean down, verifyingLoss;
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
            this.downMs = s.outage.downMs(now);
            this.totalDownMs = s.outage.totalDownMs(now);
            this.down = s.outage.isDown();
            this.verifyingLoss = s.outage.verifyingLoss();
            this.downStartWall = s.outage.downStartWall();
            this.minRtt = s.received == 0 ? 0 : s.minRtt;
            this.maxRtt = s.maxRtt;
            this.avgRtt = s.received == 0 ? 0 : s.sumRtt / s.received;
            this.jitter = s.jitterSamples == 0 ? 0 : s.totalJitter / s.jitterSamples;
            this.samples = new ArrayList<>(s.samples);
            this.outages = new ArrayList<>(s.outages);
        }

        boolean isDown() {
            return down;
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
        outage.reset();
        minRtt = Double.POSITIVE_INFINITY;
        maxRtt = sumRtt = totalJitter = 0;
        lastRtt = -1;
        jitterSamples = 0;
        samples.clear();
        outages.clear();
        revision++;
    }

    synchronized void stop() {
        OutageTracker.Change end=outage.stop(SystemClock.elapsedRealtime());
        if(end.ended)addOutage(end.startWall,end.durationMs);
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
        } else if (item.kind == PingSample.Kind.TIMEOUT) {
            lost++;
            lastRtt = -1;
        } else {
            errors++;
            lastRtt = -1;
        }
        OutageTracker.Change change=outage.record(item.kind,
                SystemClock.elapsedRealtime(),System.currentTimeMillis());
        if(change.ended)addOutage(change.startWall,change.durationMs);
        samples.addLast(item);
        while (samples.size() > 240) samples.removeFirst();
        revision++;
    }

    synchronized Snapshot snapshot() { return new Snapshot(this); }
}
