package io.columnar.core;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Retains codegen outputs (Buddy-generated classes, etc.). */
public final class ClassCache {

    private static final ClassCache INSTANCE = new ClassCache();

    private final ConcurrentHashMap<String, Class<? extends CompiledPredicate>> predicates =
            new ConcurrentHashMap<>();

    public static ClassCache instance() {
        return INSTANCE;
    }

    public void registerIfAbsent(String canonicalKey, Class<? extends CompiledPredicate> clazz) {
        Objects.requireNonNull(canonicalKey, "canonicalKey");
        Objects.requireNonNull(clazz, "clazz");
        predicates.putIfAbsent(canonicalKey, clazz);
    }

    /** For diagnostics — overwrites unconditionally. */
    public void register(String canonicalKey, Class<? extends CompiledPredicate> clazz) {
        Objects.requireNonNull(canonicalKey, "canonicalKey");
        Objects.requireNonNull(clazz, "clazz");
        predicates.put(canonicalKey, clazz);
    }

    public Class<? extends CompiledPredicate> get(String key) {
        return predicates.get(key);
    }
}
