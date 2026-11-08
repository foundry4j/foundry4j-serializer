/// Foundry4J Serializer is a compact, reflection-light binary serialization framework for Java 27.
///
/// The framework is built around a small set of primitives:
///
/// - [io.github.foundry4j.serializer.BinaryWriter] and [io.github.foundry4j.serializer.BinaryReader]
///   implement a compact little-endian wire format with variable-length integers.
/// - [io.github.foundry4j.serializer.Codec] describes how a single Java type is encoded and decoded.
/// - [io.github.foundry4j.serializer.Serializer] composes codecs and exposes the high-level API.
///
/// Records, sealed hierarchies, enums, primitive arrays, collections, maps, optionals and the
/// common `java.time` value types are supported out of the box. No runtime dependency outside
/// the JDK is required.
package io.github.foundry4j.serializer;
