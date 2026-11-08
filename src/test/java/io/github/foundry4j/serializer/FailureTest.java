package io.github.foundry4j.serializer;

import io.github.foundry4j.serializer.internal.ArrayCodecs;
import io.github.foundry4j.serializer.internal.BuiltinCodecs;
import io.github.foundry4j.serializer.internal.CodecRegistry;
import io.github.foundry4j.serializer.internal.CollectionCodecs;
import io.github.foundry4j.serializer.internal.PolymorphicCodec;
import io.github.foundry4j.serializer.internal.RecordCodec;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FailureTest {

    record Payload(String name, int value) {
    }

    record Wrapper(List<String> items) {
    }

    record Link(int value, Link next) {
    }

    record Box<T>(T value) {
    }

    enum Color {
        RED, GREEN
    }

    interface Base {
    }

    record Impl(int value) implements Base {
    }

    static final class ComparableHolder {
        Comparable<String> value;
    }

    private final Serializer serializer = Serializer.create();

    @Test
    void unsupportedTypesAreRejected() {
        assertThrows(SerializationException.class, () -> serializer.codec(Object.class));
        assertThrows(SerializationException.class, () -> serializer.codec(Thread.class));
        assertThrows(SerializationException.class, () -> serializer.codec(List.class));
        assertThrows(SerializationException.class, () -> serializer.codec(Set.class));
        assertThrows(SerializationException.class, () -> serializer.codec(Map.class));
        assertThrows(SerializationException.class, () -> serializer.codec(Iterable.class));
        assertThrows(SerializationException.class, () -> serializer.codec(Collection.class));
    }

    @Test
    void unsupportedParameterizedTypeIsRejected() throws Exception {
        Type type = ComparableHolder.class.getDeclaredField("value").getGenericType();
        assertThrows(SerializationException.class, () -> serializer.serialize("x", type));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rawGenericRecordCannotResolveItsTypeVariable() {
        Codec<Box> codec = serializer.codec(Box.class);
        BinaryWriter out = new BinaryWriter();
        assertThrows(SerializationException.class, () -> codec.encode(out, new Box<>("x")));
    }

    @Test
    void recordCodecRejectsNonRecords() {
        CodecRegistry registry = new CodecRegistry(Map.of(), Map.of());
        assertThrows(SerializationException.class,
                () -> new RecordCodec<>(String.class, registry, Map.of()));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void enumCodecRejectsNonEnumsAndInvalidOrdinals() {
        assertThrows(SerializationException.class, () -> new BuiltinCodecs.EnumCodec(String.class));

        Codec<Color> colorCodec = serializer.codec(Color.class);
        BinaryWriter out = new BinaryWriter();
        out.writeVarInt(99);
        assertThrows(SerializationException.class,
                () -> colorCodec.decode(new BinaryReader(out.toByteArray())));

        BinaryWriter negative = new BinaryWriter();
        negative.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F});
        assertThrows(SerializationException.class,
                () -> colorCodec.decode(new BinaryReader(negative.toByteArray())));
    }

    @Test
    void primitiveArrayFactoryRejectsObjectArrays() {
        assertThrows(SerializationException.class, () -> ArrayCodecs.primitive(String[].class));
    }

    @Test
    void polymorphicCodecRejectsInvalidRegistrations() {
        CodecRegistry registry = new CodecRegistry(Map.of(), Map.of());
        assertThrows(SerializationException.class,
                () -> new PolymorphicCodec<>(Base.class, Map.of(), registry));
        assertThrows(SerializationException.class,
                () -> new PolymorphicCodec<>(Base.class, Map.of(0, String.class), registry));
        assertThrows(SerializationException.class,
                () -> new PolymorphicCodec<>(Base.class, Map.of(-1, Impl.class), registry));
        assertThrows(SerializationException.class,
                () -> new PolymorphicCodec<>(Base.class, Map.of(0, Impl.class, 1, Impl.class),
                        registry));
    }

    @Test
    void invalidMarkersAreRejected() {
        Codec<String> nullable = BuiltinCodecs.STRING.nullable();
        assertThrows(SerializationException.class,
                () -> nullable.decode(new BinaryReader(new byte[]{2})));
        assertThrows(SerializationException.class,
                () -> new CollectionCodecs.OptionalCodec<>(BuiltinCodecs.STRING)
                        .decode(new BinaryReader(new byte[]{2})));
    }

    @Test
    void codecFactoryValidatesArguments() {
        assertThrows(NullPointerException.class,
                () -> Codec.of(null, (Codec.Decoder<String>) _ -> null));
        assertThrows(NullPointerException.class,
                () -> Codec.of((Codec.Encoder<String>) (_, _) -> {
                }, null));
    }

    @Test
    void decodingLimitsAreEnforced() {
        Serializer limitedDepth = Serializer.builder()
                .limits(new Limits(1_000_000, 1_000_000, 1_000_000, 3))
                .build();
        Link chain = null;
        for (int index = 0; index < 10; index++) {
            chain = new Link(index, chain);
        }
        byte[] chainBytes = serializer.serialize(chain, Link.class);
        assertThrows(SerializationException.class,
                () -> limitedDepth.deserialize(chainBytes, Link.class));

        Serializer limitedCollection = Serializer.builder()
                .limits(new Limits(1_000_000, 1_000_000, 2, 100))
                .build();
        byte[] listBytes = serializer.serialize(
                new Wrapper(List.of("a", "b", "c")), Wrapper.class);
        assertThrows(SerializationException.class,
                () -> limitedCollection.deserialize(listBytes, Wrapper.class));

        Serializer limitedString = Serializer.builder()
                .limits(new Limits(1_000_000, 3, 1_000_000, 100))
                .build();
        byte[] stringBytes = serializer.serialize("hello", String.class);
        assertThrows(SerializationException.class,
                () -> limitedString.deserialize(stringBytes, String.class));

        Serializer limitedBytes = Serializer.builder()
                .limits(new Limits(20, 1_000_000, 1_000_000, 100))
                .build();
        byte[] largeBytes = serializer.serialize("x".repeat(100), String.class);
        assertThrows(SerializationException.class,
                () -> limitedBytes.deserialize(largeBytes, String.class));
    }

    @Test
    void truncatedPayloadsAreRejected() {
        byte[] bytes = serializer.serialize(new Payload("hello", 42), Payload.class);
        byte[] truncated = Arrays.copyOf(bytes, bytes.length - 1);
        assertThrows(SerializationException.class,
                () -> serializer.deserialize(truncated, Payload.class));
    }

    @Test
    void explicitReadSizeLimitIsEnforced() {
        BinaryWriter out = new BinaryWriter();
        out.writeVarInt(100);
        BinaryReader in = new BinaryReader(out.toByteArray());
        assertThrows(SerializationException.class, () -> in.readSize("blob", 10));
    }

    @Test
    void publicCodecFactoriesComposeCorrectly() {
        Codec<List<String>> listCodec = Codecs.listOf(BuiltinCodecs.STRING);
        List<String> list = new ArrayList<>(Arrays.asList("a", null, "b"));
        BinaryWriter listOut = new BinaryWriter();
        listCodec.encode(listOut, list);
        assertEquals(list, listCodec.decode(new BinaryReader(listOut.toByteArray())));

        Codec<Set<String>> setCodec = Codecs.setOf(BuiltinCodecs.STRING);
        Set<String> set = new LinkedHashSet<>(Arrays.asList("a", null, "b"));
        BinaryWriter setOut = new BinaryWriter();
        setCodec.encode(setOut, set);
        assertEquals(set, setCodec.decode(new BinaryReader(setOut.toByteArray())));

        Codec<Map<String, Integer>> mapCodec =
                Codecs.mapOf(BuiltinCodecs.STRING, BuiltinCodecs.INTEGER);
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("a", 1);
        map.put(null, null);
        BinaryWriter mapOut = new BinaryWriter();
        mapCodec.encode(mapOut, map);
        assertEquals(map, mapCodec.decode(new BinaryReader(mapOut.toByteArray())));

        Codec<Optional<String>> optionalCodec = Codecs.optionalOf(BuiltinCodecs.STRING);
        BinaryWriter optionalOut = new BinaryWriter();
        optionalCodec.encode(optionalOut, Optional.of("x"));
        optionalCodec.encode(optionalOut, Optional.empty());
        BinaryReader optionalIn = new BinaryReader(optionalOut.toByteArray());
        assertEquals(Optional.of("x"), optionalCodec.decode(optionalIn));
        assertEquals(Optional.empty(), optionalCodec.decode(optionalIn));

        Codec<String[]> arrayCodec = Codecs.arrayOf(String.class, BuiltinCodecs.STRING);
        String[] array = {"a", null, "b"};
        BinaryWriter arrayOut = new BinaryWriter();
        arrayCodec.encode(arrayOut, array);
        assertArrayEquals(array, arrayCodec.decode(new BinaryReader(arrayOut.toByteArray())));

        Codec<String> nullable = Codecs.nullable(BuiltinCodecs.STRING);
        BinaryWriter nullableOut = new BinaryWriter();
        nullable.encode(nullableOut, null);
        nullable.encode(nullableOut, "value");
        BinaryReader nullableIn = new BinaryReader(nullableOut.toByteArray());
        assertNull(nullable.decode(nullableIn));
        assertEquals("value", nullable.decode(nullableIn));
    }
}
