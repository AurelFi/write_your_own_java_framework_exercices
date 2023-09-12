package com.github.forax.framework.mapper;

import java.beans.BeanInfo;
import java.beans.PropertyDescriptor;
import java.util.Arrays;
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
    PropertyDescriptor[] properties = Utils.beanInfo(o.getClass()).getPropertyDescriptors();
    return Arrays.stream(properties)
          .filter(property -> !property.getName().equals("class"))
          .map(property -> '"' + property.getName() + "\": " + toJSON(Utils.invokeMethod(o, property.getReadMethod())))
          .collect(Collectors.joining(", ", "{", "}"));
  }
}
