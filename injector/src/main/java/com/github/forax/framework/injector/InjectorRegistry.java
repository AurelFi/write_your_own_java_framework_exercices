package com.github.forax.framework.injector;

import java.util.HashMap;
import java.util.Objects;

public final class InjectorRegistry {

  /** Storage for registered instances */
  private final HashMap<Class<?>, Object> registry = new HashMap<>();

  public void registerInstance(Class<?> type, Object instance) {
    Objects.requireNonNull(type);
    Objects.requireNonNull(instance);
    if (null != registry.putIfAbsent(type, instance)) {
      throw new IllegalStateException("Recipe already registered for " + type.getName());
    }
  }

  public Object lookupInstance(Class<?> type) {
    Objects.requireNonNull(type);
    var val = registry.get(type);
    if (val == null) throw new IllegalStateException("Type not present in registry");
    return val;
  }
}