package online.mtradius.mtping;

import org.junit.Test;
import static org.junit.Assert.*;

public final class InternetQualityScoreTest {
    @Test public void healthyQuickTestRatesHigh() {
        InternetQualityEngine.Report r=new InternetQualityEngine.Report();
        r.googleMs=20;r.cloudflareMs=18;r.lossPercent=0;r.jitterMs=2;
        r.dnsGoogleMs=15;r.dnsCloudflareMs=12;r.httpsGoogleMs=85;r.httpsCloudflareMs=90;
        InternetQualityScore.evaluate(r,false);
        assertEquals(100,r.score);assertEquals("اتصال ممتاز",r.verdict);
    }
    @Test public void missingIcmpDoesNotInventPacketLoss() {
        InternetQualityEngine.Report r=new InternetQualityEngine.Report();
        r.lossPercent=100;
        r.dnsGoogleMs=20;r.httpsGoogleMs=150;
        InternetQualityScore.evaluate(r,false);
        assertEquals(-1,r.lossPercent,0.001);
        assertEquals(100,r.score);
        assertTrue(r.diagnosis.contains("ICMP"));
    }
    @Test public void unavailableNetworkIsNotRatedHealthy() {
        InternetQualityEngine.Report r=new InternetQualityEngine.Report();
        InternetQualityScore.evaluate(r,false);
        assertEquals(0,r.score);
        assertEquals("تعذّر التقييم",r.verdict);
    }
    @Test public void slowAndLossyLineRatesPoorly() {
        InternetQualityEngine.Report r=new InternetQualityEngine.Report();
        r.googleMs=250;r.lossPercent=14;r.jitterMs=70;
        r.dnsGoogleMs=400;r.httpsGoogleMs=1600;
        r.downloadMbps=0.5;r.uploadMbps=0.2;
        InternetQualityScore.evaluate(r,true);
        assertTrue(r.score<40);
        assertFalse(r.notes.isEmpty());
    }
    @Test public void bufferbloatGradesTrackExtraDelay() {
        assertEquals("A+", InternetQualityScore.grade(2,5));
        assertEquals("A", InternetQualityScore.grade(11,8));
        assertEquals("B", InternetQualityScore.grade(18,30));
        assertEquals("C", InternetQualityScore.grade(34,45));
        assertEquals("D", InternetQualityScore.grade(80,50));
        assertEquals("F", InternetQualityScore.grade(170,9));
        assertEquals("—", InternetQualityScore.grade(-1,9));
    }
    @Test public void excessiveLoadedLatencyLowersScore() {
        InternetQualityEngine.Report fast=new InternetQualityEngine.Report();
        fast.googleMs=16;fast.lossPercent=0;fast.jitterMs=2;
        fast.dnsGoogleMs=20;fast.httpsGoogleMs=80;
        fast.downloadMbps=75;fast.uploadMbps=20;fast.downDelta=2;fast.upDelta=6;
        InternetQualityScore.evaluate(fast,true);
        InternetQualityEngine.Report busy=new InternetQualityEngine.Report();
        busy.googleMs=16;busy.lossPercent=0;busy.jitterMs=2;
        busy.dnsGoogleMs=20;busy.httpsGoogleMs=80;
        busy.downloadMbps=75;busy.uploadMbps=20;busy.downDelta=140;busy.upDelta=210;
        InternetQualityScore.evaluate(busy,true);
        assertEquals("A",fast.bufferGrade);
        assertEquals("F",busy.bufferGrade);
        assertTrue(busy.score < fast.score - 15);
        assertTrue(busy.diagnosis.contains("Bufferbloat"));
    }
    @Test public void highP95InBothDirectionsIsNotAutomaticallyExcellent() {
        InternetQualityEngine.Report r=new InternetQualityEngine.Report();
        r.nearMs=30;r.nearJitter=2;r.dnsGoogleMs=22;r.httpsGoogleMs=105;
        r.downloadMbps=50;r.uploadMbps=8;
        r.idleMs=44;r.downDelta=0;r.upDelta=2;
        r.downP95=1074;r.upP95=1046;r.downSamples=12;r.upSamples=11;
        InternetQualityScore.evaluate(r,true);
        assertEquals("C",r.bufferGrade);
        assertTrue(r.diagnosis.contains("P95"));
        assertTrue(r.score<90);
    }
    @Test public void oneHandshakeSpikeWithTooFewSamplesDoesNotChangeGrade() {
        assertEquals("A+",InternetQualityScore.gradeWithTail(0,2,1100,1080,45,5,6));
        assertEquals("B",InternetQualityScore.gradeWithTail(0,2,1100,48,45,14,14));
        assertEquals("A+",InternetQualityScore.gradeWithTail(0,2,50,52,45,14,14));
    }

}
