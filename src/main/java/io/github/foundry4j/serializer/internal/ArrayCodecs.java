package io.github.foundry4j.serializer.internal;

import io.github.foundry4j.serializer.BinaryReader;
import io.github.foundry4j.serializer.BinaryWriter;
import io.github.foundry4j.serializer.Codec;
import io.github.foundry4j.serializer.SerializationException;

import java.lang.reflect.Array;

/// Codecs for primitive arrays and object arrays.
///
/// Primitive arrays use fixed-width little-endian elements so that large numeric arrays can be
/// copied with predictable cost. Boolean arrays are bit-packed. Object arrays interleave a null
/// map with each group of eight elements.
public final class ArrayCodecs {

    private ArrayCodecs() {
    }

    /// Creates a codec for a primitive array type.
    ///
    /// @param arrayType the primitive array class
    /// @return the codec
    /// @throws SerializationException if the component type is not primitive
    public static Codec<?> primitive(Class<?> arrayType) {
        Class<?> component = arrayType.getComponentType();
        if (component == byte.class) {
            return BYTE_ARRAY;
        }
        if (component == boolean.class) {
            return BOOLEAN_ARRAY;
        }
        if (component == char.class) {
            return CHAR_ARRAY;
        }
        if (component == short.class) {
            return SHORT_ARRAY;
        }
        if (component == int.class) {
            return INT_ARRAY;
        }
        if (component == long.class) {
            return LONG_ARRAY;
        }
        if (component == float.class) {
            return FLOAT_ARRAY;
        }
        if (component == double.class) {
            return DOUBLE_ARRAY;
        }
        throw new SerializationException("Not a primitive array type: " + arrayType.getName());
    }

    private static int readElements(BinaryReader in, String description, int elementSize) {
        int length = in.readCollectionSize(description);
        if ((long) length * elementSize > in.remaining()) {
            throw new SerializationException("Truncated " + description);
        }
        return length;
    }

    private static final Codec<byte[]> BYTE_ARRAY = new Codec<>() {
        @Override
        public void encode(BinaryWriter out, byte[] value) {
            out.writeVarInt(value.length);
            out.writeBytes(value);
        }

        @Override
        public byte[] decode(BinaryReader in) {
            return in.readByteArray();
        }
    };

    private static final Codec<boolean[]> BOOLEAN_ARRAY = new Codec<>() {
        @Override
        public void encode(BinaryWriter out, boolean[] value) {
            out.writeVarInt(value.length);
            for (int start = 0; start < value.length; start += 8) {
                int mask = 0;
                int end = Math.min(start + 8, value.length);
                for (int index = start; index < end; index++) {
                    if (value[index]) {
                        mask |= 1 << (index - start);
                    }
                }
                out.writeByte(mask);
            }
        }

        @Override
        public boolean[] decode(BinaryReader in) {
            int length = in.readCollectionSize("boolean[]");
            int packedLength = (length + 7) >>> 3;
            if (packedLength > in.remaining()) {
                throw new SerializationException("Truncated boolean[]");
            }
            boolean[] value = new boolean[length];
            for (int start = 0; start < length; start += 8) {
                int mask = in.readUnsignedByte();
                int end = Math.min(start + 8, length);
                for (int index = start; index < end; index++) {
                    value[index] = (mask & (1 << (index - start))) != 0;
                }
            }
            return value;
        }
    };

    private static final Codec<char[]> CHAR_ARRAY = new Codec<>() {
        @Override
        public void encode(BinaryWriter out, char[] value) {
            out.writeVarInt(value.length);
            for (char element : value) {
                out.writeChar(element);
            }
        }

        @Override
        public char[] decode(BinaryReader in) {
            int length = readElements(in, "char[]", Character.BYTES);
            char[] value = new char[length];
            for (int index = 0; index < length; index++) {
                value[index] = in.readChar();
            }
            return value;
        }
    };

    private static final Codec<short[]> SHORT_ARRAY = new Codec<>() {
        @Override
        public void encode(BinaryWriter out, short[] value) {
            out.writeVarInt(value.length);
            for (short element : value) {
                out.writeShort(element);
            }
        }

        @Override
        public short[] decode(BinaryReader in) {
            int length = readElements(in, "short[]", Short.BYTES);
            short[] value = new short[length];
            for (int index = 0; index < length; index++) {
                value[index] = in.readShort();
            }
            return value;
        }
    };

