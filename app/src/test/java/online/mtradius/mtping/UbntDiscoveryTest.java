package online.mtradius.mtping;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class UbntDiscoveryTest {
    private static void tlv(ByteArrayOutputStream b, int type, byte[] data) {
        b.write(type);
        b.write((data.length >>> 8) & 255);
        b.write(data.length & 255);
        b.write(data, 0, data.length);
    }

    @Test public void readsModelFirmwareMacAndUptimeFromActualPacket() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(1);b.write(0);b.write(0);b.write(0);
        tlv(b, 1, new byte[]{0x24, (byte)0xA4,0x3C,0x00,0x11,0x22});
        tlv(b, 12, "NBE-5AC-Gen2".getBytes(StandardCharsets.UTF_8));
        tlv(b, 3, "XW.v8.7.4".getBytes(StandardCharsets.UTF_8));
        tlv(b, 10, new byte[]{0,0,0x0E,0x10});
        byte[] payload=b.toByteArray();
        UbntDiscovery.Device item=UbntDiscovery.parse(payload,payload.length,"192.168.1.20");
        assertNotNull(item);
        assertEquals("192.168.1.20",item.address);
        assertEquals("24:A4:3C:00:11:22",item.mac);
        assertEquals("NBE-5AC-Gen2",item.model);
        assertEquals("XW.v8.7.4",item.firmware);
        assertEquals(3600,item.uptime);
    }

    @Test public void rejectsUnknownPacketsAndDoesNotGuessUptime() {
        assertNull(UbntDiscovery.parse(new byte[]{0,0,0,0},4,"192.168.1.2"));
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        b.write(2);b.write(0);b.write(0);b.write(0);
        tlv(b,12,"Unknown".getBytes(StandardCharsets.UTF_8));
        byte[] packet=b.toByteArray();
        UbntDiscovery.Device d=UbntDiscovery.parse(packet,packet.length,"192.168.1.1");
        assertNotNull(d);
        assertEquals(-1,d.uptime);
    }
}
