package io.github.foundry4j.serializer.internal;

import io.github.foundry4j.serializer.BinaryReader;
import io.github.foundry4j.serializer.BinaryWriter;
import io.github.foundry4j.serializer.Codec;
import io.github.foundry4j.serializer.SerializationException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/// Codec for sealed hierarchies and explicitly registered subtypes.
///
/// The runtime class is mapped to a dense tag, which is written as a varint before the subtype
/// payload. For sealed types the tags follow the order of
/// `Class#getPermittedSubclasses()`; explicit registrations can choose their own tags.
///
/// @param <T> the base type
public final class PolymorphicCodec<T> implements Codec<T> {

    private final Class<T> baseType;
    private final Map<Class<?>, Integer> tags;
    private final Codec<Object>[] codecsByTag;

    /// Creates a codec for a sealed hierarchy.
    ///
    /// @param baseType the sealed base type
    /// @param registry the registry used to resolve permitted subtypes
    /// @throws SerializationException if the type is not sealed or has no permitted subtypes
    public PolymorphicCodec(Class<T> baseType, CodecRegistry registry) {
        this(baseType, sealedSubtypes(baseType), registry);
    }

    /// Creates a codec for explicitly registered subtypes.
    ///
    /// @param baseType the base type
    /// @param subtypes the tag-to-subtype mapping
    /// @param registry the registry used to resolve subtype codecs
    /// @throws SerializationException if a subtype is invalid or a tag is duplicated
    @SuppressWarnings("unchecked")
    public PolymorphicCodec(Class<T> baseType, Map<Integer, Class<?>> subtypes, CodecRegistry registry) {
        this.baseType = baseType;
        if (subtypes.isEmpty()) {
            throw new SerializationException("No subtypes registered for " + baseType.getName());
        }
        int maximumTag = -1;
        for (int tag : subtypes.keySet()) {
            if (tag < 0) {
                throw new SerializationException("Subtype tags must be non-negative: " + tag);
            }
            maximumTag = Math.max(maximumTag, tag);
        }
        this.codecsByTag = new Codec[maximumTag + 1];
        this.tags = new HashMap<>(subtypes.size());
        for (Map.Entry<Integer, Class<?>> entry : subtypes.entrySet()) {
            int tag = entry.getKey();
            Class<?> subtype = entry.getValue();
            if (!baseType.isAssignableFrom(subtype)) {
                throw new SerializationException(
                        subtype.getName() + " is not a subtype of " + baseType.getName());
            }
            if (tags.put(subtype, tag) != null) {
                throw new SerializationException("Duplicate subtype registration: " + subtype.getName());
            }
            if (codecsByTag[tag] != null) {
                throw new SerializationException("Duplicate subtype tag: " + tag);
            }
            codecsByTag[tag] = registry.deferred(subtype);
        }
    }

    @Override
    public void encode(BinaryWriter out, T value) {
        Integer tag = tags.get(value.getClass());
        if (tag == null) {
            throw new SerializationException(
                    "Unregistered subtype " + value.getClass().getName()
                            + " for base type " + baseType.getName());
        }
        out.writeVarInt(tag);
        codecsByTag[tag].encode(out, value);
    }

    @Override
    public T decode(BinaryReader in) {
        int tag = in.readVarInt();
        if (tag < 0 || tag >= codecsByTag.length || codecsByTag[tag] == null) {
            throw new SerializationException(
                    "Unknown subtype tag " + Integer.toUnsignedString(tag)
                            + " for base type " + baseType.getName());
        }
        return baseType.cast(codecsByTag[tag].decode(in));
    }

    private static Map<Integer, Class<?>> sealedSubtypes(Class<?> baseType) {
        Class<?>[] permitted = baseType.getPermittedSubclasses();
        if (permitted == null || permitted.length == 0) {
            throw new SerializationException(
                    "Type " + baseType.getName()
                            + " is not sealed; register subtypes explicitly");
        }
        Map<Integer, Class<?>> subtypes = new LinkedHashMap<>();
        for (int index = 0; index < permitted.length; index++) {
            subtypes.put(index, permitted[index]);
        }
        return subtypes;
    }
}
