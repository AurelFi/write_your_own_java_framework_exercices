package com.github.forax.framework.injector;

import java.util.HashMap;
import java.util.Objects;
import java.util.function.Supplier;

public final class InjectorRegistry {

  /** Storage for registered instances */
  private final HashMap<Class<?>, Supplier<?>> registry = new HashMap<>();

  public <T> void registerInstance(Class<T> type, T instance) {
    Objects.requireNonNull(instance);
    registerProvider(type, () -> instance);
  }

  public <T> T lookupInstance(Class<T> type) {
    Objects.requireNonNull(type);
    var val = registry.get(type);
    if (val == null) throw new IllegalStateException("Type not present in registry");
    return type.cast(val.get());
  }

  public <T> void registerProvider(Class<T> type, Supplier<T> supplier) {
    Objects.requireNonNull(type);
    Objects.requireNonNull(supplier);
    if (null != registry.putIfAbsent(type, supplier)) {
      throw new IllegalStateException("Recipe already registered for " + type.getName());
    }
  }
}