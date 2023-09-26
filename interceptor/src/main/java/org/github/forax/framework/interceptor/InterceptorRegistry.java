package org.github.forax.framework.interceptor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.stream.Stream;

public final class InterceptorRegistry {
  private final HashMap<Class<?>, List<AroundAdvice>> advices = new HashMap<>();
  private final HashMap<Class<?>, List<Interceptor>> interceptors = new HashMap<>();

  public void addAroundAdvice(Class<? extends Annotation> annotationClass, AroundAdvice aroundAdvice) {
    Objects.requireNonNull(annotationClass);
    Objects.requireNonNull(aroundAdvice);
    addInterceptor(annotationClass, ((instance, method, args, invocation) -> {
          aroundAdvice.before(instance, method, args);
          Object result = null;
          try {
            return result = invocation.proceed(instance, method, args);
          } finally {
            aroundAdvice.after(instance, method, args, result);
          }
    }));
  }

  public void addInterceptor(Class<? extends Annotation> annotationClass, Interceptor interceptor) {
    Objects.requireNonNull(annotationClass);
    Objects.requireNonNull(interceptor);
    interceptors.computeIfAbsent(annotationClass, __ -> new ArrayList<>()).add(interceptor);

  }

  List<Interceptor> findInterceptors(Method method) {
    return Arrays.stream(method.getAnnotations())
        .flatMap(annotation -> interceptors.getOrDefault(annotation.annotationType(), List.of()).stream())
        .toList();
  }

  public static Invocation getInvocation(List<Interceptor> interceptorList) {
    Invocation invocation = Utils::invokeMethod;
    for (var interceptor : interceptorList.reversed()) {
      var curInvocation = invocation;
      invocation = (instance, method, args) -> interceptor.intercept(instance, method, args, curInvocation);
    }
    return invocation;
  }

  public <T> T createProxy(Class<T> type, T delegate) {
    Objects.requireNonNull(type);
    Objects.requireNonNull(delegate);
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(),
        new Class<?>[]{type},
        (Object __, Method method, Object[] args) -> {
            var methodInterceptors = findInterceptors(method);
            var invocation = getInvocation(methodInterceptors);
            return invocation.proceed(delegate, method, args);
        }));
  }
}
