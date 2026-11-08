package io.github.foundry4j.serializer;

/// Portable scalar implementation of the integrity hash.
///
/// The SIMD implementation in [SimdChecksum] must produce identical results; both follow the
/// same mathematical definition:
///
/// - every complete 8-byte little-endian block is a value weighted by its one-based index;
/// - every trailing byte is a value weighted by the one-based index that follows the last
///   complete block.
final class ScalarChecksum implements Checksum {

    @Override
    public long hash(byte[] data, int offset, int length) {
        int blocks = length >>> 3;
        long sum = 0;
        long weighted = 0;
        for (int block = 0; block < blocks; block++) {
            long value = BinaryReader.readLongLE(data, offset + (block << 3));
            sum += value;
            weighted += value * (block + 1L);
        }
        int tailStart = offset + (blocks << 3);
        int tailLength = length & 7;
        for (int index = 0; index < tailLength; index++) {
            long value = data[tailStart + index] & 0xFFL;
            sum += value;
            weighted += value * (blocks + index + 1L);
        }
        return Checksums.mix(length, sum, weighted);
    }
}
