package doom.sound;

import org.junit.jupiter.api.Test;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class BufferedSoundOutputTest {
    private static final class Device {
        int available;
        boolean closed;
        final ByteArrayOutputStream played = new ByteArrayOutputStream();
        final SourceDataLine line = (SourceDataLine) Proxy.newProxyInstance(
                SourceDataLine.class.getClassLoader(), new Class<?>[]{SourceDataLine.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "available": return available;
                        case "write":
                            int count = (int) args[2];
                            assertTrue(count <= available, "write would block on the audio device");
                            assertEquals(0, count % 4, "write must preserve stereo frames");
                            played.write((byte[]) args[0], (int) args[1], count);
                            available -= count;
                            return count;
                        case "close": closed = true; return null;
                        case "stop": case "flush": return null;
                        case "drain": fail("shutdown must not wait for device drain");
                        default: throw new AssertionError("Unexpected device call: " + method);
                    }
                });
    }

    @Test
    void fullDevicePreservesShortBurstThenPlaysItInOrder() {
        Device device = new Device();
        BufferedSoundOutput output = new BufferedSoundOutput(device.line, 16);
        output.submit(new byte[]{1,2,3,4,5,6,7,8});
        output.submit(new byte[]{9,10,11,12});
        assertEquals(0, device.played.size());
        device.available = 5;
        output.submit(new byte[0]);
        assertArrayEquals(new byte[]{1,2,3,4}, device.played.toByteArray());
        device.available = 16;
        output.submit(new byte[0]);
        assertArrayEquals(new byte[]{1,2,3,4,5,6,7,8,9,10,11,12}, device.played.toByteArray());
    }

    @Test
    void sustainedOverloadDiscardsOldestWholeFramesAndKeepsRecentSound() {
        Device device = new Device();
        BufferedSoundOutput output = new BufferedSoundOutput(device.line, 8);
        output.submit(new byte[]{1,2,3,4,5,6,7,8});
        output.submit(new byte[]{9,10,11,12});
        device.available = 8;
        output.submit(new byte[0]);
        assertArrayEquals(new byte[]{5,6,7,8,9,10,11,12}, device.played.toByteArray());
    }

    @Test
    void repeatedRingWrapsPreserveEveryFrameWhenDeviceKeepsUp() {
        Device device = new Device();
        BufferedSoundOutput output = new BufferedSoundOutput(device.line, 12);
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        for (int i = 0; i < 100; i++) {
            byte[] frame = {(byte)i, 0, (byte)i, 0};
            device.available = 4;
            output.submit(frame);
            expected.writeBytes(frame);
        }
        assertArrayEquals(expected.toByteArray(), device.played.toByteArray());
    }

    @Test
    void shutdownClosesStalledDeviceWithoutDraining() {
        Device device = new Device();
        BufferedSoundOutput output = new BufferedSoundOutput(device.line, 8);
        output.submit(new byte[8]);
        output.close();
        assertTrue(device.closed);
    }
}
