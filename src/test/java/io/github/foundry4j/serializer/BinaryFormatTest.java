package io.github.foundry4j.serializer;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinaryFormatTest {

    @Test
    void varIntRoundTrip() {
        int[] values = {0, 1, 2, 127, 128, 255, 256, 16_383, 16_384, 1_000_000, Integer.MAX_VALUE};
        BinaryWriter out = new BinaryWriter();
        for (int value : values) {
            out.writeVarInt(value);
        }
        BinaryReader in = new BinaryReader(out.toByteArray());
        for (int value : values) {
            assertEquals(value, in.readVarInt());
        }
        assertFalse(in.hasRemaining());
    }

    @Test
    void zigZagRoundTrip() {
        int[] ints = {Integer.MIN_VALUE, -1_000_000, -1, 0, 1, 1_000_000, Integer.MAX_VALUE};
        long[] longs = {Long.MIN_VALUE, -1_000_000_000_000L, -1L, 0L, 1L, 1_000_000_000_000L,
                Long.MAX_VALUE};

        BinaryWriter out = new BinaryWriter();
        for (int value : ints) {
            out.writeZigZagInt(value);
        }
        for (long value : longs) {
            out.writeZigZagLong(value);
        }

        BinaryReader in = new BinaryReader(out.toByteArray());
        for (int value : ints) {
            assertEquals(value, in.readZigZagInt());
        }
        for (long value : longs) {
            assertEquals(value, in.readZigZagLong());
        }
    }

    @Test
    void fixedWidthRoundTrip() {
        BinaryWriter out = new BinaryWriter(1);
        out.writeBoolean(true);
        out.writeByte(0xAB);
        out.writeShort((short) -12_345);
        out.writeChar('☃');
        out.writeInt(0x1234_5678);
        out.writeLong(-9_000_000_000L);
        out.writeFloat(3.5f);
        out.writeDouble(-2.25d);

        BinaryReader in = new BinaryReader(out.toByteArray());
        assertTrue(in.readBoolean());
        assertEquals((byte) 0xAB, in.readByte());
        assertEquals((short) -12_345, in.readShort());
        assertEquals('☃', in.readChar());
        assertEquals(0x1234_5678, in.readInt());
        assertEquals(-9_000_000_000L, in.readLong());
        assertEquals(3.5f, in.readFloat());
        assertEquals(-2.25d, in.readDouble());
    }

    @Test
    void byteSlicesAndStringsRoundTrip() {
        BinaryWriter out = new BinaryWriter();
        out.writeBytes(new byte[]{1, 2, 3, 4, 5}, 1, 3);
        out.writeString("");
        out.writeString("héllo 世界 🚀");
        out.writeString("a".repeat(1000));

        BinaryReader in = new BinaryReader(out.toByteArray());
        byte[] slice = new byte[3];
        in.readFully(slice, 0, 3);
        assertEquals("[2, 3, 4]", Arrays.toString(slice));
        assertEquals("", in.readString());
        assertEquals("héllo 世界 🚀", in.readString());
        assertEquals("a".repeat(1000), in.readString());
        assertFalse(in.hasRemaining());
    }

    @Test
    void patchedLengthShiftsPayload() {
        BinaryWriter out = new BinaryWriter();
        int placeholder = out.reserveVarInt();
        out.writeString("hello");
        out.patchVarInt(placeholder, 100_000);

        BinaryReader in = new BinaryReader(out.toByteArray());
        assertEquals(100_000, in.readVarInt());
        assertEquals("hello", in.readString());
    }

    @Test
    void nestedPatchedLengthsAreAppliedInnermostFirst() {
        BinaryWriter out = new BinaryWriter();
        int outer = out.reserveVarInt();
        out.writeByte(0x7F);
        int inner = out.reserveVarInt();
        out.writeBytes(new byte[]{1, 2, 3});
        out.patchVarInt(inner, 300);
        out.patchVarInt(outer, 12_345);

        BinaryReader in = new BinaryReader(out.toByteArray());
        assertEquals(12_345, in.readVarInt());
        assertEquals(0x7F, in.readUnsignedByte());
        assertEquals(300, in.readVarInt());
        byte[] payload = new byte[3];
        in.readFully(payload, 0, 3);
        assertEquals("[1, 2, 3]", Arrays.toString(payload));
    }

    @Test
    void writerResetReusesCapacity() {
        BinaryWriter out = new BinaryWriter(4);
        out.writeString("value");
        assertTrue(out.size() > 0);
        out.reset();
        assertEquals(0, out.size());
        out.writeInt(42);
        assertEquals(4, out.size());
    }

    @Test
    void writerRejectsInvalidOperations() {
        BinaryWriter out = new BinaryWriter();
        out.writeByte(1);
        out.patchByte(0, 2);
        assertThrows(IllegalArgumentException.class, () -> out.writeVarInt(-1));
        assertThrows(IllegalArgumentException.class, () -> out.writeVarLong(-1));
        assertThrows(IllegalArgumentException.class, () -> out.patchVarInt(0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> out.patchByte(5, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> out.patchByte(-1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> out.patchVarInt(-1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> out.patchVarInt(2, 1));
        assertThrows(IllegalArgumentException.class, () -> new BinaryWriter(-1));
        assertThrows(IndexOutOfBoundsException.class,
                () -> out.writeBytes(new byte[2], 1, 5));
    }

    @Test
    void readerRejectsMalformedVarInts() {
        assertThrows(SerializationException.class,
                () -> new BinaryReader(new byte[]{(byte) 0x80, (byte) 0x80, (byte) 0x80,
                        (byte) 0x80, (byte) 0x80}).readVarInt());
        assertThrows(SerializationException.class,
                () -> new BinaryReader(new byte[]{(byte) 0x80, (byte) 0x80, (byte) 0x80,
                        (byte) 0x80, (byte) 0x10}).readVarInt());
        byte[] tooLong = new byte[10];
        Arrays.fill(tooLong, (byte) 0x80);
        assertThrows(SerializationException.class, () -> new BinaryReader(tooLong).readVarLong());
        byte[] overflow = new byte[10];
        Arrays.fill(overflow, (byte) 0x80);
        overflow[9] = 0x02;
        assertThrows(SerializationException.class, () -> new BinaryReader(overflow).readVarLong());
    }

    @Test
    void readerRejectsInvalidBooleanMarker() {
        assertThrows(SerializationException.class,
                () -> new BinaryReader(new byte[]{2}).readBoolean());
    }

    @Test
    void readerEnforcesLimitsAndBounds() {
        Limits limits = new Limits(100, 4, 3, 1);
        assertThrows(SerializationException.class,
                () -> new BinaryReader(new byte[3], 0, 3, new Limits(2, 4, 3, 1)));
        assertThrows(IndexOutOfBoundsException.class,
                () -> new BinaryReader(new byte[2], 1, 2, Limits.DEFAULT));

        BinaryWriter out = new BinaryWriter();
        out.writeVarInt(10);
        out.writeBytes(new byte[10]);
        BinaryReader stringReader = new BinaryReader(out.toByteArray(), limits);
        assertThrows(SerializationException.class, stringReader::readString);

        BinaryReader collectionReader = new BinaryReader(out.toByteArray(), limits);
        assertThrows(SerializationException.class,
                () -> collectionReader.readCollectionSize("list"));

        BinaryReader truncated = new BinaryReader(new byte[0]);
        assertThrows(SerializationException.class, truncated::readByte);
        assertThrows(SerializationException.class, () -> truncated.readFully(new byte[2], 0, 2));
        assertThrows(IndexOutOfBoundsException.class,
                () -> truncated.readFully(new byte[1], 1, 1));
    }

    @Test
    void readerTracksDepthAndTrailingData() {
        BinaryReader in = new BinaryReader(new byte[2], new Limits(10, 10, 10, 1));
        in.enter();
        assertThrows(SerializationException.class, in::enter);
        in.exit();
        assertThrows(SerializationException.class, in::ensureFullyConsumed);
    }

    @Test
    void limitsValidateEveryParameter() {
        assertThrows(IllegalArgumentException.class, () -> new Limits(0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 1, 1, 0));
    }
}
