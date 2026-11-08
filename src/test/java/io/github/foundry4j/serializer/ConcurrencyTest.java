package io.github.foundry4j.serializer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConcurrencyTest {

    record Point(int x, int y) {
    }

    record Person(String name, int age, Point location, List<String> tags,
                  Optional<Point> alternate) {
    }

    sealed interface Shape permits Circle, Square {
    }

    record Circle(double radius) implements Shape {
    }

    record Square(double side) implements Shape {
    }

    record Document(Shape shape, Person owner, Map<String, List<Integer>> values) {
    }

    @Test
    void codecResolutionAndRoundTripsAreThreadSafe() {
        Serializer serializer = Serializer.create();
        AtomicInteger failures = new AtomicInteger();

        IntStream.range(0, 500).parallel().forEach(index -> {
            Document document = new Document(
                    index % 2 == 0 ? new Circle(index) : new Square(index),
                    new Person("person-" + index, index, new Point(index, -index),
                            new ArrayList<>(List.of("tag-" + index)), Optional.of(new Point(1, 2))),
                    Map.of("values", List.of(index, index + 1)));
            byte[] bytes = serializer.serialize(document, Document.class);
            Document decoded = serializer.deserialize(bytes, Document.class);
            if (!document.equals(decoded)) {
                failures.incrementAndGet();
            }
        });

        assertEquals(0, failures.get());
    }
}
