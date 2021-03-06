package moar.awake;

import static java.util.Collections.unmodifiableMap;
import static moar.sugar.MoarStringUtil.toSnakeCase;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import moar.sugar.MoarException;

/**
 * Interface to expose private proxy methods.
 *
 * @author Mark Farnsworth
 */
class WokePrivateProxy
    implements
    WokeProxy,
    InvocationHandler {
  private static String ROW_INTERFACE_SUFFIX = "Row";

  private static String toDbName(String string, String q) {
    return q + toSnakeCase(string) + q;
  }

  Map<String, Object> setMap = new ConcurrentHashMap<>();
  Map<String, Object> map = new ConcurrentHashMap<>();
  Class<?> clz;
  private String identifierQuoteString = "";
  private String tableName;

  WokePrivateProxy(Class<?> clz) {
    this.clz = clz;
  }

  String fromDbName(String key) {
    try {
      StringBuilder s = new StringBuilder();
      boolean upper = true;
      char qChar = identifierQuoteString.charAt(0);
      for (char c : key.toCharArray()) {
        if (c == qChar) {
          // ignore
        } else if (c != '_') {
          if (upper) {
            s.append(Character.toUpperCase(c));
            upper = false;
          } else {
            s.append(c);
          }
        }
        if (c == '_') {
          upper = true;
        }
      }
      return s.toString();
    } catch (RuntimeException e) {
      throw new RuntimeException(key, e);
    }
  }

  Map<String, Object> get() {
    Map<String, Object> dbMap = new ConcurrentHashMap<>();
    for (String key : map.keySet()) {
      String dbName = toDbName(key, getIdentifierQuoteString());
      dbMap.put(dbName, map.get(key));
    }
    return dbMap;
  }

  List<String> getColumns(boolean includeId) {
    List<String> columns = new ArrayList<>();
    for (Method method : clz.getMethods()) {
      String name = method.getName();
      if (isProperty(name)) {
        if (!getPropertyName(name).equals("Id") || includeId) {
          String dbName = toDbName(getPropertyName(name), getIdentifierQuoteString());
          if (!columns.contains(dbName)) {
            columns.add(dbName);
          }
        }
      }
    }
    Collections.sort(columns);
    return columns;
  }

  Object getDbValue(String column) {
    String propName = fromDbName(column);
    Object value = map.get(propName);
    if (value instanceof Date) {
      return new java.sql.Timestamp(((Date) value).getTime());
    }
    return value;
  }

  String getIdColumn() {
    String q = identifierQuoteString;
    return q + "id" + q;
  }

  String getIdentifierQuoteString() {
    return identifierQuoteString;
  }

  Object getIdValue() {
    return map.get("Id");
  }

  private Object getProperty(String name) {
    return map.get(getPropertyName(name));
  }

  private String getPropertyName(String name) {
    return name.substring("get".length());
  }

  String getTableName() {
    if (tableName == null) {
      String simpleName = clz.getSimpleName();
      if (simpleName.endsWith(ROW_INTERFACE_SUFFIX)) {
        simpleName = simpleName.substring(0, simpleName.length() - ROW_INTERFACE_SUFFIX.length());
      }
      tableName = toDbName(simpleName, identifierQuoteString);
    }
    return tableName;
  }

  Class<?> getTargetClass() {
    return clz;
  }

  @SuppressWarnings("unchecked")
  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    String name = method.getName();
    if (args == null) {
      if (name.equals("privateProxy")) {
        return this;
      } else if (name.equals("toString")) {
        return toString();
      } else {
        if (isProperty(name)) {
          Object value = getProperty(name);
          if (value instanceof Number) {
            Number number = (Number) value;
            Class<?> returnType = method.getReturnType();
            if (returnType == Double.class) {
              return number.doubleValue();
            } else if (returnType == Long.class) {
              return number.longValue();
            } else if (returnType == Integer.class) {
              return number.intValue();
            } else if (returnType == Float.class) {
              return number.floatValue();
            } else if (returnType == Short.class) {
              return number.shortValue();
            } else if (returnType == Byte.class) {
              return number.byteValue();
            }
          }
          return value;
        }
      }
    } else if (args.length == 1) {
      if (name.startsWith("$")) {
        if (name.equals("$set")) {
          set((Map<String, Object>) args[0]);
          return null;
        } else if (name.equals("$setIdentifierQuoteString")) {
          setIdentifierQuoteString((String) args[0]);
          return null;
        }
      } else {
        if (isProperty(name)) {
          setProperty(name, args[0]);
          return null;
        }
      }
    }
    throw new MoarException(name, " is not supported by this proxy");
  }

  boolean isDbDirty(String column) {
    String propName = fromDbName(column);
    Object mapValue = map.get(propName);
    Object setValue = setMap.get(propName);
    if (setValue == mapValue) {
      return false;
    }
    if (mapValue != null) {
      return !mapValue.equals(setValue);
    }
    return true;
  }

  @Override
  public boolean isDirty() {
    for (String column : getColumns(true)) {
      if (isDbDirty(column)) {
        return true;
      }
    }
    return false;
  }

  private boolean isProperty(String name) {
    // try to be fast with this check!
    // get or set!
    return name.startsWith("g") || name.startsWith("s") && name.substring(1).startsWith("et");
  }

  void reset() {
    map.clear();
    for (String key : setMap.keySet()) {
      map.put(key, setMap.get(key));
    }
  }

  void set(Map<String, Object> dbMap) {
    setMap.clear();
    map.clear();
    for (String key : dbMap.keySet()) {
      String propName = fromDbName(key);
      Object dbValue = dbMap.get(key);
      setMap.put(propName, dbValue);
      map.put(propName, dbValue);
    }
  }

  void setIdentifierQuoteString(String value) {
    identifierQuoteString = value;
  }

  private void setProperty(String name, Object arg) {
    if (arg == null) {
      map.remove(name);
    } else {
      map.put(getPropertyName(name), arg);
    }
  }

  void setTableName(String tableish) {
    tableName = tableish;
  }

  @SuppressWarnings("rawtypes")
  @Override
  public Map toMap() {
    return unmodifiableMap(map);
  }

  @Override
  public String toString() {
    return map.toString();
  }

}
