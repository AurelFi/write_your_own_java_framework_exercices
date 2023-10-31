package com.github.forax.framework.mapper;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
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

  public <T> T parseJSON(String text, Class<T> beanClass) {
    Objects.requireNonNull(text);
    Objects.requireNonNull(beanClass);
    var visitor = new ToyJSONParser.JSONVisitor() {
      private BeanData beanData;
      private Object result;

      @Override
      public void value(String key, Object value) {
        // call the corresponding setter on result
        var setter = beanData.findProperty(key).getWriteMethod();
        Utils.invokeMethod(result, setter, value);

      }

      @Override
      public void startObject(String key) {
        //get the beanData and store it in the field
        beanData = BEAN_DATA_CLASS_VALUE.get(beanClass);
        //create an instance and store it in result
        result = Utils.newInstance(beanData.constructor());
      }

      @Override
      public void endObject(String key) {
        // do nothing
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
    return beanClass.cast(visitor.result);
  }
}