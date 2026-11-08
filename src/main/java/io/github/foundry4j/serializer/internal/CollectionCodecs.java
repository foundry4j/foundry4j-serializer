package io.github.foundry4j.serializer.internal;

import io.github.foundry4j.serializer.BinaryReader;
import io.github.foundry4j.serializer.BinaryWriter;
import io.github.foundry4j.serializer.Codec;
import io.github.foundry4j.serializer.SerializationException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// Codecs for lists, sets, maps, and optionals.
///
/// Every group of eight reference elements is preceded by a null bit mask. This keeps payloads
/// compact when nulls are rare while still supporting them everywhere.
public final class CollectionCodecs {

    private CollectionCodecs() {
    }

    private static void encodeElements(BinaryWriter out, Collection<?> values, Codec<Object> codec) {
        out.writeVarInt(values.size());
        Iterator<?> iterator = values.iterator();
        int remaining = values.size();
        while (remaining > 0) {
            int group = Math.min(8, remaining);
            int maskOffset = out.size();
            out.writeByte(0);
            int mask = 0;
            for (int index = 0; index < group; index++) {
                Object element = iterator.next();
                if (element != null) {
                    mask |= 1 << index;
                    codec.encode(out, element);
                }
            }
            out.patchByte(maskOffset, mask);
            remaining -= group;
        }
    }

    private static void decodeElements(BinaryReader in, Collection<Object> target, Codec<Object> codec) {
        int size = in.readCollectionSize("collection");
        int packedLength = (size + 7) >>> 3;
        if (packedLength > in.remaining()) {
            throw new SerializationException("Truncated collection");
        }
        in.enter();
        try {
            int remaining = size;
            while (remaining > 0) {
                int group = Math.min(8, remaining);
                int mask = in.readUnsignedByte();
                for (int index = 0; index < group; index++) {
                    if ((mask & (1 << index)) != 0) {
                        target.add(codec.decode(in));
                    } else {
                        target.add(null);
                    }
                }
                remaining -= group;
            }
        } finally {
            in.exit();
        }
    }

    /// Codec for [List] and [Collection] values.
    ///
    /// @param <T> the element type
    public static final class ListCodec<T> implements Codec<List<T>> {

        private final Codec<Object> elementCodec;

        /// Creates a list codec.
        ///
        /// @param elementCodec the codec for non-null elements
        @SuppressWarnings("unchecked")
        public ListCodec(Codec<?> elementCodec) {
            this.elementCodec = (Codec<Object>) elementCodec;
        }

        @Override
        public void encode(BinaryWriter out, List<T> value) {
            encodeElements(out, value, elementCodec);
        }

        @Override
        public List<T> decode(BinaryReader in) {
            List<Object> result = new ArrayList<>(Math.min(in.remaining(), 256));
            decodeElements(in, result, elementCodec);
            @SuppressWarnings("unchecked")
            List<T> cast = (List<T>) result;
            return cast;
        }
    }

    /// Codec for [Set] values. Decoded sets preserve insertion order through
    /// [LinkedHashSet].
    ///
    /// @param <T> the element type
    public static final class SetCodec<T> implements Codec<Set<T>> {

        private final Codec<Object> elementCodec;

        /// Creates a set codec.
        ///
        /// @param elementCodec the codec for non-null elements
        @SuppressWarnings("unchecked")
        public SetCodec(Codec<?> elementCodec) {
            this.elementCodec = (Codec<Object>) elementCodec;
        }

        @Override
        public void encode(BinaryWriter out, Set<T> value) {
            encodeElements(out, value, elementCodec);
        }

        @Override
        public Set<T> decode(BinaryReader in) {
            Set<Object> result = new LinkedHashSet<>();
            decodeElements(in, result, elementCodec);
            @SuppressWarnings("unchecked")
            Set<T> cast = (Set<T>) result;
            return cast;
        }
    }

    /// Codec for [Map] values. Decoded maps preserve insertion order through
    /// [LinkedHashMap].
    ///
    /// @param <K> the key type
    /// @param <V> the value type
    public static final class MapCodec<K, V> implements Codec<Map<K, V>> {

        private final Codec<Object> keyCodec;
        private final Codec<Object> valueCodec;

        /// Creates a map codec.
        ///
        /// @param keyCodec   the codec for non-null keys
        /// @param valueCodec the codec for non-null values
        @SuppressWarnings("unchecked")
        public MapCodec(Codec<?> keyCodec, Codec<?> valueCodec) {
            this.keyCodec = (Codec<Object>) keyCodec;
            this.valueCodec = (Codec<Object>) valueCodec;
        }

        @Override
        public void encode(BinaryWriter out, Map<K, V> value) {
            out.writeVarInt(value.size());
            Iterator<? extends Map.Entry<K, V>> iterator = value.entrySet().iterator();
            int remaining = value.size();
            while (remaining > 0) {
                int group = Math.min(8, remaining);
                int keyMaskOffset = out.size();
                out.writeByte(0);
                int valueMaskOffset = out.size();
                out.writeByte(0);
                int keyMask = 0;
                int valueMask = 0;
                for (int index = 0; index < group; index++) {
                    Map.Entry<K, V> entry = iterator.next();
                    K key = entry.getKey();
                    V mapped = entry.getValue();
                    if (key != null) {
                        keyMask |= 1 << index;
                        keyCodec.encode(out, key);
                    }
                    if (mapped != null) {
                        valueMask |= 1 << index;
                        valueCodec.encode(out, mapped);
                    }
                }
                out.patchByte(keyMaskOffset, keyMask);
                out.patchByte(valueMaskOffset, valueMask);
                remaining -= group;
            }
        }

        @Override
        public Map<K, V> decode(BinaryReader in) {
            int size = in.readCollectionSize("map");
            int packedLength = (size + 7) >>> 3;
            if (packedLength * 2L > in.remaining()) {
                throw new SerializationException("Truncated map");
            }
            Map<Object, Object> result = new LinkedHashMap<>();
            in.enter();
            try {
                int remaining = size;
                while (remaining > 0) {
                    int group = Math.min(8, remaining);
                    int keyMask = in.readUnsignedByte();
                    int valueMask = in.readUnsignedByte();
                    for (int index = 0; index < group; index++) {
                        Object key = (keyMask & (1 << index)) != 0 ? keyCodec.decode(in) : null;
                        Object mapped = (valueMask & (1 << index)) != 0 ? valueCodec.decode(in) : null;
                        result.put(key, mapped);
                    }
                    remaining -= group;
                }
            } finally {
                in.exit();
            }
            @SuppressWarnings("unchecked")
            Map<K, V> cast = (Map<K, V>) result;
            return cast;
        }
    }

    /// Codec for [Optional] values.
    ///
    /// @param <T> the contained type
    public static final class OptionalCodec<T> implements Codec<Optional<T>> {

        private final Codec<Object> elementCodec;

        /// Creates an optional codec.
        ///
        /// @param elementCodec the codec for the contained value
        @SuppressWarnings("unchecked")
        public OptionalCodec(Codec<?> elementCodec) {
            this.elementCodec = (Codec<Object>) elementCodec;
        }

        @Override
        public void encode(BinaryWriter out, Optional<T> value) {
            if (value.isEmpty()) {
                out.writeByte(0);
            } else {
                out.writeByte(1);
                elementCodec.encode(out, value.get());
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public Optional<T> decode(BinaryReader in) {
            return switch (in.readUnsignedByte()) {
                case 0 -> Optional.empty();
                case 1 -> Optional.ofNullable((T) elementCodec.decode(in));
                default -> throw new SerializationException("Invalid optional marker");
            };
        }
    }
}
