package io.github.foundry4j.serializer;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/// A bounds-checked reader for the Foundry4J binary format.
///
/// The reader never copies the backing array and enforces the [Limits] supplied at
/// construction time. Instances are not thread-safe; use one reader per deserialization call.
public final class BinaryReader {

    private static final VarHandle SHORT_LE =
            MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle CHAR_LE =
            MethodHandles.byteArrayViewVarHandle(char[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT_LE =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG_LE =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private final byte[] data;
    private final int limit;
    private final Limits limits;
    private int position;
    private int depth;

    /// Creates a reader over the whole array using [Limits#DEFAULT].
    ///
    /// @param data the encoded bytes
    public BinaryReader(byte[] data) {
        this(data, 0, data.length, Limits.DEFAULT);
    }

    /// Creates a reader over the whole array with explicit limits.
    ///
    /// @param data   the encoded bytes
    /// @param limits the decoding limits
    public BinaryReader(byte[] data, Limits limits) {
        this(data, 0, data.length, limits);
    }

    /// Creates a reader over a slice of an array.
    ///
    /// @param data   the encoded bytes
    /// @param offset the first readable index
    /// @param length the number of readable bytes
    /// @param limits the decoding limits
    /// @throws IndexOutOfBoundsException if the slice is invalid
    /// @throws SerializationException    if the slice exceeds [Limits#maxBytes()]
    public BinaryReader(byte[] data, int offset, int length, Limits limits) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(limits, "limits");
        Objects.checkFromIndexSize(offset, length, data.length);
        if (length > limits.maxBytes()) {
            throw new SerializationException(
                    "Payload of " + length + " bytes exceeds maxBytes " + limits.maxBytes());
        }
        this.data = data;
        this.position = offset;
        this.limit = offset + length;
        this.limits = limits;
    }

    /// Returns the number of unread bytes.
    ///
    /// @return the number of remaining bytes
    public int remaining() {
        return limit - position;
    }

    /// Returns `true` when no bytes are left.
    ///
    /// @return whether the input is exhausted
    public boolean hasRemaining() {
        return position < limit;
    }

    /// Reads one byte.
    ///
    /// @return the byte value
    /// @throws SerializationException if no byte is available
    public byte readByte() {
        require(1);
        return data[position++];
    }

    /// Reads one byte as an unsigned integer in the range `0..255`.
    ///
    /// @return the unsigned byte value
    /// @throws SerializationException if no byte is available
    public int readUnsignedByte() {
        return readByte() & 0xFF;
    }

    /// Reads a boolean and validates that it is encoded as `0` or `1`.
    ///
    /// @return the boolean value
    /// @throws SerializationException if the marker is invalid
    public boolean readBoolean() {
        return switch (readUnsignedByte()) {
            case 0 -> false;
            case 1 -> true;
            default -> throw new SerializationException("Invalid boolean marker");
        };
    }

    /// Reads a little-endian signed 16-bit value.
    ///
    /// @return the value
    public short readShort() {
        require(2);
        short value = (short) SHORT_LE.get(data, position);
        position += 2;
        return value;
    }

    /// Reads a little-endian UTF-16 code unit.
    ///
    /// @return the character
    public char readChar() {
        require(2);
        char value = (char) CHAR_LE.get(data, position);
        position += 2;
        return value;
    }

    /// Reads a little-endian signed 32-bit value.
    ///
    /// @return the value
    public int readInt() {
        require(4);
        int value = (int) INT_LE.get(data, position);
        position += 4;
        return value;
    }

    /// Reads a little-endian signed 64-bit value.
    ///
    /// @return the value
    public long readLong() {
        require(8);
        long value = (long) LONG_LE.get(data, position);
        position += 8;
        return value;
    }

    /// Reads a little-endian 32-bit float.
    ///
    /// @return the value
    public float readFloat() {
        return Float.intBitsToFloat(readInt());
    }

    /// Reads a little-endian 64-bit double.
    ///
    /// @return the value
    public double readDouble() {
        return Double.longBitsToDouble(readLong());
    }

    /// Reads an unsigned variable-length integer.
    ///
    /// The returned value preserves all 32 bits, so the method is also suitable for reading
    /// zig-zag encoded data through [#readZigZagInt()].
    ///
    /// @return the encoded 32-bit value
    /// @throws SerializationException if the encoding is malformed
    public int readVarInt() {
        int result = 0;
        for (int shift = 0; shift <= 28; shift += 7) {
            int b = readUnsignedByte();
            if (shift == 28 && (b & 0xF0) != 0) {
                throw new SerializationException("Malformed varint: value exceeds 32 bits");
            }
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw new SerializationException("Malformed varint: more than 5 bytes");
    }

    /// Reads an unsigned variable-length long.
    ///
    /// The returned value preserves all 64 bits.
    ///
    /// @return the encoded 64-bit value
    /// @throws SerializationException if the encoding is malformed
    public long readVarLong() {
        long result = 0;
        for (int shift = 0; shift <= 63; shift += 7) {
            int b = readUnsignedByte();
            if (shift == 63 && (b & 0xFE) != 0) {
                throw new SerializationException("Malformed var long: value exceeds 64 bits");
            }
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw new SerializationException("Malformed var long: more than 10 bytes");
    }

    /// Reads a zig-zag encoded signed integer.
    ///
    /// @return the decoded value
    public int readZigZagInt() {
        int raw = readVarInt();
        return (raw >>> 1) ^ -(raw & 1);
    }

    /// Reads a zig-zag encoded signed long.
    ///
    /// @return the decoded value
    public long readZigZagLong() {
        long raw = readVarLong();
        return (raw >>> 1) ^ -(raw & 1);
    }

    /// Reads a length-prefixed string.
    ///
    /// @return the decoded string
    /// @throws SerializationException if the length is invalid or the input is truncated
    public String readString() {
        int length = readLength("string", limits.maxStringLength());
        require(length);
        String value = new String(data, position, length, StandardCharsets.UTF_8);
        position += length;
        return value;
    }

    /// Reads a length-prefixed byte array.
    ///
    /// @return the decoded bytes
    /// @throws SerializationException if the length is invalid or the input is truncated
    public byte[] readByteArray() {
        int length = readLength("byte[]", limits.maxCollectionSize());
        require(length);
        byte[] value = new byte[length];
        readFully(value, 0, length);
        return value;
    }

    /// Reads a collection size and validates it against [Limits#maxCollectionSize()].
    ///
    /// @param description a human-readable description used in error messages
    /// @return the validated non-negative size
    /// @throws SerializationException if the size is invalid
    public int readCollectionSize(String description) {
        return readLength(description, limits.maxCollectionSize());
    }

    /// Reads an arbitrary length and validates it against an explicit maximum.
    ///
    /// @param description a human-readable description used in error messages
    /// @param maximum     the maximum accepted length
    /// @return the validated non-negative length
    /// @throws SerializationException if the length is invalid or exceeds the input
    public int readSize(String description, int maximum) {
        return readLength(description, maximum);
    }

    /// Reads exactly `length` bytes into a slice of the destination array.
    ///
    /// @param destination the destination array
    /// @param offset      the first destination index
    /// @param length      the number of bytes to read
    /// @throws SerializationException if the input is truncated
    public void readFully(byte[] destination, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, destination.length);
        require(length);
        System.arraycopy(data, position, destination, offset, length);
        position += length;
    }

    /// Enters one level of nesting and enforces [Limits#maxDepth()].
    ///
    /// Every call must be paired with [#exit()] in a `finally` block.
    ///
    /// @throws SerializationException if the depth limit is exceeded
    public void enter() {
        if (++depth > limits.maxDepth()) {
            throw new SerializationException("Maximum nesting depth exceeded: " + limits.maxDepth());
        }
    }

    /// Leaves one level of nesting.
    public void exit() {
        depth--;
    }

    /// Verifies that the whole input has been consumed.
    ///
    /// @throws SerializationException if unread bytes remain
    public void ensureFullyConsumed() {
        if (position != limit) {
            throw new SerializationException(
                    "Trailing data: " + (limit - position) + " unread bytes");
        }
    }

    /// Reads a little-endian long directly from a byte array.
    ///
    /// @param data   the source array
    /// @param offset the index of the first byte
    /// @return the decoded value
    static long readLongLE(byte[] data, int offset) {
        return (long) LONG_LE.get(data, offset);
    }

    private int readLength(String description, int maximum) {
        int length = readVarInt();
        if (length < 0 || length > maximum) {
            throw new SerializationException(
                    "Invalid " + description + " length: " + Integer.toUnsignedString(length)
                            + " (maximum " + maximum + ")");
        }
        return length;
    }

    private void require(int count) {
        if (count > limit - position) {
            throw new SerializationException(
                    "Truncated input: need " + count + " bytes, have " + (limit - position));
        }
    }
}
