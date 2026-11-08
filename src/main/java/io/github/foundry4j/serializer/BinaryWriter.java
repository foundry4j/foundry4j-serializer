package io.github.foundry4j.serializer;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/// A growable, reusable output buffer for the Foundry4J binary format.
///
/// The writer stores fixed-width numbers in little-endian order and uses unsigned
/// variable-length integers for sizes and zig-zag encoded integers for signed values.
/// Instances are not thread-safe; use one writer per thread or per serialization call.
public final class BinaryWriter {

    private static final int MIN_CAPACITY = 16;

    private static final VarHandle SHORT_LE =
            MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle CHAR_LE =
            MethodHandles.byteArrayViewVarHandle(char[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT_LE =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG_LE =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private byte[] buffer;
    private int position;

    /// Creates a writer with a small default buffer.
    public BinaryWriter() {
        this(64);
    }

    /// Creates a writer with the requested initial capacity.
    ///
    /// @param initialCapacity the initial buffer size in bytes
    /// @throws IllegalArgumentException if the capacity is negative
    public BinaryWriter(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("initialCapacity must not be negative: " + initialCapacity);
        }
        this.buffer = new byte[Math.max(initialCapacity, MIN_CAPACITY)];
    }

    /// Returns the number of bytes written so far.
    ///
    /// @return the current position in the buffer
    public int size() {
        return position;
    }

    /// Removes all content while keeping the allocated buffer for reuse.
    public void reset() {
        position = 0;
    }

    /// Copies the written bytes into a right-sized array.
    ///
    /// @return a new array containing exactly the written bytes
    public byte[] toByteArray() {
        return Arrays.copyOf(buffer, position);
    }

    /// Returns the internal buffer.
    ///
    /// The returned array may be larger than [#size()] and must be treated as read-only.
    ///
    /// @return the internal backing array
    byte[] buffer() {
        return buffer;
    }

    /// Writes the low eight bits of the supplied value.
    ///
    /// @param value the value to write
    public void writeByte(int value) {
        ensureCapacity(1);
        buffer[position++] = (byte) value;
    }

    /// Writes a boolean as a single `0` or `1` byte.
    ///
    /// @param value the value to write
    public void writeBoolean(boolean value) {
        writeByte(value ? 1 : 0);
    }

    /// Writes a signed 16-bit value in little-endian order.
    ///
    /// @param value the value to write
    public void writeShort(short value) {
        ensureCapacity(2);
        SHORT_LE.set(buffer, position, value);
        position += 2;
    }

    /// Writes a UTF-16 code unit in little-endian order.
    ///
    /// @param value the value to write
    public void writeChar(char value) {
        ensureCapacity(2);
        CHAR_LE.set(buffer, position, value);
        position += 2;
    }

    /// Writes a signed 32-bit value in little-endian order.
    ///
    /// @param value the value to write
    public void writeInt(int value) {
        ensureCapacity(4);
        INT_LE.set(buffer, position, value);
        position += 4;
    }

    /// Writes a signed 64-bit value in little-endian order.
    ///
    /// @param value the value to write
    public void writeLong(long value) {
        ensureCapacity(8);
        LONG_LE.set(buffer, position, value);
        position += 8;
    }

    /// Writes a 32-bit float in little-endian order.
    ///
    /// @param value the value to write
    public void writeFloat(float value) {
        writeInt(Float.floatToRawIntBits(value));
    }

    /// Writes a 64-bit double in little-endian order.
    ///
    /// @param value the value to write
    public void writeDouble(double value) {
        writeLong(Double.doubleToRawLongBits(value));
    }

    /// Writes a non-negative integer as an unsigned variable-length integer.
    ///
    /// @param value the non-negative value to write
    /// @throws IllegalArgumentException if the value is negative
    public void writeVarInt(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("writeVarInt requires a non-negative value: " + value);
        }
        writeVarIntBits(value);
    }

