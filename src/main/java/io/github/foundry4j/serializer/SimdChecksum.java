package io.github.foundry4j.serializer;

import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;

/// SIMD implementation of the integrity hash using the Java Vector API.
///
/// The 128-bit species processes two 64-bit blocks per iteration. The lane index vector makes
/// every weight explicit, and all arithmetic is performed modulo 2^64, which matches the
/// scalar implementation exactly.
///
/// This class is loaded only when the `jdk.incubator.vector` module is present.
final class SimdChecksum implements Checksum {

    private static final VectorSpecies<Long> SPECIES = LongVector.SPECIES_128;
    private static final LongVector LANE_INDEX =
            LongVector.fromArray(SPECIES, new long[]{0L, 1L}, 0);

    @Override
    public long hash(byte[] data, int offset, int length) {
        MemorySegment segment = MemorySegment.ofArray(data);
        LongVector sumVector = LongVector.broadcast(SPECIES, 0L);
        LongVector weightedVector = LongVector.broadcast(SPECIES, 0L);

        int blocks = length >>> 3;
        int vectorBlocks = blocks & ~1;
        int cursor = offset;
        for (int block = 0; block < vectorBlocks; block += 2) {
            LongVector values = LongVector.fromMemorySegment(
                    SPECIES, segment, cursor, ByteOrder.LITTLE_ENDIAN);
            LongVector weights = LANE_INDEX.add(block + 1L);
            sumVector = sumVector.add(values);
            weightedVector = weightedVector.add(values.mul(weights));
            cursor += 16;
        }

        long sum = sumVector.reduceLanes(VectorOperators.ADD);
        long weighted = weightedVector.reduceLanes(VectorOperators.ADD);

        if ((blocks & 1) != 0) {
            long value = BinaryReader.readLongLE(data, offset + (vectorBlocks << 3));
            sum += value;
            weighted += value * (vectorBlocks + 1L);
            cursor += 8;
        }

        int tailLength = length & 7;
        for (int index = 0; index < tailLength; index++) {
            long value = data[cursor + index] & 0xFFL;
            sum += value;
            weighted += value * (blocks + index + 1L);
        }
        return Checksums.mix(length, sum, weighted);
    }
}
