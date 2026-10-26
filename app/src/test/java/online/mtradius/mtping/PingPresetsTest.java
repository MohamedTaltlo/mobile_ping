package online.mtradius.mtping;

import org.junit.Test;
import static org.junit.Assert.*;

/** Customizable quick targets survive between sessions via stable keys. */
public class PingPresetsTest {
    @Test public void defaultsRemainAvailableWhenNoPreferenceWasStored(){
        assertEquals("10.0.0.1",PingPresets.defaultAt(0));
        assertEquals("8.8.8.8",PingPresets.defaultAt(1));
        assertEquals("www.google.com",PingPresets.defaultAt(2));
    }
    @Test public void eachButtonUsesAnIndependentPermanentKey(){
        assertEquals("preset_0",PingPresets.keyAt(0));
        assertEquals("preset_1",PingPresets.keyAt(1));
        assertEquals("preset_2",PingPresets.keyAt(2));
    }
    @Test public void editedAddressCanBeDomainOrIpAndIsTrimmed(){
        assertEquals("192.168.88.1",PingPresets.validate("  192.168.88.1  "));
        assertEquals("router.my-network.net",PingPresets.validate("router.my-network.net"));
    }
    @Test public void invalidPresetCannotBeSaved(){
        for(String input:new String[]{"","  ","abc def","https://example.com","abc/def"}){
            try{PingPresets.validate(input);fail("Accepted: "+input);}
            catch(IllegalArgumentException expected){}
        }
    }
    @Test public void lastFocusedHostControlsWherePresetIsInserted(){
        assertEquals(0,PingPresets.fieldIndex(0));
        assertEquals(1,PingPresets.fieldIndex(1));
        assertEquals(2,PingPresets.fieldIndex(2));
        assertEquals(0,PingPresets.fieldIndex(-1));
        assertEquals(0,PingPresets.fieldIndex(3));
    }
}
