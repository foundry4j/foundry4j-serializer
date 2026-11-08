package io.github.foundry4j.serializer;

import java.util.Objects;

/// Encodes and decodes a single Java type.
///
/// A codec is stateless with respect to the values it handles and therefore safe to share
/// between threads. Implementations must not retain the writer or reader after the call
/// returns.
///
/// @param <T> the encoded type
public interface Codec<T> {

    /// Encodes a non-null value.
    ///
    /// @param out   the destination writer
    /// @param value the value to encode, never `null`
    void encode(BinaryWriter out, T value);

    /// Decodes a value.
    ///
    /// @param in the source reader
    /// @return the decoded value
    T decode(BinaryReader in);

    /// Encodes a value with a functional encoder.
    ///
    /// @param <T> the encoded type
    @FunctionalInterface
    interface Encoder<T> {
        /// Encodes a non-null value.
        ///
        /// @param out   the destination writer
        /// @param value the value to encode
        void encode(BinaryWriter out, T value);
    }

    /// Decodes a value with a functional decoder.
    ///
    /// @param <T> the decoded type
    @FunctionalInterface
    interface Decoder<T> {
        /// Decodes a value.
        ///
        /// @param in the source reader
        /// @return the decoded value
        T decode(BinaryReader in);
    }

    /// Creates a codec from separate encoder and decoder functions.
    ///
    /// @param <T>     the encoded type
    /// @param encoder the encoding function
    /// @param decoder the decoding function
    /// @return a codec backed by the two functions
    /// @throws NullPointerException if an argument is `null`
    static <T> Codec<T> of(Encoder<T> encoder, Decoder<T> decoder) {
        Objects.requireNonNull(encoder, "encoder");
        Objects.requireNonNull(decoder, "decoder");
        return new Codec<>() {
            @Override
            public void encode(BinaryWriter out, T value) {
                encoder.encode(out, value);
            }

            @Override
            public T decode(BinaryReader in) {
                return decoder.decode(in);
            }
        };
    }

    /// Returns a codec that supports `null` by writing a one-byte presence marker.
    ///
    /// Collection and record codecs already carry their own compact null maps, so this
    /// wrapper is mainly useful for custom codecs composed by hand.
    ///
    /// @return a nullable view of this codec
    default Codec<T> nullable() {
        Codec<T> delegate = this;
        return new Codec<>() {
            @Override
            public void encode(BinaryWriter out, T value) {
                if (value == null) {
                    out.writeByte(0);
                } else {
                    out.writeByte(1);
                    delegate.encode(out, value);
                }
            }

            @Override
            public T decode(BinaryReader in) {
                return switch (in.readUnsignedByte()) {
                    case 0 -> null;
                    case 1 -> delegate.decode(in);
                    default -> throw new SerializationException("Invalid null marker");
                };
            }
        };
    }
}
