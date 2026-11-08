package io.github.foundry4j.serializer.internal;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/// Reflection helpers for generic type substitution.
///
/// Generic records such as `record Box<T>(T value)` need their type variables replaced with the
/// actual type arguments before a codec can be resolved. The helpers in this class create
/// lightweight [Type] implementations with value-based equality so that resolved codecs can be
/// cached.
public final class Types {

    private Types() {
    }

    /// Replaces type variables according to a binding map.
    ///
    /// @param type     the type to transform
    /// @param bindings the type-variable bindings
    /// @return the transformed type, or the original type when no binding applies
    public static Type substitute(Type type, Map<TypeVariable<?>, Type> bindings) {
        if (bindings.isEmpty()) {
            return type;
        }
        return switch (type) {
            case Class<?> clazz -> clazz;
            case TypeVariable<?> variable -> bindings.getOrDefault(variable, variable);
            case ParameterizedType parameterized -> new ParameterizedTypeImpl(
                    parameterized.getOwnerType() == null
                            ? null
                            : substitute(parameterized.getOwnerType(), bindings),
                    parameterized.getRawType(),
                    substituteAll(parameterized.getActualTypeArguments(), bindings));
            case GenericArrayType array -> new GenericArrayTypeImpl(
                    substitute(array.getGenericComponentType(), bindings));
            case WildcardType wildcard -> new WildcardTypeImpl(
                    substituteAll(wildcard.getUpperBounds(), bindings),
                    substituteAll(wildcard.getLowerBounds(), bindings));
            default -> type;
        };
    }

    private static Type[] substituteAll(Type[] types, Map<TypeVariable<?>, Type> bindings) {
        Type[] result = new Type[types.length];
        for (int index = 0; index < types.length; index++) {
            result[index] = substitute(types[index], bindings);
        }
        return result;
    }

    /// A parameterized type with value-based equality.
    ///
    /// @param ownerType     the enclosing type, or `null`
    /// @param rawType       the raw class
    /// @param typeArguments the actual type arguments
    private record ParameterizedTypeImpl(Type ownerType, Type rawType, Type[] typeArguments)
            implements ParameterizedType {

        @Override
        public Type[] getActualTypeArguments() {
            return typeArguments.clone();
        }

        @Override
        public Type getRawType() {
            return rawType;
        }

        @Override
        public Type getOwnerType() {
            return ownerType;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ParameterizedType that
                    && Objects.equals(ownerType, that.getOwnerType())
                    && rawType.equals(that.getRawType())
                    && Arrays.equals(typeArguments, that.getActualTypeArguments());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(typeArguments) ^ Objects.hashCode(ownerType) ^ rawType.hashCode();
        }
    }

    /// A generic array type with value-based equality.
    ///
    /// @param genericComponentType the component type
    private record GenericArrayTypeImpl(Type genericComponentType) implements GenericArrayType {

        @Override
        public Type getGenericComponentType() {
            return genericComponentType;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof GenericArrayType that
                    && genericComponentType.equals(that.getGenericComponentType());
        }

        @Override
        public int hashCode() {
            return genericComponentType.hashCode();
        }
    }

    /// A wildcard type with value-based equality.
    ///
    /// @param upperBounds the upper bounds
    /// @param lowerBounds the lower bounds
    private record WildcardTypeImpl(Type[] upperBounds, Type[] lowerBounds) implements WildcardType {

        @Override
        public Type[] getUpperBounds() {
            return upperBounds.clone();
        }

        @Override
        public Type[] getLowerBounds() {
            return lowerBounds.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof WildcardType that
                    && Arrays.equals(upperBounds, that.getUpperBounds())
                    && Arrays.equals(lowerBounds, that.getLowerBounds());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(upperBounds) ^ Arrays.hashCode(lowerBounds);
        }
    }
}
