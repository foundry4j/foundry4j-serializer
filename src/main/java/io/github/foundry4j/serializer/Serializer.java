package io.github.foundry4j.serializer;

import io.github.foundry4j.serializer.internal.CodecRegistry;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/// The entry point of the framework.
///
/// A serializer is immutable and thread-safe after construction. It holds a registry of codecs
/// and optionally enables an integrity checksum, which is verified automatically when a payload
/// carries the checksum flag.
///
/// ```java
/// record Point(int x, int y) {}
///
/// Serializer serializer = Serializer.create();
/// byte[] bytes = serializer.serialize(new Point(1, 2), Point.class);
/// Point point = serializer.deserialize(bytes, Point.class);
/// ```
public final class Serializer {

    private static final int VERSION = 1;
    private static final int CHECKSUM_FLAG = 0x80;

    private final CodecRegistry registry;
    private final boolean checksumEnabled;
    private final Checksum checksum;
    private final Limits limits;

    private Serializer(CodecRegistry registry, boolean checksumEnabled, Limits limits) {
        this.registry = registry;
        this.checksumEnabled = checksumEnabled;
        this.checksum = Checksums.create();
        this.limits = limits;
    }

    /// Creates a serializer with the default registry and no checksum.
    ///
    /// @return a serializer with the default configuration
    public static Serializer create() {
        return builder().build();
    }

    /// Returns a builder for a custom serializer.
    ///
    /// @return a new builder
    public static Builder builder() {
        return new Builder();
    }

    /// Serializes a value using its runtime class.
    ///
    /// This convenience method cannot encode polymorphic values through a base type and cannot
    /// encode `null`. Prefer [#serialize(Object, Class)] when a declared type is available.
    ///
    /// @param value the non-null value
    /// @return the encoded bytes
    /// @throws NullPointerException if the value is `null`
    public byte[] serialize(Object value) {
        Objects.requireNonNull(value, "value");
        return serialize(value, value.getClass());
    }

    /// Serializes a value with a declared class.
    ///
    /// @param <T>   the declared type
    /// @param value the value to encode, or `null`
    /// @param type  the declared type
    /// @return the encoded bytes
    public <T> byte[] serialize(T value, Class<T> type) {
        return serialize(value, (Type) type);
    }

    /// Serializes a value with a declared reflective type.
    ///
    /// @param value the value to encode, or `null`
    /// @param type  the declared type, which may be a parameterized type
    /// @return the encoded bytes
    public byte[] serialize(Object value, Type type) {
        Codec<Object> codec = codecFor(type);
        BinaryWriter out = new BinaryWriter(256);
        out.writeByte(checksumEnabled ? VERSION | CHECKSUM_FLAG : VERSION);
        if (value == null) {
            out.writeByte(0);
        } else {
            out.writeByte(1);
            codec.encode(out, value);
        }
        if (checksumEnabled) {
            long hash = checksum.hash(out.buffer(), 1, out.size() - 1);
            out.writeLong(hash);
        }
        return out.toByteArray();
    }

    /// Deserializes a value with a declared class.
    ///
    /// @param <T>  the declared type
    /// @param data the encoded bytes
    /// @param type the declared type
    /// @return the decoded value, possibly `null`
    public <T> T deserialize(byte[] data, Class<T> type) {
        @SuppressWarnings("unchecked")
        T value = (T) deserialize(data, (Type) type);
        return value;
    }

    /// Deserializes a value with a declared reflective type.
    ///
    /// @param data the encoded bytes
    /// @param type the declared type, which may be a parameterized type
    /// @return the decoded value, possibly `null`
    /// @throws SerializationException if the input is malformed or fails verification
    public Object deserialize(byte[] data, Type type) {
        Objects.requireNonNull(data, "data");
        if (data.length < 2) {
            throw new SerializationException("Payload is too short: " + data.length + " bytes");
        }
        int header = data[0] & 0xFF;
        int version = header & 0x7F;
        if (version != VERSION) {
            throw new SerializationException("Unsupported format version: " + version);
        }
        boolean hasChecksum = (header & CHECKSUM_FLAG) != 0;
        int payloadEnd = data.length;
        if (hasChecksum) {
            if (data.length < 10) {
                throw new SerializationException("Checksummed payload is too short");
            }
            payloadEnd = data.length - Long.BYTES;
            long expected = BinaryReader.readLongLE(data, payloadEnd);
            long actual = checksum.hash(data, 1, payloadEnd - 1);
            if (expected != actual) {
                throw new SerializationException("Checksum mismatch");
            }
        }
        BinaryReader in = new BinaryReader(data, 1, payloadEnd - 1, limits);
        int presence = in.readUnsignedByte();
        if (presence == 0) {
            in.ensureFullyConsumed();
            return null;
        }
        if (presence != 1) {
            throw new SerializationException("Invalid presence marker: " + presence);
        }
        Object value = codecFor(type).decode(in);
        in.ensureFullyConsumed();
        return value;
    }

