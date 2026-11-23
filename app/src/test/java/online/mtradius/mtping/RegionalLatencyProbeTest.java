package online.mtradius.mtping;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public final class RegionalLatencyProbeTest {
    @Test public void interpolationAndMedian() {
        assertEquals(13.0,RegionalLatencyProbe.percentile(Arrays.asList(6d,13d,22d),0.5),.01);
        assertEquals(20.2,RegionalLatencyProbe.percentile(Arrays.asList(6d,13d,22d),0.9),.01);
        assertEquals(-1,RegionalLatencyProbe.percentile(java.util.Collections.<Double>emptyList(),0.9),0);
    }
    @Test public void jitterDoesNotInventInsufficientSamples(){
        assertEquals(-1,RegionalLatencyProbe.jitter(Arrays.asList(3d,4d)),0.001);
        assertEquals(3.5,RegionalLatencyProbe.jitter(Arrays.asList(9d,12d,8d)),0.001);
    }
    @Test public void loadedLatencyOnlyComparesSameEndpoint(){
        assertEquals(18,RegionalLatencyProbe.extraLatency(13,31),.0001);
        assertEquals(0,RegionalLatencyProbe.extraLatency(30,19),.0001);
        assertEquals(-1,RegionalLatencyProbe.extraLatency(13,-1),.0001);
    }
    @Test public void independentFastTargetOutweighsRateLimitedIcmp(){
        InternetQualityEngine.Report r=new InternetQualityEngine.Report();
        r.nearMs=8;r.nearJitter=2;r.googleMs=65;r.googleLoss=25;
        r.cloudflareMs=85;r.cloudflareLoss=37.5;
        r.dnsGoogleMs=31;r.httpsGoogleMs=240;
        InternetQualityScore.evaluate(r,false);
        assertTrue(r.score>=95);
        assertEquals(-1,r.lossPercent,0.001);
        assertEquals("اتصال ممتاز",r.verdict);
    }
}