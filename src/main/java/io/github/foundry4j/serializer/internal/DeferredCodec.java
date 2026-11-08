package io.github.foundry4j.serializer.internal;

import io.github.foundry4j.serializer.BinaryReader;
import io.github.foundry4j.serializer.BinaryWriter;
import io.github.foundry4j.serializer.Codec;

import java.lang.reflect.Type;

/// A codec that resolves its delegate on first use.
///
/// Deferred resolution breaks cycles between self-referential records and their component
/// codecs. For example, `record Node(Node next)` can be built before the `Node` codec is
/// inserted into the registry.
///
/// @param <T> the encoded type
public final class DeferredCodec<T> implements Codec<T> {

    private final CodecRegistry registry;
    private final Type type;
    private volatile Codec<T> delegate;

    /// Creates a deferred codec for a type.
    ///
    /// @param registry the registry used for resolution
    /// @param type     the target type
    public DeferredCodec(CodecRegistry registry, Type type) {
        this.registry = registry;
        this.type = type;
    }

    @Override
    public void encode(BinaryWriter out, T value) {
        resolve().encode(out, value);
    }

    @Override
    public T decode(BinaryReader in) {
        return resolve().decode(in);
    }

    @SuppressWarnings("unchecked")
    private Codec<T> resolve() {
        Codec<T> current = delegate;
        if (current == null) {
            current = (Codec<T>) registry.resolve(type);
            delegate = current;
        }
        return current;
    }
}
