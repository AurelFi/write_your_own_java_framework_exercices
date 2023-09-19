package com.github.forax.framework.injector;

import java.util.HashMap;
import java.util.Objects;

public final class InjectorRegistry {

  /** Storage for registered instances */
  private final HashMap<Class<?>, Object> registry = new HashMap<>();

  public <T> void registerInstance(Class<T> type, T instance) {
    Objects.requireNonNull(type);
    Objects.requireNonNull(instance);
    assert instance.getClass().equals(type);
    if (null != registry.putIfAbsent(type, instance)) {
      throw new IllegalStateException("Recipe already registered for " + type.getName());
    }
  }

  public <T> T lookupInstance(Class<T> type) {
    Objects.requireNonNull(type);
    var val = registry.get(type);
    if (val == null) throw new IllegalStateException("Type not present in registry");
    return type.cast(val);
  }
}