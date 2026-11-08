package io.github.foundry4j.serializer;

/// Computes a non-cryptographic integrity hash over a byte range.
///
/// Both implementations, scalar and SIMD, compute exactly the same value so payloads can be
/// exchanged between machines that do or do not have the Vector API module available.
sealed interface Checksum permits ScalarChecksum, SimdChecksum {

    /// Computes the 64-bit hash of a byte range.
    ///
    /// @param data   the source array
    /// @param offset the first index
    /// @param length the number of bytes
    /// @return the 64-bit hash
    long hash(byte[] data, int offset, int length);
}
