package io.github.foundry4j.serializer;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoundTripTest {

    record Point(int x, int y) {
    }

    record Person(String name, int age, Point location, List<String> tags,
                  Optional<Point> alternate) {
    }

    record Values(boolean bool, byte b, short s, char c, int i, long l, float f, double d,
                  String text) {
    }

    record Boxed(Byte b, Short s, Character c, Integer i, Long l, Float f, Double d,
                 Boolean bool) {
    }

    record Many(int a, int b, int c, int d, int e, int f, int g, int h, String name, int tail) {
    }

    record Node(String value, Node next) {
    }

    record Box<T>(T value, List<T> items) {
    }

    record Wild(List<? extends String> values) {
    }

    record ArraysHolder(byte[] bytes, boolean[] flags, char[] chars, short[] shorts, int[] ints,
                        long[] longs, float[] floats, double[] doubles, String[] names,
                        int[][] matrix) {
    }

    record Jdk(UUID id, BigInteger big, BigDecimal decimal, LocalDate date, LocalTime time,
               LocalDateTime dateTime, Instant instant, Duration duration, ZoneOffset offset,
               ZonedDateTime zoned) {
    }

    enum Color {
        RED, GREEN, BLUE
    }

    record EnumHolder(Color color) {
    }

    record Collections(List<String> list, Set<String> set, Map<String, Point> map,
                       Optional<String> optional) {
    }

    static final class GenericHolder {
        Box<String> box;
    }

    static final class ListHolder {
        List<String> items;
    }

    private final Serializer serializer = Serializer.create();

    @Test
    void nestedRecordsWithNullsRoundTrip() {
        Person person = new Person("Ada", 36, new Point(3, 4),
                new ArrayList<>(Arrays.asList("engineer", null, "mathematician")),
                Optional.of(new Point(-1, -2)));
        Person decoded = serializer.deserialize(serializer.serialize(person, Person.class),
                Person.class);
        assertEquals(person, decoded);

        Person empty = new Person("Grace", 0, null, null, Optional.empty());
        assertEquals(empty, serializer.deserialize(serializer.serialize(empty, Person.class),
                Person.class));

        Person plain = new Person(null, -1, null, List.of(), Optional.empty());
        assertEquals(plain, serializer.deserialize(serializer.serialize(plain, Person.class),
                Person.class));
    }

    @Test
    void primitiveComponentsRoundTrip() {
        Values values = new Values(true, (byte) -7, (short) 30_000, 'Ω', -123_456,
                Long.MIN_VALUE, 1.5f, -2.75d, "héllo 世界 🚀");
        assertEquals(values, serializer.deserialize(serializer.serialize(values, Values.class),
                Values.class));

        Values extremes = new Values(false, Byte.MIN_VALUE, Short.MIN_VALUE,
                Character.MAX_VALUE, Integer.MIN_VALUE, Long.MAX_VALUE, Float.NaN,
                Double.NEGATIVE_INFINITY, "");
        assertEquals(extremes, serializer.deserialize(
                serializer.serialize(extremes, Values.class), Values.class));
    }

    @Test
    void boxedComponentsSupportAllNulls() {
        Boxed nulls = new Boxed(null, null, null, null, null, null, null, null);
        assertEquals(nulls, serializer.deserialize(serializer.serialize(nulls, Boxed.class),
                Boxed.class));

        Boxed values = new Boxed((byte) 1, (short) 2, 'x', 3, 4L, 5.5f, 6.5d, true);
        assertEquals(values, serializer.deserialize(serializer.serialize(values, Boxed.class),
                Boxed.class));
    }

    @Test
    void recordsLargerThanOneNullMaskGroupRoundTrip() {
        Many many = new Many(1, 2, 3, 4, 5, 6, 7, 8, "nine", 10);
        assertEquals(many, serializer.deserialize(serializer.serialize(many, Many.class),
                Many.class));

        Many nullName = new Many(-1, -2, -3, -4, -5, -6, -7, -8, null, Integer.MAX_VALUE);
        assertEquals(nullName, serializer.deserialize(
                serializer.serialize(nullName, Many.class), Many.class));
    }

    @Test
    void selfReferentialRecordsRoundTrip() {
        Node node = new Node("a", new Node("b", new Node("c", null)));
        assertEquals(node, serializer.deserialize(serializer.serialize(node, Node.class),
                Node.class));
        assertEquals(new Node("only", null), serializer.deserialize(
                serializer.serialize(new Node("only", null), Node.class), Node.class));
    }

    @Test
    void genericRecordsRoundTripThroughReflectiveType() throws Exception {
        Type type = GenericHolder.class.getDeclaredField("box").getGenericType();
        Box<String> box = new Box<>("root", new ArrayList<>(Arrays.asList("a", null, "b")));

        Object decoded = serializer.deserialize(serializer.serialize(box, type), type);
        assertEquals(box, decoded);
    }

    @Test
    void genericCollectionsRoundTripThroughReflectiveType() throws Exception {
        Type type = ListHolder.class.getDeclaredField("items").getGenericType();
        List<String> values = new ArrayList<>(Arrays.asList("a", null, "b", "c"));

        Object decoded = serializer.deserialize(serializer.serialize(values, type), type);
        assertEquals(values, decoded);
    }

    @Test
    void wildcardElementBoundsResolveToUpperBound() {
        Wild wild = new Wild(new ArrayList<>(Arrays.asList("a", null, "b")));
        assertEquals(wild, serializer.deserialize(serializer.serialize(wild, Wild.class),
                Wild.class));
    }

    @Test
    void primitiveAndObjectArraysRoundTrip() {
        boolean[] flags = {true, false, true, true, false, false, true, false, true};
        ArraysHolder holder = new ArraysHolder(
                new byte[]{1, 2, 3},
                flags,
                new char[]{'a', 'Ω', '中'},
                new short[]{Short.MIN_VALUE, 0, Short.MAX_VALUE},
                new int[]{Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE},
                new long[]{Long.MIN_VALUE, 0, Long.MAX_VALUE},
                new float[]{-1.5f, 0f, 2.25f},
                new double[]{-1.5d, 0d, 2.25d},
                new String[]{"a", null, "c"},
                new int[][]{{1, 2}, null, {3}});

        ArraysHolder decoded = serializer.deserialize(
                serializer.serialize(holder, ArraysHolder.class), ArraysHolder.class);

        assertArrayEquals(holder.bytes(), decoded.bytes());
        assertArrayEquals(holder.flags(), decoded.flags());
        assertArrayEquals(holder.chars(), decoded.chars());
        assertArrayEquals(holder.shorts(), decoded.shorts());
        assertArrayEquals(holder.ints(), decoded.ints());
        assertArrayEquals(holder.longs(), decoded.longs());
        assertArrayEquals(holder.floats(), decoded.floats());
        assertArrayEquals(holder.doubles(), decoded.doubles());
        assertArrayEquals(holder.names(), decoded.names());
        assertEquals(holder.matrix().length, decoded.matrix().length);
        assertArrayEquals(holder.matrix()[0], decoded.matrix()[0]);
        assertNull(decoded.matrix()[1]);
        assertArrayEquals(holder.matrix()[2], decoded.matrix()[2]);
    }

    @Test
    void booleanArrayPackingHandlesAllGroupBoundaries() {
        for (int length : new int[]{0, 1, 7, 8, 9, 16, 17, 33}) {
            boolean[] value = new boolean[length];
            for (int index = 0; index < length; index++) {
                value[index] = index % 3 == 0;
            }
            boolean[] decoded = serializer.deserialize(serializer.serialize(value,
                    boolean[].class), boolean[].class);
            assertArrayEquals(value, decoded);
        }
    }

    @Test
    void jdkValueTypesRoundTrip() {
        Jdk value = new Jdk(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                new BigInteger("-123456789012345678901234567890"),
                new BigDecimal(BigInteger.valueOf(12345), -2),
                LocalDate.of(2026, 11, 8),
                LocalTime.of(23, 59, 58, 123_456_789),
                LocalDateTime.of(2026, 11, 8, 12, 30, 45, 999_999_999),
                Instant.ofEpochSecond(-123_456_789L, 123),
                Duration.ofSeconds(-5, 123),
                ZoneOffset.ofHoursMinutes(5, 30),
                ZonedDateTime.of(2026, 3, 29, 1, 30, 0, 0, ZoneId.of("Europe/Paris")));

        assertEquals(value, serializer.deserialize(serializer.serialize(value, Jdk.class),
                Jdk.class));
    }

    @Test
    void enumsRoundTrip() {
        for (Color color : Color.values()) {
            EnumHolder holder = new EnumHolder(color);
            assertEquals(holder, serializer.deserialize(
                    serializer.serialize(holder, EnumHolder.class), EnumHolder.class));
        }
    }

    @Test
    void collectionsMapsAndOptionalsRoundTrip() {
        List<String> list = new ArrayList<>(Arrays.asList("a", null, "c"));
        Set<String> set = new LinkedHashSet<>(Arrays.asList("x", null, "y"));
        Map<String, Point> map = new LinkedHashMap<>();
        map.put("first", new Point(1, 2));
        map.put(null, null);
        map.put("last", null);
        Collections value = new Collections(list, set, map, Optional.of("present"));

        assertEquals(value, serializer.deserialize(serializer.serialize(value, Collections.class),
                Collections.class));

        Collections empty = new Collections(List.of(), Set.of(), Map.of(), Optional.empty());
        assertEquals(empty, serializer.deserialize(serializer.serialize(empty, Collections.class),
                Collections.class));
    }

    @Test
    void stringsRoundTripAtBoundaries() {
        for (String value : new String[]{"", "a", "héllo", "世界", "🚀🚀", "x".repeat(10_000)}) {
            byte[] bytes = serializer.serialize(value, String.class);
            assertEquals(value, serializer.deserialize(bytes, String.class));
        }
    }

    @Test
    void nullAndRuntimeConveniencePathsWork() {
        assertNull(serializer.deserialize(serializer.serialize(null, Person.class),
                Person.class));
        assertNull(serializer.deserialize(serializer.serialize(null, String.class),
                String.class));
        assertThrows(NullPointerException.class, () -> serializer.serialize(null));

        Point point = new Point(10, 20);
        byte[] bytes = serializer.serialize(point);
        assertEquals(point, serializer.deserialize(bytes, Point.class));
    }

    @Test
    void directCodecAccessWorks() {
        Codec<Point> codec = serializer.codec(Point.class);
        assertNotNull(codec);
        BinaryWriter out = new BinaryWriter();
        codec.encode(out, new Point(7, 8));
        assertEquals(new Point(7, 8), codec.decode(new BinaryReader(out.toByteArray())));
    }
}
