package online.mtradius.mtping;

import org.junit.Test;
import static org.junit.Assert.*;

public class PingRunnerTest {
    @Test public void successfulLinuxReplyShowsRttAndTtl() {
        PingSample sample = PingRunner.parse(312, 1000,
                "64 bytes from 10.0.0.1: icmp_seq=312 ttl=64 time=1.16 ms\n" +
                "1 packets transmitted, 1 received, 0% packet loss", 0);
        assertEquals(PingSample.Kind.REPLY, sample.kind);
        assertEquals(1.16, sample.rttMs, 0.00001);
        assertEquals(64, sample.ttl);
        assertEquals(312L, sample.sequence);
    }

    @Test public void timeoutIsPacketLoss() {
        PingSample sample = PingRunner.parse(12, 1000,
                "1 packets transmitted, 0 received, 100% packet loss", 1);
        assertEquals(PingSample.Kind.TIMEOUT, sample.kind);
    }

    @Test public void badHostIsNotPacketLoss() {
        PingSample sample = PingRunner.parse(1, 1000,
                "ping: bad address 'incorrect host'", 2);
        assertEquals(PingSample.Kind.ERROR, sample.kind);
    }

    @Test public void insufficientPermissionsIsProbeError() {
        PingSample sample = PingRunner.parse(1, 1000, "ping: permission denied", 2);
        assertEquals(PingSample.Kind.ERROR, sample.kind);
    }

    @Test public void googleDomainReply() {
        PingSample sample = PingRunner.parse(1, 1000,
                "64 bytes from 142.250.184.4: icmp_seq=1 ttl=116 time=19.7 ms", 0);
        assertEquals(PingSample.Kind.REPLY, sample.kind);
        assertEquals(19.7, sample.rttMs, 0.0001);
    }

    @Test public void commaDecimalReply() {
        PingSample sample = PingRunner.parse(1, 1000,
                "64 bytes from 8.8.8.8: icmp_seq=1 ttl=114 time=24,31 ms", 0);
        assertEquals(PingSample.Kind.REPLY, sample.kind);
        assertEquals(24.31, sample.rttMs, 0.0001);
    }
}
