package com.github.forax.framework.mapper;

public final class JSONWriter {
  public String toJSON(Object o) {
    return switch (o) {
      case null -> "null";
      case String s -> '"' + s + '"';
      default -> String.valueOf(o);
    };
  }
}
