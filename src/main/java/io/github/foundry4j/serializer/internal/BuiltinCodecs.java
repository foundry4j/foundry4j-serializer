package io.github.foundry4j.serializer.internal;

import io.github.foundry4j.serializer.BinaryReader;
import io.github.foundry4j.serializer.BinaryWriter;
import io.github.foundry4j.serializer.Codec;
import io.github.foundry4j.serializer.SerializationException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

/// Ready-made codecs for JDK value types.
///
/// All codecs assume non-null input. Nullability is handled by the enclosing record, collection,
/// or map codec.
public final class BuiltinCodecs {

    private BuiltinCodecs() {
    }

    /// Codec for `boolean`.
    public static final Codec<Boolean> BOOLEAN = Codec.of(
            BinaryWriter::writeBoolean,
            BinaryReader::readBoolean);

    /// Codec for `byte`.
    public static final Codec<Byte> BYTE = Codec.of(
            (out, value) -> out.writeByte(value),
            BinaryReader::readByte);

    /// Codec for `short`.
    public static final Codec<Short> SHORT = Codec.of(
            BinaryWriter::writeShort,
            BinaryReader::readShort);

    /// Codec for `char`.
    public static final Codec<Character> CHARACTER = Codec.of(
            BinaryWriter::writeChar,
            BinaryReader::readChar);

    /// Codec for `int`, using zig-zag varint encoding.
    public static final Codec<Integer> INTEGER = Codec.of(
            BinaryWriter::writeZigZagInt,
            BinaryReader::readZigZagInt);

    /// Codec for `long`, using zig-zag varint encoding.
    public static final Codec<Long> LONG = Codec.of(
            BinaryWriter::writeZigZagLong,
            BinaryReader::readZigZagLong);

    /// Codec for `float`.
    public static final Codec<Float> FLOAT = Codec.of(
            BinaryWriter::writeFloat,
            BinaryReader::readFloat);

    /// Codec for `double`.
    public static final Codec<Double> DOUBLE = Codec.of(
            BinaryWriter::writeDouble,
            BinaryReader::readDouble);

    /// Codec for [String], encoded as UTF-8 with a varint byte length.
    public static final Codec<String> STRING = Codec.of(
            BinaryWriter::writeString,
            BinaryReader::readString);

    /// Codec for [UUID], encoded as two fixed-width 64-bit halves.
    public static final Codec<UUID> UUID_CODEC = Codec.of(
            (out, value) -> {
                out.writeLong(value.getMostSignificantBits());
                out.writeLong(value.getLeastSignificantBits());
            },
            in -> new UUID(in.readLong(), in.readLong()));

    /// Codec for [BigInteger], encoded as its two's-complement byte representation.
    public static final Codec<BigInteger> BIG_INTEGER = Codec.of(
            (out, value) -> {
                byte[] bytes = value.toByteArray();
                out.writeVarInt(bytes.length);
                out.writeBytes(bytes);
            },
            in -> {
                byte[] bytes = in.readByteArray();
                if (bytes.length == 0) {
                    throw new SerializationException("Invalid BigInteger payload");
                }
                return new BigInteger(bytes);
            });

    /// Codec for [BigDecimal], encoded as an unscaled value and a zig-zag scale.
    public static final Codec<BigDecimal> BIG_DECIMAL = Codec.of(
            (out, value) -> {
                byte[] bytes = value.unscaledValue().toByteArray();
                out.writeVarInt(bytes.length);
                out.writeBytes(bytes);
                out.writeZigZagInt(value.scale());
            },
            in -> {
                byte[] bytes = in.readByteArray();
                if (bytes.length == 0) {
                    throw new SerializationException("Invalid BigDecimal payload");
                }
                return new BigDecimal(new BigInteger(bytes), in.readZigZagInt());
            });

    /// Codec for [LocalDate], encoded as an epoch day.
    public static final Codec<LocalDate> LOCAL_DATE = Codec.of(
            (out, value) -> out.writeZigZagLong(value.toEpochDay()),
            in -> LocalDate.ofEpochDay(in.readZigZagLong()));

    /// Codec for [LocalTime], encoded as a nanosecond of day.
    public static final Codec<LocalTime> LOCAL_TIME = Codec.of(
            (out, value) -> out.writeVarLong(value.toNanoOfDay()),
            in -> LocalTime.ofNanoOfDay(in.readVarLong()));

    /// Codec for [LocalDateTime], encoded as an epoch day and a nanosecond of day.
    public static final Codec<LocalDateTime> LOCAL_DATE_TIME = Codec.of(
            (out, value) -> {
                out.writeZigZagLong(value.toLocalDate().toEpochDay());
                out.writeVarLong(value.toLocalTime().toNanoOfDay());
            },
            in -> LocalDateTime.of(
                    LocalDate.ofEpochDay(in.readZigZagLong()),
                    LocalTime.ofNanoOfDay(in.readVarLong())));

    /// Codec for [Instant], encoded as epoch seconds and a nanosecond adjustment.
    public static final Codec<Instant> INSTANT = Codec.of(
            (out, value) -> {
                out.writeZigZagLong(value.getEpochSecond());
                out.writeVarInt(value.getNano());
            },
            in -> Instant.ofEpochSecond(in.readZigZagLong(), in.readVarInt()));

    /// Codec for [Duration], encoded as seconds and a nanosecond adjustment.
    public static final Codec<Duration> DURATION = Codec.of(
            (out, value) -> {
                out.writeZigZagLong(value.getSeconds());
                out.writeVarInt(value.getNano());
            },
            in -> Duration.ofSeconds(in.readZigZagLong(), in.readVarInt()));

    /// Codec for [ZoneOffset], encoded as total seconds.
    public static final Codec<ZoneOffset> ZONE_OFFSET = Codec.of(
            (out, value) -> out.writeZigZagInt(value.getTotalSeconds()),
            in -> ZoneOffset.ofTotalSeconds(in.readZigZagInt()));

    /// Codec for [ZonedDateTime], encoded as a local date-time and a zone identifier.
    public static final Codec<ZonedDateTime> ZONED_DATE_TIME = Codec.of(
            (out, value) -> {
                out.writeZigZagLong(value.toLocalDate().toEpochDay());
                out.writeVarLong(value.toLocalTime().toNanoOfDay());
                out.writeString(value.getZone().getId());
            },
            in -> ZonedDateTime.of(
                    LocalDate.ofEpochDay(in.readZigZagLong()),
                    LocalTime.ofNanoOfDay(in.readVarLong()),
                    ZoneId.of(in.readString())));

    /// Codec for enum constants, encoded by ordinal.
    ///
    /// @param <E> the enum type
    public static final class EnumCodec<E extends Enum<E>> implements Codec<E> {

        private final E[] constants;

        /// Creates a codec for an enum class.
        ///
        /// @param type the enum class
        /// @throws SerializationException if the class is not an enum
        public EnumCodec(Class<E> type) {
            this.constants = type.getEnumConstants();
            if (constants == null) {
                throw new SerializationException("Not an enum type: " + type.getName());
            }
        }

        @Override
        public void encode(io.github.foundry4j.serializer.BinaryWriter out, E value) {
            out.writeVarInt(value.ordinal());
        }

        @Override
        public E decode(io.github.foundry4j.serializer.BinaryReader in) {
            int ordinal = in.readVarInt();
            if (ordinal < 0 || ordinal >= constants.length) {
                throw new SerializationException(
                        "Invalid enum ordinal " + Integer.toUnsignedString(ordinal));
            }
            return constants[ordinal];
        }
    }
}
