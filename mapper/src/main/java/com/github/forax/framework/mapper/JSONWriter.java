package com.github.forax.framework.mapper;

import java.beans.PropertyDescriptor;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class JSONWriter {
  public String toJSON(Object o) {
    return switch (o) {
      case null -> "null";
      case String s -> '"' + s + '"';
      case Boolean b -> b.toString();
      case Integer i -> i.toString();
      case Double d -> d.toString();
      default -> beanToJson(o);
    };
  }

  private String beanToJson(Object o) {
    return DATA_CLASS_VALUE.get(o.getClass()).stream()
          .map(generator -> generator.generate(this, o))
          .collect(Collectors.joining(", ", "{", "}"));
  }

  private static final ClassValue<List<Generator>> DATA_CLASS_VALUE = new ClassValue<>() {
    @Override
    protected List<Generator> computeValue(Class<?> type) {
      PropertyDescriptor[] properties = Utils.beanInfo(type).getPropertyDescriptors();
      return Arrays.stream(properties)
        .filter(property -> !property.getName().equals("class"))
        .<Generator>map(property -> {
          var method = property.getReadMethod();
          var annotation = method.getAnnotation(JSONProperty.class);
          var key = "\"" + (annotation != null ? annotation.value() : property.getName()) + "\": ";
          return (JSONWriter w, Object o) -> key + w.toJSON(Utils.invokeMethod(o, method));
        })
        .toList();
    }
  };

  @FunctionalInterface
  public interface Generator {
    String generate(JSONWriter writer, Object bean);
  }
}
