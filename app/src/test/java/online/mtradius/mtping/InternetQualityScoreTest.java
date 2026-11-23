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
}
