package online.mtradius.mtping;

import org.junit.Test;
import static org.junit.Assert.*;

public class MonitorTargetsTest {
    @Test public void oneTargetIsAllowed() {
        assertArrayEquals(new String[]{"10.0.0.1"},
                MonitorTargets.normalize("10.0.0.1", "", ""));
    }
    @Test public void upToThreeTargetsRunConcurrently() {
        assertArrayEquals(new String[]{"10.0.0.1","8.8.8.8","www.google.com"},
                MonitorTargets.normalize(" 10.0.0.1 ","8.8.8.8","www.google.com"));
    }
    @Test public void rejectsDuplicateTargetsCaseInsensitive() {
        try{
            MonitorTargets.normalize("www.Google.com","www.google.com");
            fail("Expected duplicate rejection");
        }catch(IllegalArgumentException expected){}
    }
    @Test public void rejectsMoreThanThree() {
        try{
            MonitorTargets.normalize("1.1.1.1","8.8.8.8","9.9.9.9","10.0.0.1");
            fail("Expected limit");
        }catch(IllegalArgumentException expected){}
    }
    @Test public void rejectsInvalidHostAndEmptySession() {
        assertFalse(MonitorTargets.valid(new String[]{"192.168.1.1","192.168.1.1"}));
        assertFalse(MonitorTargets.valid(new String[]{"192.168.1.1","not a host"}));
        assertFalse(MonitorTargets.valid(new String[]{}));
        assertTrue(MonitorTargets.valid(new String[]{"10.0.0.1","8.8.8.8"}));
    }
}
