package online.mtradius.mtping;

import org.junit.Test;
import static org.junit.Assert.*;

/** Prevent regressions that turn one missed pulse into a long false outage. */
public class OutageTrackerTest {
    private static final PingSample.Kind OK=PingSample.Kind.REPLY;
    private static final PingSample.Kind LOST=PingSample.Kind.TIMEOUT;
    private static final PingSample.Kind ERROR=PingSample.Kind.ERROR;

    @Test public void oneLostPacketFollowedByReplyIsNotAnOutage() {
        OutageTracker clock=new OutageTracker();
        OutageTracker.Change first=clock.record(LOST,1000,10000);
        assertFalse(first.started);
        assertFalse(clock.isDown());
        assertTrue(clock.verifyingLoss());
        OutageTracker.Change restored=clock.record(OK,1200,10200);
        assertFalse(restored.ended);
        assertFalse(clock.isDown());
        assertFalse(clock.verifyingLoss());
        assertEquals(0,clock.totalDownMs(9000));
    }

    @Test public void twoConsecutiveLossesStartAtFirstFailedProbe() {
        OutageTracker clock=new OutageTracker();
        clock.record(LOST,1000,11000);
        OutageTracker.Change start=clock.record(LOST,2000,12000);
        assertTrue(start.started);
        assertEquals(11000,start.startWall);
        assertTrue(clock.isDown());
        assertEquals(1000,clock.downMs(2000));
        OutageTracker.Change recovery=clock.record(OK,2100,12100);
        assertTrue(recovery.ended);
        assertEquals(1100,recovery.durationMs);
        assertEquals(1100,clock.totalDownMs(12000));
        assertEquals(0,clock.downMs(12000));
    }

    @Test public void isolatedLossThenLongIdleGapDoesNotInventOutage() {
        OutageTracker clock=new OutageTracker();
        clock.record(LOST,1000,11000);
        clock.record(OK,8000,18000);
        assertFalse(clock.isDown());
        assertEquals(0,clock.totalDownMs(8000));
    }

    @Test public void errorDoesNotCauseOutage() {
        OutageTracker clock=new OutageTracker();
        clock.record(LOST,1000,11000);
        assertFalse(clock.record(ERROR,1200,11200).ended);
        assertFalse(clock.record(LOST,1400,11400).started);
        assertFalse(clock.isDown());
    }

    @Test public void confirmedOutageEndsWhenProbeCommandFails() {
        OutageTracker clock=new OutageTracker();
        clock.record(LOST,1000,11000);
        clock.record(LOST,2000,12000);
        OutageTracker.Change end=clock.record(ERROR,3000,13000);
        assertTrue(end.ended);
        assertEquals(2000,end.durationMs);
        assertFalse(clock.isDown());
    }

    @Test public void multipleOutagesDoNotCarryDurationIntoNextOne() {
        OutageTracker clock=new OutageTracker();
        clock.record(LOST,1000,10000);
        clock.record(LOST,1200,10200);
        assertEquals(300,clock.record(OK,1300,10300).durationMs);
        clock.record(LOST,2000,11000);
        clock.record(LOST,2200,11200);
        assertEquals(300,clock.record(OK,2300,11300).durationMs);
        assertEquals(600,clock.totalDownMs(5000));
        clock.reset();
        assertEquals(0,clock.totalDownMs(5000));
    }

    @Test public void stopClosesOnePendingConfirmedOutage() {
        OutageTracker clock=new OutageTracker();
        clock.record(LOST,1000,11000);
        clock.record(LOST,2000,12000);
        OutageTracker.Change end=clock.stop(2600);
        assertTrue(end.ended);
        assertEquals(1600,end.durationMs);
        assertFalse(clock.isDown());
        assertFalse(clock.stop(3000).ended);
    }
}
