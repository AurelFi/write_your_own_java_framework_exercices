package com.github.forax.framework.orm;

import javax.sql.DataSource;
import java.beans.BeanInfo;
import java.beans.PropertyDescriptor;
import java.io.Serial;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ORM {
  private ORM() {
    throw new AssertionError();
  }

  @FunctionalInterface
  public interface TransactionBlock {
    void run() throws SQLException;
  }

  private static final Map<Class<?>, String> TYPE_MAPPING = Map.of(
      int.class, "INTEGER",
      Integer.class, "INTEGER",
      long.class, "BIGINT",
      Long.class, "BIGINT",
      String.class, "VARCHAR(255)"
  );

  private static Class<?> findBeanTypeFromRepository(Class<?> repositoryType) {
    var repositorySupertype = Arrays.stream(repositoryType.getGenericInterfaces())
        .flatMap(superInterface -> {
          if (superInterface instanceof ParameterizedType parameterizedType
              && parameterizedType.getRawType() == Repository.class) {
            return Stream.of(parameterizedType);
          }
          return null;
        })
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("invalid repository interface " + repositoryType.getName()));
    var typeArgument = repositorySupertype.getActualTypeArguments()[0];
    if (typeArgument instanceof Class<?> beanType) {
      return beanType;
    }
    throw new IllegalArgumentException("invalid type argument " + typeArgument + " for repository interface " + repositoryType.getName());
  }

  private static class UncheckedSQLException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 42L;

    private UncheckedSQLException(SQLException cause) {
      super(cause);
    }

    @Override
    public SQLException getCause() {
      return (SQLException) super.getCause();
    }
  }


  // --- do not change the code above

  private static final ThreadLocal<Connection> CONNECTION_THREAD_LOCAL = new ThreadLocal<>();

  public static void transaction(DataSource dataSource, TransactionBlock block) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      CONNECTION_THREAD_LOCAL.set(connection);
      try {
        connection.setAutoCommit(false);
        block.run();
        connection.commit();
      } catch (SQLException e) {
        connection.rollback();
        throw e;
      } catch (UncheckedSQLException e) {
        connection.rollback();
        throw e.getCause();
      } finally {
        CONNECTION_THREAD_LOCAL.remove();
      }
    }
  }

  static Connection currentConnection() {
    var connection = CONNECTION_THREAD_LOCAL.get();
    if (connection == null) {
      throw new IllegalStateException();
    }
    return connection;
  }

  /**
   * Takes a bean class as argument and returns the name of the table.
   * @param beanClass class to get the table name from
   * @return the table name
   */
  static String findTableName(Class<?> beanClass) {
    var annotation = beanClass.getAnnotation(Table.class);
    var name = annotation == null ? beanClass.getSimpleName() : annotation.value();
    return name.toUpperCase(Locale.ROOT);
  }

  static String findColumnName(PropertyDescriptor property) {
    var annotation = getGetterMethod(property).getAnnotation(Column.class);
    var name = annotation == null ? property.getName() : annotation.value();
    return name.toUpperCase(Locale.ROOT);
  }

  public static void createTable(Class<?> beanClass) throws SQLException {
    Objects.requireNonNull(beanClass);
    var tableName = findTableName(beanClass);
    var connection = currentConnection();
    var query = Arrays.stream(Utils.beanInfo(beanClass).getPropertyDescriptors())
        .filter(p -> !p.getName().equals("class"))
        .map(ORM::colToSQL)
        .collect(Collectors.joining(",\n  ", "CREATE TABLE " + tableName + " (\n  ", ");\n"));

    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate(query);
    }
    connection.commit();
  }

  private static boolean isPrimary(PropertyDescriptor property) {
    return getGetterMethod(property).isAnnotationPresent(Id.class);
  }

  private static boolean isGenerated(PropertyDescriptor property) {
    return getGetterMethod(property).isAnnotationPresent(GeneratedValue.class);
  }

  private static Method getGetterMethod(PropertyDescriptor property) {
    var getter = property.getReadMethod();
    if (getter == null) {
      throw new IllegalStateException("No getter for property " + property.getName());
    }
    return getter;
  }

  private static String colToSQL(PropertyDescriptor property) {
    var propertyType = property.getPropertyType();
    var sqlType = TYPE_MAPPING.get(propertyType);
    if (sqlType == null)
      throw new IllegalStateException("Unknown type " + propertyType);
    var colName = findColumnName(property);
    var notNull = propertyType.isPrimitive() ? " NOT NULL" : "";
    var generated = isGenerated(property) ? " AUTO_INCREMENT" : "";
    var primary = isPrimary(property) ? ", PRIMARY KEY (" + colName + ")" : "";
    return colName + " " + sqlType + notNull + generated + primary;
  }


  public static <T extends Repository<?,?>> T createRepository(Class<T> repositoryClass) {
    Objects.requireNonNull(repositoryClass);
    var beanType = findBeanTypeFromRepository(repositoryClass);
    var beanInfo = Utils.beanInfo(beanType);
    var constructor = Utils.defaultConstructor(beanType);
    var tableName = findTableName(beanType);
    var propertyId = findId(beanInfo);
    return repositoryClass.cast(Proxy.newProxyInstance(repositoryClass.getClassLoader(),
      new Class<?>[]{repositoryClass},
      (Object __, Method method, Object[] args) -> switch (method.getName()) {
        case "findAll" -> findAll(currentConnection(), "SELECT * FROM " + tableName, beanInfo, constructor);
        case "save" -> save(currentConnection(), tableName, beanInfo, args[0], propertyId);
        case "equals", "hashCode", "toString" -> throw new UnsupportedOperationException();
        default -> throw new IllegalStateException("Unknown method");
      }));
  }

  static <T> T toEntityClass(ResultSet resultSet, BeanInfo beanInfo, Constructor<? extends T> constructor) throws SQLException {
    var instance = Utils.newInstance(constructor); // On créé l'instance vide
    for (var property : beanInfo.getPropertyDescriptors()) {
      var name = property.getName();
      if (name.equals("class")) {
        continue;
      }
      Utils.invokeMethod(instance, property.getWriteMethod(), resultSet.getObject(name));
    }
    return instance;
  }

  static <T> List<T> findAll(Connection connection, String sqlQuery, BeanInfo beanInfo, Constructor<? extends T> constructor) {
    var list = new ArrayList<T>();
    try (var statement = connection.prepareStatement(sqlQuery)) {
      try (ResultSet resultSet = statement.executeQuery()) {
        while(resultSet.next()) {
          list.add(toEntityClass(resultSet, beanInfo, constructor));
        }
      }
    } catch (SQLException e) {
      throw new UncheckedSQLException(e);
    }
    return list;
  }

  static String createSaveQuery(String tableName, BeanInfo beanInfo) {
    var properties = beanInfo.getPropertyDescriptors();
    var names = Arrays.stream(properties)
        .filter(property -> !property.getName().equals("class"))
        .map(ORM::findColumnName)
        .collect(Collectors.joining(", ", " (", ") VALUES ("));
    var placeholders = String.join(", ", Collections.nCopies(properties.length - 1, "?"));
    return "MERGE INTO " + tableName + names + placeholders + ");";
  }

  static <T> T save(Connection connection, String tableName, BeanInfo beanInfo, T bean, PropertyDescriptor idProperty) throws SQLException {
    var query = createSaveQuery(tableName, beanInfo);
    try(var statement = connection.prepareStatement(query, Statement.RETURN_GENERATED_KEYS)) {
      int i = 1 ;
      for (var property : beanInfo.getPropertyDescriptors()) {
        var name = property.getName();
        if (!name.equals("class")) {
          statement.setObject(i++, Utils.invokeMethod(bean, property.getReadMethod()));
        }
      }
      statement.executeUpdate();
      if (idProperty != null) {
        try(ResultSet resultSet = statement.getGeneratedKeys()) {
          if (resultSet.next()) {
            Utils.invokeMethod(bean, idProperty.getWriteMethod(), resultSet.getObject(1));
          }
        }
      }
    }
    connection.commit();
    return bean;
  }

  private static PropertyDescriptor findId(BeanInfo beanInfo) {
    for (var property : beanInfo.getPropertyDescriptors()) {
      if (property.getReadMethod().isAnnotationPresent(Id.class)) {
        return property;
      }
    }
    return null;
  }
}
