package io.github.foundry4j.serializer;

import io.github.foundry4j.serializer.internal.ArrayCodecs;
import io.github.foundry4j.serializer.internal.CollectionCodecs.ListCodec;
import io.github.foundry4j.serializer.internal.CollectionCodecs.MapCodec;
import io.github.foundry4j.serializer.internal.CollectionCodecs.OptionalCodec;
import io.github.foundry4j.serializer.internal.CollectionCodecs.SetCodec;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// Factories for composing built-in codecs with custom element codecs.
///
/// The serializer resolves parameterized types automatically, so these factories are mainly
/// useful when a custom codec is used inside a collection or when a type is not expressible as
/// a Java reflection type.
public final class Codecs {

    private Codecs() {
    }

    /// Creates a codec for a [List].
    ///
    /// @param <T>          the element type
    /// @param elementCodec the element codec
    /// @return the list codec
    public static <T> Codec<List<T>> listOf(Codec<T> elementCodec) {
        return new ListCodec<>(elementCodec);
    }

    /// Creates a codec for a [Set].
    ///
    /// @param <T>          the element type
    /// @param elementCodec the element codec
    /// @return the set codec
    public static <T> Codec<Set<T>> setOf(Codec<T> elementCodec) {
        return new SetCodec<>(elementCodec);
    }

    /// Creates a codec for a [Map].
    ///
    /// @param <K>        the key type
    /// @param <V>        the value type
    /// @param keyCodec   the key codec
    /// @param valueCodec the value codec
    /// @return the map codec
    public static <K, V> Codec<Map<K, V>> mapOf(Codec<K> keyCodec, Codec<V> valueCodec) {
        return new MapCodec<>(keyCodec, valueCodec);
    }

    /// Creates a codec for an [Optional].
    ///
    /// @param <T>          the contained type
    /// @param elementCodec the contained-value codec
    /// @return the optional codec
    public static <T> Codec<Optional<T>> optionalOf(Codec<T> elementCodec) {
        return new OptionalCodec<>(elementCodec);
    }

    /// Creates a codec for an object array.
    ///
    /// @param <T>           the component type
    /// @param componentType the runtime component class
    /// @param elementCodec  the element codec
    /// @return the array codec
    public static <T> Codec<T[]> arrayOf(Class<T> componentType, Codec<T> elementCodec) {
        return new ArrayCodecs.ObjectArrayCodec<>(componentType, elementCodec);
    }

    /// Creates a nullable view of a codec.
    ///
    /// @param <T>   the encoded type
    /// @param codec the delegate codec
    /// @return a codec that maps `null` to a one-byte marker
    public static <T> Codec<T> nullable(Codec<T> codec) {
        return codec.nullable();
    }
}
