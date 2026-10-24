package online.mtradius.mtping;

final class PingSample {
    enum Kind { REPLY, TIMEOUT, ERROR }
    final long sequence;
    final long wallTime;
    final Kind kind;
    final double rttMs;
    final int ttl;
    final String detail;

    PingSample(long sequence, long wallTime, Kind kind, double rttMs, int ttl, String detail) {
        this.sequence = sequence;
        this.wallTime = wallTime;
        this.kind = kind;
        this.rttMs = rttMs;
        this.ttl = ttl;
        this.detail = detail == null ? "" : detail;
    }
}