    /// Writes a non-negative long as an unsigned variable-length integer.
    ///
    /// @param value the non-negative value to write
    /// @throws IllegalArgumentException if the value is negative
    public void writeVarLong(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("writeVarLong requires a non-negative value: " + value);
        }
        writeVarLongBits(value);
    }

    /// Writes a signed integer using zig-zag encoding followed by an unsigned varint.
    ///
    /// Small absolute values, including small negative numbers, occupy a single byte.
    ///
    /// @param value the signed value to write
    public void writeZigZagInt(int value) {
        writeVarIntBits((value << 1) ^ (value >> 31));
    }

    /// Writes a signed long using zig-zag encoding followed by an unsigned varint.
    ///
    /// @param value the signed value to write
    public void writeZigZagLong(long value) {
        writeVarLongBits((value << 1) ^ (value >> 63));
    }

    /// Writes a raw byte array without a length prefix.
    ///
    /// @param source the array to write
    public void writeBytes(byte[] source) {
        writeBytes(source, 0, source.length);
    }

    /// Writes a slice of a byte array without a length prefix.
    ///
    /// @param source the source array
    /// @param offset the first index to write
    /// @param length the number of bytes to write
    /// @throws IndexOutOfBoundsException if the slice is invalid
    public void writeBytes(byte[] source, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, source.length);
        ensureCapacity(length);
        System.arraycopy(source, offset, buffer, position, length);
        position += length;
    }

    /// Writes a string as a UTF-8 byte sequence prefixed by its byte length.
    ///
    /// @param value the non-null string to write
    /// @throws NullPointerException if the value is `null`
    public void writeString(String value) {
        Objects.requireNonNull(value, "value");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(bytes.length);
        writeBytes(bytes);
    }

    /// Reserves one byte for a variable-length integer that is patched later.
    ///
    /// The returned offset can be passed to [#patchVarInt(int, int)]. Placeholders must be
    /// patched in reverse order of reservation when several nested placeholders exist.
    ///
    /// @return the offset of the placeholder
    public int reserveVarInt() {
        int offset = position;
        writeByte(0);
        return offset;
    }

    /// Patches a previously reserved variable-length integer.
    ///
    /// If the encoded value needs more than one byte, the remaining content is shifted to the
    /// right. Only placeholders created by [#reserveVarInt()] can be patched.
    ///
    /// @param offset the placeholder offset returned by [#reserveVarInt()]
    /// @param value  the non-negative value to store
    /// @throws IllegalArgumentException  if the value is negative
    /// @throws IndexOutOfBoundsException if the offset is not a valid placeholder position
    public void patchVarInt(int offset, int value) {
        if (value < 0) {
            throw new IllegalArgumentException("patchVarInt requires a non-negative value: " + value);
        }
        if (offset < 0 || offset >= position) {
            throw new IndexOutOfBoundsException("Invalid placeholder offset: " + offset);
        }
        int needed = varIntSize(value);
        int growth = needed - 1;
        if (growth > 0) {
            ensureCapacity(position + growth);
            System.arraycopy(buffer, offset + 1, buffer, offset + 1 + growth, position - offset - 1);
            position += growth;
        }
        int cursor = offset;
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            buffer[cursor++] = (byte) ((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        buffer[cursor] = (byte) remaining;
    }

    /// Overwrites a single byte that has already been written.
    ///
    /// @param offset the absolute buffer offset
    /// @param value  the byte value to store
    /// @throws IndexOutOfBoundsException if the offset is outside the written region
    public void patchByte(int offset, int value) {
        if (offset < 0 || offset >= position) {
            throw new IndexOutOfBoundsException("Invalid patch offset: " + offset);
        }
        buffer[offset] = (byte) value;
    }

    private void writeVarIntBits(int value) {
        ensureCapacity(5);
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            buffer[position++] = (byte) ((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        buffer[position++] = (byte) remaining;
    }

    private void writeVarLongBits(long value) {
        ensureCapacity(10);
        long remaining = value;
        while ((remaining & ~0x7FL) != 0) {
            buffer[position++] = (byte) ((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        buffer[position++] = (byte) remaining;
    }

    private static int varIntSize(int value) {
        int size = 1;
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            size++;
            remaining >>>= 7;
        }
        return size;
    }

    private void ensureCapacity(int additional) {
        int required = position + additional;
        if (required <= buffer.length) {
            return;
        }
        int grown = buffer.length + (buffer.length >> 1) + 16;
        buffer = Arrays.copyOf(buffer, Math.max(required, grown));
    }
}
