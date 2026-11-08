package io.github.foundry4j.serializer.internal;

import io.github.foundry4j.serializer.BinaryReader;
import io.github.foundry4j.serializer.BinaryWriter;
import io.github.foundry4j.serializer.Codec;
import io.github.foundry4j.serializer.SerializationException;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Map;

/// Codec for Java records.
///
/// Record components are encoded in declaration order. A compact null map of one byte per
/// eight components is interleaved with the values, so a record with mostly non-null reference
/// components costs only one bit per component. Primitive components never occupy a null bit.
///
/// The codec resolves component accessors through `MethodHandle`s during construction and uses
/// the canonical constructor during decoding. No reflection happens per component value.
///
/// @param <T> the record type
public final class RecordCodec<T> implements Codec<T> {

    private static final int GROUP_SIZE = 8;

    private final Class<T> type;
    private final Component[] components;
    private final Constructor<T> constructor;
    private final boolean hasReferences;

    /// Creates a codec for a record type.
    ///
    /// @param type     the record class
    /// @param registry the registry used to resolve component codecs
    /// @param bindings type-variable bindings for generic records
    /// @throws SerializationException if the type is not a record or an accessor is inaccessible
    @SuppressWarnings("unchecked")
    public RecordCodec(Class<T> type, CodecRegistry registry, Map<TypeVariable<?>, Type> bindings) {
        this.type = type;
        RecordComponent[] recordComponents = type.getRecordComponents();
        if (recordComponents == null) {
            throw new SerializationException("Not a record type: " + type.getName());
        }

        MethodHandles.Lookup lookup = lookupFor(type);
        Class<?>[] parameterTypes = new Class<?>[recordComponents.length];
        this.components = new Component[recordComponents.length];
        boolean references = false;

        for (int index = 0; index < recordComponents.length; index++) {
            RecordComponent component = recordComponents[index];
            Class<?> componentType = component.getType();
            parameterTypes[index] = componentType;
            MethodHandle getter;
            try {
                getter = lookup.unreflect(component.getAccessor())
                        .asType(MethodType.methodType(Object.class, Object.class));
            } catch (IllegalAccessException exception) {
                throw new SerializationException(
                        "Cannot access record component '" + component.getName() + "' of "
                                + type.getName() + "; open the package to the serializer",
                        exception);
            }
            var resolvedType = Types.substitute(component.getGenericType(), bindings);
            Codec<Object> codec = registry.deferred(resolvedType);
            boolean primitive = componentType.isPrimitive();
            references |= !primitive;
            this.components[index] = new Component(getter, codec, primitive);
        }
        this.hasReferences = references;
        this.constructor = canonicalConstructor(type, parameterTypes);
    }

    @Override
    public void encode(BinaryWriter out, T value) {
        try {
            for (int groupStart = 0; groupStart < components.length; groupStart += GROUP_SIZE) {
                int groupEnd = Math.min(groupStart + GROUP_SIZE, components.length);
                int maskOffset = -1;
                int mask = 0;
                if (hasReferences) {
                    maskOffset = out.size();
                    out.writeByte(0);
                }
                for (int index = groupStart; index < groupEnd; index++) {
                    Component component = components[index];
                    Object componentValue = component.getter.invokeExact(value);
                    if (component.primitive) {
                        component.codec.encode(out, componentValue);
                    } else if (componentValue != null) {
                        mask |= 1 << (index - groupStart);
                        component.codec.encode(out, componentValue);
                    }
                }
                if (hasReferences) {
                    out.patchByte(maskOffset, mask);
                }
            }
        } catch (SerializationException exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw new SerializationException("Failed to encode record " + type.getName(), throwable);
        }
    }

    @Override
    public T decode(BinaryReader in) {
        in.enter();
        try {
            Object[] arguments = new Object[components.length];
            for (int groupStart = 0; groupStart < components.length; groupStart += GROUP_SIZE) {
                int groupEnd = Math.min(groupStart + GROUP_SIZE, components.length);
                int mask = hasReferences ? in.readUnsignedByte() : 0;
                for (int index = groupStart; index < groupEnd; index++) {
                    Component component = components[index];
                    if (!component.primitive && (mask & (1 << (index - groupStart))) == 0) {
                        arguments[index] = null;
                    } else {
                        arguments[index] = component.codec.decode(in);
                    }
                }
            }
            return type.cast(constructor.newInstance(arguments));
        } catch (SerializationException exception) {
            throw exception;
        } catch (ReflectiveOperationException exception) {
            throw new SerializationException("Failed to construct record " + type.getName(), exception);
        } finally {
            in.exit();
        }
    }

    private static MethodHandles.Lookup lookupFor(Class<?> type) {
        try {
            return MethodHandles.privateLookupIn(type, MethodHandles.lookup());
        } catch (IllegalAccessException _) {
            return MethodHandles.publicLookup();
        }
    }

    private static <T> Constructor<T> canonicalConstructor(Class<T> type, Class<?>[] parameterTypes) {
        try {
            Constructor<T> constructor = type.getDeclaredConstructor(parameterTypes);
            constructor.trySetAccessible();
            return constructor;
        } catch (NoSuchMethodException exception) {
            throw new SerializationException(
                    "Missing canonical constructor for record " + type.getName(), exception);
        }
    }

    /// A prepared record component.
    ///
    /// @param getter    a handle of type `(Object)Object` that reads the component
    /// @param codec     the component codec
    /// @param primitive whether the component has a primitive type
    private record Component(MethodHandle getter, Codec<Object> codec, boolean primitive) {
    }
}
