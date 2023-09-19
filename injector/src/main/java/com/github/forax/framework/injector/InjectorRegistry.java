package com.github.forax.framework.injector;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

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

  public <T> void registerProvider(Class<T> type, Supplier<? extends T> supplier) {
    Objects.requireNonNull(type);
    Objects.requireNonNull(supplier);
    if (null != registry.putIfAbsent(type, supplier)) {
      throw new IllegalStateException("Recipe already registered for " + type.getName());
    }
  }

  public <T> void registerProviderClass(Class<T> type, Class<? extends T> providerClass) {
    Objects.requireNonNull(type);
    Objects.requireNonNull(providerClass);
    var constructor = getConstructor(providerClass);
    var properties = findInjectableProperties(providerClass);
    var params = constructor.getParameterTypes();
    registerProvider(type, () -> {
      var instance = Utils.newInstance(constructor, Arrays.stream(params).map(this::lookupInstance).toArray());
      properties.forEach(p -> callSetter(instance, p));
      return instance;
    });
  }

  public <T> void registerProviderClass(Class<T> providerClass) {
    registerProviderClass(providerClass, providerClass);
  }

  // Package visible for testing
  static List<PropertyDescriptor> findInjectableProperties(Class<?> type) {
    var info = Utils.beanInfo(type);
    return Arrays.stream(info.getPropertyDescriptors())
        .filter(InjectorRegistry::isWriterAnnotated)
        .toList();
  }

  private static boolean isWriterAnnotated(PropertyDescriptor property) {
    var setter = property.getWriteMethod();
    return setter != null && setter.isAnnotationPresent(Inject.class);
  }

  private static <T> Constructor<T> getConstructor(Class<T> type) {
    var constructors = Arrays.stream(type.getConstructors())
        .filter(c -> c.isAnnotationPresent(Inject.class) || c.getParameterCount() == 0)
        .toArray(Constructor[]::new);
    return switch (constructors.length) {
      case 0 -> throw new NoSuchMethodError("Missing default constructor OR annotated constructor");
      case 1 -> constructors[0];
      default -> throw new IllegalStateException("Too much valid constructors");
    };
  }

  private void callSetter(Object instance, PropertyDescriptor p) {
    var method = p.getWriteMethod();
    var params = Arrays.stream(method.getParameterTypes()).map(this::lookupInstance).toArray();
    Utils.invokeMethod(instance, method, params);
  }

}