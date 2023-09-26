package org.github.forax.framework.interceptor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.stream.Stream;

public final class InterceptorRegistry {
  private final HashMap<Class<?>, List<AroundAdvice>> advices = new HashMap<>();

  public void addAroundAdvice(Class<? extends Annotation> annotationClass, AroundAdvice aroundAdvice) {
    Objects.requireNonNull(annotationClass);
    Objects.requireNonNull(aroundAdvice);
    advices.computeIfAbsent(annotationClass, __ -> new ArrayList<>()).add(aroundAdvice);
  }

  List<AroundAdvice> findAdvices(Method method) {
    return Arrays.stream(method.getAnnotations())
        .flatMap(annotation -> advices.getOrDefault(annotation.annotationType(), List.of()).stream())
        .toList();
  }

  public <T> T createProxy(Class<T> type, T delegate) {
    Objects.requireNonNull(type);
    Objects.requireNonNull(delegate);
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(),
        new Class<?>[]{type},
        (Object __, Method method, Object[] args) -> {
          var methodAdvices = findAdvices(method);
          for (var advice : methodAdvices) {
            advice.before(delegate, method, args);
          }
          Object ret = null;
          try {
            ret = method.invoke(delegate, args);
            return ret;
          } finally {
            for (var advice : methodAdvices.reversed()) {
              advice.after(delegate, method, args, ret);
            }
          }
        }));
  }
}
