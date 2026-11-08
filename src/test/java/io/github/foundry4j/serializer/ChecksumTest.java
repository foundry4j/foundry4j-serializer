package io.github.foundry4j.serializer;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChecksumTest {

    record Payload(String name, int value) {
    }

    @Test
    void scalarAndSimdImplementationsAgree() {
        Checksum scalar = new ScalarChecksum();
        Checksum simd = new SimdChecksum();
        byte[] data = new byte[512];
        new Random(42).nextBytes(data);
        int[] offsets = {0, 1, 2, 3, 5, 7, 8, 9, 15, 16, 17, 31, 32, 33, 63, 64, 65, 100, 127};

        for (int length = 0; length <= 96; length++) {
            for (int offset : offsets) {
                if (offset + length <= data.length) {
                    assertEquals(scalar.hash(data, offset, length), simd.hash(data, offset, length),
                            "offset=" + offset + ", length=" + length);
                }
            }
        }
        assertEquals(scalar.hash(new byte[0], 0, 0), simd.hash(new byte[0], 0, 0));
    }

    @Test
    void factoryUsesSimdWhenVectorModuleIsPresent() {
        Assumptions.assumeTrue(
                ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent());
        assertInstanceOf(SimdChecksum.class, Checksums.create());
    }

    @Test
    void checksummedPayloadsRoundTrip() {
        Serializer serializer = Serializer.builder().checksum(true).build();
        Payload payload = new Payload("hello", 42);
        byte[] bytes = serializer.serialize(payload, Payload.class);

        assertTrue((bytes[0] & 0x80) != 0);
        assertEquals(payload, serializer.deserialize(bytes, Payload.class));
        assertEquals(payload, Serializer.create().deserialize(bytes, Payload.class));
    }

    @Test
    void checksummedNullRoundTrips() {
        Serializer serializer = Serializer.builder().checksum(true).build();
        byte[] bytes = serializer.serialize(null, Payload.class);
        assertNull(serializer.deserialize(bytes, Payload.class));
    }

    @Test
    void corruptedPayloadFailsVerification() {
        Serializer serializer = Serializer.builder().checksum(true).build();
        byte[] bytes = serializer.serialize(new Payload("hello", 42), Payload.class);
        bytes[2] ^= 0x01;
        assertThrows(SerializationException.class,
                () -> serializer.deserialize(bytes, Payload.class));
    }

    @Test
    void corruptedChecksumFailsVerification() {
        Serializer serializer = Serializer.builder().checksum(true).build();
        byte[] bytes = serializer.serialize(new Payload("hello", 42), Payload.class);
        bytes[bytes.length - 1] ^= 0x01;
        assertThrows(SerializationException.class,
                () -> serializer.deserialize(bytes, Payload.class));
    }

    @Test
    void largePayloadsExerciseTheVectorLoop() {
        Serializer serializer = Serializer.builder().checksum(true).build();
        byte[] value = new byte[100_000];
        new Random(7).nextBytes(value);
        byte[] bytes = serializer.serialize(value, byte[].class);
        byte[] decoded = serializer.deserialize(bytes, byte[].class);
        assertEquals(value.length, decoded.length);
        for (int index = 0; index < value.length; index++) {
            assertEquals(value[index], decoded[index]);
        }
    }

    @Test
    void malformedHeadersAreRejected() {
        Serializer serializer = Serializer.create();
        assertThrows(SerializationException.class, () -> serializer.deserialize(new byte[0],
                Payload.class));
        assertThrows(SerializationException.class, () -> serializer.deserialize(new byte[1],
                Payload.class));
        assertThrows(SerializationException.class,
                () -> serializer.deserialize(new byte[]{(byte) 0x81, 0}, Payload.class));
        assertThrows(SerializationException.class,
                () -> serializer.deserialize(new byte[]{2, 0}, Payload.class));
        assertThrows(SerializationException.class,
                () -> serializer.deserialize(new byte[]{1, 2}, Payload.class));
        assertThrows(SerializationException.class,
                () -> serializer.deserialize(new byte[]{1, 0, 99}, Payload.class));
    }
}
