package io.github.foundry4j.serializer.internal;

import io.github.foundry4j.serializer.Codec;
import io.github.foundry4j.serializer.SerializationException;
import io.github.foundry4j.serializer.internal.CollectionCodecs.ListCodec;
import io.github.foundry4j.serializer.internal.CollectionCodecs.MapCodec;
import io.github.foundry4j.serializer.internal.CollectionCodecs.OptionalCodec;
import io.github.foundry4j.serializer.internal.CollectionCodecs.SetCodec;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/// Resolves and caches codecs for Java types.
///
/// The registry is immutable after construction. Codecs for records and parameterized types are
/// resolved lazily and cached in a concurrent map, which makes resolution safe under concurrent
/// first use.
public final class CodecRegistry {

    private final Map<Class<?>, Codec<?>> explicit;
    private final Map<Class<?>, Map<Integer, Class<?>>> explicitSubtypes;
    private final ConcurrentHashMap<Type, Codec<?>> cache = new ConcurrentHashMap<>();

    /// Creates a registry.
    ///
    /// @param custom           user-registered codecs, keyed by class
    /// @param explicitSubtypes user-registered subtype mappings
    public CodecRegistry(Map<Class<?>, Codec<?>> custom,
                         Map<Class<?>, Map<Integer, Class<?>>> explicitSubtypes) {
        Map<Class<?>, Codec<?>> merged = builtins();
        merged.putAll(custom);
        this.explicit = Map.copyOf(merged);
        this.explicitSubtypes = Map.copyOf(explicitSubtypes);
    }

    /// Returns the codec for a class.
    ///
    /// @param <T>  the type
    /// @param type the class
    /// @return the codec
    public <T> Codec<T> codec(Class<T> type) {
        @SuppressWarnings("unchecked")
        Codec<T> codec = (Codec<T>) resolve(type);
        return codec;
    }

    /// Resolves a codec for any reflective type.
    ///
    /// @param type the type
    /// @return the codec
    /// @throws SerializationException if no codec can be built
    public Codec<?> resolve(Type type) {
        Objects.requireNonNull(type, "type");
        Codec<?> cached = cache.get(type);
        if (cached != null) {
            return cached;
        }
        Codec<?> built = build(type);
        Codec<?> previous = cache.putIfAbsent(type, built);
        return previous != null ? previous : built;
    }

    /// Creates a lazily resolved codec.
    ///
    /// @param <T>  the encoded type
    /// @param type the target type
    /// @return a codec that resolves on first use
    public <T> Codec<T> deferred(Type type) {
        return new DeferredCodec<>(this, type);
    }

    private Codec<?> build(Type type) {
        return switch (type) {
            case Class<?> clazz -> buildClass(clazz);
            case ParameterizedType parameterized -> buildParameterized(parameterized);
            case GenericArrayType array -> new ArrayCodecs.ObjectArrayCodec<>(
                    Object.class, deferred(array.getGenericComponentType()));
            case WildcardType wildcard -> buildWildcard(wildcard);
            case TypeVariable<?> variable -> buildVariable(variable);
            default -> throw new SerializationException("Unsupported type: " + type.getTypeName());
        };
    }

    private Codec<?> buildClass(Class<?> clazz) {
        Codec<?> registered = explicit.get(clazz);
        if (registered != null) {
            return registered;
        }
        Map<Integer, Class<?>> subtypes = explicitSubtypes.get(clazz);
        if (subtypes != null) {
            return new PolymorphicCodec<>(clazz, subtypes, this);
        }
        if (clazz.isArray()) {
            Class<?> component = clazz.getComponentType();
            if (component.isPrimitive()) {
                return ArrayCodecs.primitive(clazz);
            }
            return new ArrayCodecs.ObjectArrayCodec<>(component, deferred(component));
        }
        if (clazz.isEnum()) {
            return new BuiltinCodecs.EnumCodec<>(clazz.asSubclass(Enum.class));
        }
        if (clazz.isRecord()) {
            return new RecordCodec<>(clazz, this, Map.of());
        }
        if (clazz.isSealed()) {
            return new PolymorphicCodec<>(clazz, this);
        }
        if (clazz == Object.class) {
            throw new SerializationException(
                    "Object has no inherent representation; register a concrete codec");
        }
        if (clazz == List.class || clazz == Collection.class || clazz == Iterable.class
                || clazz == Set.class || clazz == Map.class) {
            throw new SerializationException(
                    "Raw collection type " + clazz.getName() + " requires generic arguments");
        }
        throw new SerializationException("No codec registered for " + clazz.getName());
    }

