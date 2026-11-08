package io.github.foundry4j.serializer;

/// Hard limits applied while decoding untrusted input.
///
/// Limits protect the process against malformed or malicious payloads that announce huge
/// strings, collections, or deeply nested object graphs. Encoding is not limited because the
/// encoder trusts the values it receives.
///
/// @param maxBytes          the maximum number of bytes accepted by a [BinaryReader]
/// @param maxStringLength   the maximum UTF-8 byte length of a decoded string
/// @param maxCollectionSize the maximum element count of a decoded collection, map, or array
/// @param maxDepth          the maximum nesting depth accepted while decoding
public record Limits(int maxBytes, int maxStringLength, int maxCollectionSize, int maxDepth) {

    /// A balanced default suitable for typical application payloads.
    public static final Limits DEFAULT =
            new Limits(64 * 1024 * 1024, 16 * 1024 * 1024, 16_777_216, 512);

    /// Validates that every limit is strictly positive.
    ///
    /// @throws IllegalArgumentException if a limit is zero or negative
    public Limits {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive: " + maxBytes);
        }
        if (maxStringLength <= 0) {
            throw new IllegalArgumentException("maxStringLength must be positive: " + maxStringLength);
        }
        if (maxCollectionSize <= 0) {
            throw new IllegalArgumentException("maxCollectionSize must be positive: " + maxCollectionSize);
        }
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("maxDepth must be positive: " + maxDepth);
        }
    }
}
