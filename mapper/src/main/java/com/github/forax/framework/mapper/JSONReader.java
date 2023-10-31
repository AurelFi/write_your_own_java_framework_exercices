package com.github.forax.framework.mapper;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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
  }

  private record Context(ObjectBuilder<Object> builder, Object result) {}

  public <T> T parseJSON(String text, Class<T> expectedClass) {
    return expectedClass.cast(parseJSON(text, (Type) expectedClass));
  }

  public Object parseJSON(String text, Type expectedType) {
    Objects.requireNonNull(text);
    Objects.requireNonNull(expectedType);
    var stack = new ArrayDeque<Context>();
    var visitor = new ToyJSONParser.JSONVisitor() {
      private Object result;

      @Override
      public void value(String key, Object value) {
        // call the corresponding setter on result
        var context = stack.peek();
        context.builder.populater.populate(context.result, key, value);
      }

      @Override
      public void startObject(String key) {
        var context = stack.peek();
        //get the beanData and store it in the field
        var beanType = context == null
            ? expectedType
            : context.builder.typeProvider.apply(key);
        //create an instance and store it in result
        var objectbuilder = ObjectBuilder.bean(Utils.erase(beanType));
        stack.push(new Context(objectbuilder, objectbuilder.supplier.get()));
      }

      @Override
      public void endObject(String key) {
        var previousContext = stack.pop();
        if (stack.isEmpty()) {
          result = previousContext.result;
        } else {
          var context = stack.peek();
          context.builder.populater().populate(context.result, key, previousContext.result);
        }
      }

      @Override
      public void startArray(String key) {
        throw new UnsupportedOperationException("Implemented later");
      }

      @Override
      public void endArray(String key) {
        throw new UnsupportedOperationException("Implemented later");
      }
    };
    ToyJSONParser.parse(text, visitor);
    return visitor.result;
  }
}