    private Codec<?> buildParameterized(ParameterizedType parameterized) {
        Type rawType = parameterized.getRawType();
        if (!(rawType instanceof Class<?> raw)) {
            throw new SerializationException(
                    "Unsupported parameterized type: " + parameterized.getTypeName());
        }
        Type[] arguments = parameterized.getActualTypeArguments();

        if (raw == List.class || raw == Collection.class || raw == Iterable.class
                || raw == ArrayList.class) {
            return new ListCodec<>(deferred(argument(arguments, 0, parameterized)));
        }
        if (raw == Set.class || raw == HashSet.class || raw == LinkedHashSet.class) {
            return new SetCodec<>(deferred(argument(arguments, 0, parameterized)));
        }
        if (raw == Map.class || raw == HashMap.class || raw == LinkedHashMap.class) {
            return new MapCodec<>(
                    deferred(argument(arguments, 0, parameterized)),
                    deferred(argument(arguments, 1, parameterized)));
        }
        if (raw == Optional.class) {
            return new OptionalCodec<>(deferred(argument(arguments, 0, parameterized)));
        }
        if (raw.isRecord()) {
            return new RecordCodec<>(raw, this, bindings(raw, arguments));
        }
        Codec<?> registered = explicit.get(raw);
        if (registered != null) {
            return registered;
        }
        throw new SerializationException(
                "Unsupported parameterized type: " + parameterized.getTypeName());
    }

    private Codec<?> buildWildcard(WildcardType wildcard) {
        Type[] bounds = wildcard.getUpperBounds();
        if (bounds.length == 0) {
            throw new SerializationException("Wildcard without an upper bound");
        }
        return build(bounds[0]);
    }

    private Codec<?> buildVariable(TypeVariable<?> variable) {
        Type[] bounds = variable.getBounds();
        if (bounds.length == 0) {
            throw new SerializationException("Type variable without a bound: " + variable.getName());
        }
        return build(bounds[0]);
    }

    private static Type argument(Type[] arguments, int index, ParameterizedType type) {
        if (arguments.length <= index) {
            throw new SerializationException(
                    "Missing type argument " + index + " for " + type.getTypeName());
        }
        return arguments[index];
    }

    private static Map<TypeVariable<?>, Type> bindings(Class<?> raw, Type[] arguments) {
        TypeVariable<?>[] variables = raw.getTypeParameters();
        if (variables.length != arguments.length) {
            throw new SerializationException(
                    "Type argument count mismatch for " + raw.getName());
        }
        Map<TypeVariable<?>, Type> bindings = new HashMap<>(variables.length);
        for (int index = 0; index < variables.length; index++) {
            bindings.put(variables[index], arguments[index]);
        }
        return bindings;
    }

    private static Map<Class<?>, Codec<?>> builtins() {
        Map<Class<?>, Codec<?>> map = new HashMap<>();
        register(map, boolean.class, BuiltinCodecs.BOOLEAN);
        register(map, Boolean.class, BuiltinCodecs.BOOLEAN);
        register(map, byte.class, BuiltinCodecs.BYTE);
        register(map, Byte.class, BuiltinCodecs.BYTE);
        register(map, short.class, BuiltinCodecs.SHORT);
        register(map, Short.class, BuiltinCodecs.SHORT);
        register(map, char.class, BuiltinCodecs.CHARACTER);
        register(map, Character.class, BuiltinCodecs.CHARACTER);
        register(map, int.class, BuiltinCodecs.INTEGER);
        register(map, Integer.class, BuiltinCodecs.INTEGER);
        register(map, long.class, BuiltinCodecs.LONG);
        register(map, Long.class, BuiltinCodecs.LONG);
        register(map, float.class, BuiltinCodecs.FLOAT);
        register(map, Float.class, BuiltinCodecs.FLOAT);
        register(map, double.class, BuiltinCodecs.DOUBLE);
        register(map, Double.class, BuiltinCodecs.DOUBLE);
        register(map, String.class, BuiltinCodecs.STRING);
        register(map, UUID.class, BuiltinCodecs.UUID_CODEC);
        register(map, BigInteger.class, BuiltinCodecs.BIG_INTEGER);
        register(map, BigDecimal.class, BuiltinCodecs.BIG_DECIMAL);
        register(map, LocalDate.class, BuiltinCodecs.LOCAL_DATE);
        register(map, LocalTime.class, BuiltinCodecs.LOCAL_TIME);
        register(map, LocalDateTime.class, BuiltinCodecs.LOCAL_DATE_TIME);
        register(map, Instant.class, BuiltinCodecs.INSTANT);
        register(map, Duration.class, BuiltinCodecs.DURATION);
        register(map, ZoneOffset.class, BuiltinCodecs.ZONE_OFFSET);
        register(map, ZonedDateTime.class, BuiltinCodecs.ZONED_DATE_TIME);
        return map;
    }

    private static void register(Map<Class<?>, Codec<?>> map, Class<?> type, Codec<?> codec) {
        map.put(type, codec);
    }
}
