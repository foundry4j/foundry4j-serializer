# Foundry4j Serializer

<img src="docs/logo.svg" alt="Foundry4j Serializer logo" align="right" width="128" height="128" />

A compact, zero-dependency binary serialization framework for Java 27, built around records,
sealed hierarchies, and the Java 27 Markdown documentation comments.

![Java 27+](https://img.shields.io/badge/Java-27%2B-ED8B00?logo=openjdk&logoColor=white)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.foundry4j/foundry4j-serializer?logo=apachemaven&color=blue)](https://central.sonatype.com/artifact/io.github.foundry4j/foundry4j-serializer)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=foundry4j_foundry4j-serializer&metric=coverage)](https://sonarcloud.io/summary/new_code?id=foundry4j_foundry4j-serializer)
[![CI](https://github.com/foundry4j/foundry4j-serializer/actions/workflows/ci.yml/badge.svg)](https://github.com/foundry4j/foundry4j-serializer/actions/workflows/ci.yml)

## Overview

Foundry4J Serializer turns records and sealed types into compact binary payloads without
reflection-heavy machinery at encode time and without any third-party runtime dependency. It
derives codecs from the shape of a type, caches them, and then drives a hand-written binary
reader and writer that avoids per-value allocation on the hot path.

```java
record Point(int x, int y) {}

Serializer serializer = Serializer.create();

byte[] bytes = serializer.serialize(new Point(1, 2), Point.class);
Point point = serializer.deserialize(bytes, Point.class);
```

## Highlights

- **Zero runtime dependencies** — only `jdk.incubator.vector` from the JDK is used, optionally.
- **Records first** — record components are resolved once through `MethodHandles` and a cached
  canonical constructor; no field scanning on each call.
- **Sealed polymorphism** — permitted subclasses are discovered automatically and encoded with a
  varint tag, with explicit tag registration available as an override.
- **Compact wire format** — varints, zig-zag signed integers, length-prefixed strings, packed
  booleans, and null bitmaps interleaved per group of eight values.
- **Vectorized integrity check** — an optional 64-bit checksum computed with the Java Vector API,
  with a byte-for-byte identical scalar fallback.
- **Hardened decoding** — configurable limits for payload size, string length, collection size,
  and nesting depth, plus strict rejection of trailing bytes.
- **Java 27 throughout** — Markdown documentation comments (`///`), pattern matching over
  `java.lang.reflect.Type`, sealed interfaces, records, and unnamed variables.

## Requirements

| Component | Version |
| --- | --- |
| JDK | 27 (with `jdk.incubator.vector` for the SIMD checksum) |
| Maven | 3.9+ |

## Installation

Add the dependency to your build. The framework has no transitive runtime dependencies.

```xml
<dependency>
    <groupId>io.github.foundry4j</groupId>
    <artifactId>foundry4j-serializer</artifactId>
    <version>1.0.0</version>
</dependency>
```

The Vector API is an incubator module. When you want the SIMD checksum, launch with:

```text
--add-modules jdk.incubator.vector
```

Without the module, the framework transparently falls back to the scalar implementation. Both
implementations produce the same checksum, so payloads remain interchangeable.

## Usage

### Basic round trip

```java
record Account(long id, String name, List<String> roles) {}

Serializer serializer = Serializer.create();

byte[] data = serializer.serialize(new Account(7, "ada", List.of("admin")), Account.class);
Account account = serializer.deserialize(data, Account.class);
```

Generic records are supported through a reflective parameterized type. Type arguments are
substituted into the component types when the codec is derived:

```java
record Box<T>(T value, List<T> items) {}

Type boxOfString = Holder.class.getDeclaredField("box").getGenericType();

byte[] data = serializer.serialize(new Box<>("v", List.of("a")), boxOfString);
Object box = serializer.deserialize(data, boxOfString);

static final class Holder {
    Box<String> box;
}
```

Any `java.lang.reflect.Type` works, including `ParameterizedType`, `GenericArrayType`, and
wildcard bounds produced by the JDK's reflection API. Passing a raw `Class` for a generic record
is rejected with `SerializationException`, because its type variables cannot be resolved.

### Sealed hierarchies

```java
sealed interface Shape permits Circle, Square {}
record Circle(double radius) implements Shape {}
record Square(double side) implements Shape {}

Serializer serializer = Serializer.create();

byte[] data = serializer.serialize(new Circle(2.0), Shape.class);
Shape shape = serializer.deserialize(data, Shape.class); // a Circle
```

Explicit tags override discovery and let you control the wire representation:

```java
Serializer serializer = Serializer.builder()
        .registerSubtype(Shape.class, 1, Circle.class)
        .registerSubtype(Shape.class, 2, Square.class)
        .build();
```

### Integrity checking

```java
Serializer serializer = Serializer.builder()
        .checksum(true)
        .build();
```

The checksum flag travels in the header byte, so a deserializer always verifies payloads that
declare one, even when checksum generation is disabled locally. Corrupted payloads raise
`SerializationException`.

### Decoding limits

```java
Serializer serializer = Serializer.builder()
        .limits(new Limits(16 * 1024 * 1024, 1_000_000, 1_000_000, 64))
        .build();
```

`Limits.DEFAULT` allows 64 MiB payloads, 16 Mi character strings, 16 Mi collection elements, and
a nesting depth of 512. Limits are validated when the record is constructed.

### Custom codecs

```java
Serializer serializer = Serializer.builder()
        .register(Money.class, new Codec<>() {
            @Override public void encode(BinaryWriter out, Money value) { ... }
            @Override public Money decode(BinaryReader in) { ... }
        })
        .build();
```

Compose codecs with the `Codecs` factories: `listOf`, `setOf`, `mapOf`, `optionalOf`,
`arrayOf`, and `nullable`.

## Wire format

Every payload starts with a single header byte: the format version in the low seven bits and the
checksum flag in the high bit. A presence byte follows, where `0` denotes `null` and `1` denotes
a present value. The value payload follows in the shape defined by its codec.

| Type | Encoding |
| --- | --- |
| `boolean` | one byte, `0` or `1` |
| `byte`, `short`, `char`, `float`, `double` | little-endian, fixed width |
| `int`, `long` | zig-zag varint |
| `String` | varint UTF-8 byte length followed by the encoded bytes |
| `boolean[]` | varint length followed by a bit-packed mask |
| primitive arrays | varint length followed by contiguous little-endian elements |
| object arrays, collections, maps | varint size and one null-mask byte per group of eight |
| sealed values | varint subtype tag followed by the subtype payload |

The checksum, when present, is eight little-endian bytes appended after the payload and covers
the presence byte and the value payload.

## Performance notes

- Codecs are resolved once per `Type` and cached in a concurrent map.
- Record accessors are adapted to plain `(Object)Object` method handles at construction time.
- `BinaryWriter` grows geometrically and exposes a `VarHandle` little-endian view, avoiding
  `ByteBuffer` allocation and byte-by-byte shifts.
- Null masks are interleaved with values rather than stored as a separate bitmap, which keeps
  writes cache friendly.
- The SIMD checksum processes 128-bit lanes with `LongVector.SPECIES_128` on top of a
  `MemorySegment` view of the heap buffer.

## Thread safety

`Serializer` is immutable after `build()`. Codec resolution and the type cache are thread-safe,
so a single serializer instance can be shared across all threads.