package io.github.foundry4j.serializer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PolymorphismTest {

    sealed interface Shape permits Circle, Square {
    }

    record Circle(double radius) implements Shape {
    }

    record Square(double side) implements Shape {
    }

    interface Event {
    }

    record Login(String user) implements Event {
    }

    record Logout(String user) implements Event {
    }

    record Other() implements Event {
    }

    @Test
    void sealedHierarchyRoundTripsBothArmTypes() {
        Serializer serializer = Serializer.create();
        Shape circle = new Circle(1.5);
        Shape square = new Square(2.5);

        assertEquals(circle, serializer.deserialize(serializer.serialize(circle, Shape.class),
                Shape.class));
        assertEquals(square, serializer.deserialize(serializer.serialize(square, Shape.class),
                Shape.class));
    }

    @Test
    void explicitlyRegisteredSubtypesUseCustomTags() {
        Serializer serializer = Serializer.builder()
                .registerSubtype(Event.class, 5, Login.class)
                .registerSubtype(Event.class, 9, Logout.class)
                .build();

        Event login = new Login("ada");
        Event logout = new Logout("grace");
        assertEquals(login, serializer.deserialize(serializer.serialize(login, Event.class),
                Event.class));
        assertEquals(logout, serializer.deserialize(serializer.serialize(logout, Event.class),
                Event.class));
    }

    @Test
    void unregisteredSubtypeIsRejected() {
        Serializer serializer = Serializer.builder()
                .registerSubtype(Event.class, 0, Login.class)
                .build();
        assertThrows(SerializationException.class,
                () -> serializer.serialize(new Other(), Event.class));
    }

    @Test
    void unknownTagIsRejected() {
        Serializer serializer = Serializer.builder()
                .registerSubtype(Event.class, 5, Login.class)
                .registerSubtype(Event.class, 9, Logout.class)
                .build();
        BinaryWriter out = new BinaryWriter();
        out.writeByte(1);
        out.writeByte(1);
        out.writeVarInt(7);

        assertThrows(SerializationException.class,
                () -> serializer.deserialize(out.toByteArray(), Event.class));
    }

    @Test
    void explicitRegistrationTakesPrecedenceOverSealedDiscovery() {
        Serializer serializer = Serializer.builder()
                .registerSubtype(Shape.class, 0, Circle.class)
                .build();
        assertThrows(SerializationException.class,
                () -> serializer.serialize(new Square(1), Shape.class));
    }

    @Test
    void builderRejectsInvalidSubtypeRegistrations() {
        Serializer.Builder builder = Serializer.builder();
        assertThrows(IllegalArgumentException.class,
                () -> builder.registerSubtype(Event.class, -1, Login.class));
        assertThrows(IllegalArgumentException.class,
                () -> builder.registerSubtype(Event.class, 0, String.class));

        builder.registerSubtype(Event.class, 1, Login.class);
        assertThrows(SerializationException.class,
                () -> builder.registerSubtype(Event.class, 2, Login.class));
        assertThrows(SerializationException.class,
                () -> builder.registerSubtype(Event.class, 1, Logout.class));
    }
}
