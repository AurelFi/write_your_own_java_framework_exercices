package com.github.forax.framework.mapper;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JSONReader {

  private record BeanData(Constructor<?> constructor, Map<String, PropertyDescriptor> propertyMap) {
    PropertyDescriptor findProperty(String key) {
      var property = propertyMap.get(key);
      if (property == null) {
        throw new IllegalStateException("unknown key " + key + " for bean " + constructor.getDeclaringClass().getName());
      }
      return property;
    }
  }

  private static final ClassValue<BeanData> BEAN_DATA_CLASS_VALUE = new ClassValue<>() {
    @Override
    protected BeanData computeValue(Class<?> type) {
      var beanInfo = Utils.beanInfo(type);
      var map = Arrays.stream(beanInfo.getPropertyDescriptors())
          .filter(property -> !property.getName().equals("class"))
          .collect(Collectors.toMap(PropertyDescriptor::getName, Function.identity()));
      return new BeanData(Utils.defaultConstructor(type), map);
    }
  };

  @FunctionalInterface
  public interface TypeMatcher {
    Optional<ObjectBuilder<?>> match(Type type);
  }

  private final ArrayList<TypeMatcher> typeMatchers = new ArrayList<>();

  public record ObjectBuilder<T>(Function<? super String, ? extends Type> typeProvider,
                                 Supplier<? extends T> supplier,
                                 Populater<? super T> populater,
                                 Function<? super T, ?> finisher) {
    public interface Populater<T> {
      void populate(T instance, String key, Object value);
    }

    public static ObjectBuilder<Object> bean(Class<?> beanClass) {
      var beanData = BEAN_DATA_CLASS_VALUE.get(beanClass);
      return new ObjectBuilder<>(
          key -> beanData.findProperty(key).getWriteMethod().getGenericParameterTypes()[0],
          () -> Utils.newInstance(beanData.constructor),
          (instance, key, value) -> Utils.invokeMethod(instance, beanData.findProperty(key).getWriteMethod(), value),
          Function.identity()
      );
    }

    public static ObjectBuilder<List<Object>> list(Type elementType) {
      Objects.requireNonNull(elementType);
      return new ObjectBuilder<>(
          key -> elementType,
          ArrayList::new,
          (list, key, value) -> list.add(value),
          List::copyOf
      );
    }
  }

  private record Context<T>(ObjectBuilder<T> builder, T result) {

    Object finish() {
      return builder.finisher().apply(result);
    }

    void populate(String key, Object value) {
      builder.populater().populate(result, key, value);
    }

    static <T> Context<T> create(ObjectBuilder<T> builder) {
      return new Context<>(builder, builder.supplier.get());
    }
  }

  public interface TypeReference<T> {}

  private static Type findElemntType(TypeReference<?> typeReference) {
    var typeReferenceType = Arrays.stream(typeReference.getClass().getGenericInterfaces())
        .flatMap(t -> t instanceof ParameterizedType parameterizedType? Stream.of(parameterizedType): null)
        .filter(t -> t.getRawType() == TypeReference.class)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("invalid TypeReference " + typeReference));
    return typeReferenceType.getActualTypeArguments()[0];
  }

  public <T> T parseJSON(String text, TypeReference<T> typeReference) {
    var expectedType = findElemntType(typeReference);
    @SuppressWarnings("unchecked")
    var result = (T)parseJSON(text, expectedType);
    return result;
  }

  public <T> T parseJSON(String text, Class<T> expectedClass) {
    return expectedClass.cast(parseJSON(text, (Type)expectedClass));
  }

  public Object parseJSON(String text, Type expectedType) {
    Objects.requireNonNull(text);
    Objects.requireNonNull(expectedType);
    var stack = new ArrayDeque<Context<?>>();
    var visitor = new ToyJSONParser.JSONVisitor() {
      private Object result;

      @Override
      public void value(String key, Object value) {
        // call the corresponding setter on result
        var context = stack.peek();
        context.populate(key, value);
      }

      @Override
      public void startObject(String key) {
        var context = stack.peek();
        //get the beanData and store it in the field
        var beanType = context == null
            ? expectedType
            : context.builder.typeProvider.apply(key);
        //create an instance and store it in result
        var objectbuilder = findObjectsBuilder(beanType);
        stack.push(Context.create(objectbuilder));
      }

      @Override
      public void endObject(String key) {
        var previousContext = stack.pop();
        if (stack.isEmpty()) {
          result = previousContext.finish();
        } else {
          var context = stack.peek();

          context.populate(key, previousContext.finish());
        }
      }

      @Override
      public void startArray(String key) {
        startObject(key);
      }

      @Override
      public void endArray(String key) {
        endObject(key);
      }
    };
    ToyJSONParser.parse(text, visitor);
    return visitor.result;
  }

  public void addTypeMatcher(TypeMatcher typeMatcher) {
    Objects.requireNonNull(typeMatcher);
    typeMatchers.add(typeMatcher);
  }

  private ObjectBuilder<?> findObjectsBuilder(Type type) {
    return typeMatchers.reversed().stream()
        .flatMap(typeMatcher -> typeMatcher.match(type).stream())
        .findFirst()
        .orElseGet(() -> ObjectBuilder.bean(Utils.erase(type)));
  }
}

// TODO Q6 à la maison