    private static final Codec<int[]> INT_ARRAY = new Codec<>() {
        @Override
        public void encode(BinaryWriter out, int[] value) {
            out.writeVarInt(value.length);
            for (int element : value) {
                out.writeInt(element);
            }
        }

        @Override
        public int[] decode(BinaryReader in) {
            int length = readElements(in, "int[]", Integer.BYTES);
            int[] value = new int[length];
            for (int index = 0; index < length; index++) {
                value[index] = in.readInt();
            }
            return value;
        }
    };

    private static final Codec<long[]> LONG_ARRAY = new Codec<>() {
        @Override
        public void encode(BinaryWriter out, long[] value) {
            out.writeVarInt(value.length);
            for (long element : value) {
                out.writeLong(element);
            }
        }

        @Override
        public long[] decode(BinaryReader in) {
            int length = readElements(in, "long[]", Long.BYTES);
            long[] value = new long[length];
            for (int index = 0; index < length; index++) {
                value[index] = in.readLong();
            }
            return value;
        }
    };

    private static final Codec<float[]> FLOAT_ARRAY = new Codec<>() {
        @Override
        public void encode(BinaryWriter out, float[] value) {
            out.writeVarInt(value.length);
            for (float element : value) {
                out.writeFloat(element);
            }
        }

        @Override
        public float[] decode(BinaryReader in) {
            int length = readElements(in, "float[]", Float.BYTES);
            float[] value = new float[length];
            for (int index = 0; index < length; index++) {
                value[index] = in.readFloat();
            }
            return value;
        }
    };

    private static final Codec<double[]> DOUBLE_ARRAY = new Codec<>() {
        @Override
        public void encode(BinaryWriter out, double[] value) {
            out.writeVarInt(value.length);
            for (double element : value) {
                out.writeDouble(element);
            }
        }

        @Override
        public double[] decode(BinaryReader in) {
            int length = readElements(in, "double[]", Double.BYTES);
            double[] value = new double[length];
            for (int index = 0; index < length; index++) {
                value[index] = in.readDouble();
            }
            return value;
        }
    };

    /// Codec for object arrays.
    ///
    /// @param <T> the component type
    public static final class ObjectArrayCodec<T> implements Codec<T[]> {

        private final Class<?> componentType;
        private final Codec<Object> elementCodec;

        /// Creates an object-array codec.
        ///
        /// @param componentType the runtime component class used to allocate arrays
        /// @param elementCodec  the codec for non-null elements
        @SuppressWarnings("unchecked")
        public ObjectArrayCodec(Class<?> componentType, Codec<?> elementCodec) {
            this.componentType = componentType;
            this.elementCodec = (Codec<Object>) elementCodec;
        }

        @Override
        public void encode(BinaryWriter out, T[] value) {
            out.writeVarInt(value.length);
            for (int start = 0; start < value.length; start += 8) {
                int maskOffset = out.size();
                out.writeByte(0);
                int mask = 0;
                int end = Math.min(start + 8, value.length);
                for (int index = start; index < end; index++) {
                    Object element = value[index];
                    if (element != null) {
                        mask |= 1 << (index - start);
                        elementCodec.encode(out, element);
                    }
                }
                out.patchByte(maskOffset, mask);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public T[] decode(BinaryReader in) {
            int length = in.readCollectionSize("array");
            int packedLength = (length + 7) >>> 3;
            if (packedLength > in.remaining()) {
                throw new SerializationException("Truncated array");
            }
            Object[] value = (Object[]) Array.newInstance(componentType, length);
            in.enter();
            try {
                for (int start = 0; start < length; start += 8) {
                    int mask = in.readUnsignedByte();
                    int end = Math.min(start + 8, length);
                    for (int index = start; index < end; index++) {
                        if ((mask & (1 << (index - start))) != 0) {
                            value[index] = elementCodec.decode(in);
                        }
                    }
                }
            } finally {
                in.exit();
            }
            return (T[]) value;
        }
    }
}
