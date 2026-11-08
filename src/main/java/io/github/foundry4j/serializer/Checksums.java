package io.github.foundry4j.serializer;

/// Creates the best available checksum implementation.
///
/// The SIMD implementation is used only when the `jdk.incubator.vector` module is present in
/// the boot layer. The class reference is isolated in [SimdChecksum] so that applications that
/// do not add the module can still use the framework.
final class Checksums {

    private Checksums() {
    }

    /// Returns a SIMD checksum when the Vector API is available, otherwise a scalar checksum.
    ///
    /// @return a checksum implementation
    static Checksum create() {
        if (ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent()) {
            try {
                return new SimdChecksum();
            } catch (Throwable _) {
                // Fall through to the scalar implementation.
            }
        }
        return new ScalarChecksum();
    }

    /// Mixes the partial sums into the final 64-bit hash.
    ///
    /// @param length   the hashed length in bytes
    /// @param sum      the sum of 64-bit blocks and trailing bytes
    /// @param weighted the position-weighted sum of 64-bit blocks and trailing bytes
    /// @return the final hash
    static long mix(int length, long sum, long weighted) {
        long hash = 0x9E3779B97F4A7C15L ^ ((long) length * 0xC2B2AE3D27D4EB4FL);
        hash = (hash ^ sum) * 0xFF51AFD7ED558CCDL;
        hash = (hash ^ weighted) * 0xC4CEB9FE1A85EC53L;
        return hash ^ (hash >>> 33);
    }
}