    /// Returns the codec used for a class.
    ///
    /// @param <T>  the type
    /// @param type the class
    /// @return the codec
    public <T> Codec<T> codec(Class<T> type) {
        return registry.codec(type);
    }

    @SuppressWarnings("unchecked")
    private Codec<Object> codecFor(Type type) {
        return (Codec<Object>) registry.resolve(type);
    }

    /// Builds immutable serializers with custom codecs, subtype tags, limits, and integrity
    /// checking.
    public static final class Builder {

        private final Map<Class<?>, Codec<?>> codecs = new LinkedHashMap<>();
        private final Map<Class<?>, Map<Integer, Class<?>>> subtypes = new LinkedHashMap<>();
        private boolean checksum;
        private Limits limits = Limits.DEFAULT;

        private Builder() {
        }

        /// Registers a custom codec for a class, replacing any built-in codec.
        ///
        /// @param <T>   the type
        /// @param type  the class
        /// @param codec the codec
        /// @return this builder
        public <T> Builder register(Class<T> type, Codec<T> codec) {
            codecs.put(Objects.requireNonNull(type, "type"), Objects.requireNonNull(codec, "codec"));
            return this;
        }

        /// Registers a subtype of a polymorphic base type.
        ///
        /// Explicit registrations take precedence over sealed-hierarchy discovery.
        ///
        /// @param baseType the base type
        /// @param tag      the non-negative tag written to the stream
        /// @param subtype  the subtype class
        /// @return this builder
        /// @throws IllegalArgumentException if the subtype is not assignable or the tag is negative
        /// @throws SerializationException   if the tag or subtype is already registered
        public Builder registerSubtype(Class<?> baseType, int tag, Class<?> subtype) {
            Objects.requireNonNull(baseType, "baseType");
            Objects.requireNonNull(subtype, "subtype");
            if (tag < 0) {
                throw new IllegalArgumentException("tag must be non-negative: " + tag);
            }
            if (!baseType.isAssignableFrom(subtype)) {
                throw new IllegalArgumentException(
                        subtype.getName() + " is not a subtype of " + baseType.getName());
            }
            Map<Integer, Class<?>> mapping =
                    subtypes.computeIfAbsent(baseType, _ -> new LinkedHashMap<>());
            if (mapping.containsValue(subtype)) {
                throw new SerializationException(
                        "Subtype already registered: " + subtype.getName());
            }
            if (mapping.putIfAbsent(tag, subtype) != null) {
                throw new SerializationException("Tag already registered: " + tag);
            }
            return this;
        }

        /// Enables or disables the integrity checksum.
        ///
        /// The deserializer always verifies payloads that carry the checksum flag, regardless
        /// of this setting.
        ///
        /// @param enabled whether new payloads carry a checksum
        /// @return this builder
        public Builder checksum(boolean enabled) {
            this.checksum = enabled;
            return this;
        }

        /// Replaces the decoding limits.
        ///
        /// @param limits the new limits
        /// @return this builder
        public Builder limits(Limits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /// Builds the serializer.
        ///
        /// @return an immutable serializer
        public Serializer build() {
            Map<Class<?>, Map<Integer, Class<?>>> copiedSubtypes = new HashMap<>();
            subtypes.forEach((base, mapping) -> copiedSubtypes.put(base, Map.copyOf(mapping)));
            return new Serializer(
                    new CodecRegistry(Map.copyOf(codecs), Map.copyOf(copiedSubtypes)),
                    checksum,
                    limits);
        }
    }
}
