package eu.wohlben.qits.offline;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * Fails boot, naming what is missing, when a service has an {@link OfflineCapable} bean but its
 * database lacks the module's schema: {@code qits_idempotency}, {@code qits_tombstones} or the
 * function {@code qits_touch_change_xid()}. The service copies {@code
 * META-INF/qits-offline/schema.sql} into a Flyway migration of its own; without this check the
 * first replayed write would be the one to find out.
 *
 * <p>It observes Quarkus' {@code StartupEvent}, not CDI's {@code @Initialized(ApplicationScoped)}:
 * Quarkus fires the latter during static init, before the datasource may be touched.
 *
 * <p>A service with no {@code @OfflineCapable} bean is not checked, so holding the jar costs
 * nothing until the first operation adopts it.
 */
@Dependent
public class OfflineSchemaCheck {

  /** The tables, as {@code to_regclass} names them. */
  static final List<String> TABLES = List.of("qits_idempotency", "qits_tombstones");

  /** The function, as {@code to_regprocedure} names it. */
  static final String FUNCTION = "qits_touch_change_xid()";

  @Inject Instance<DataSource> dataSource;

  void onStart(@Observes StartupEvent event, BeanManager beans) {
    List<Class<?>> classes = new ArrayList<>();
    for (Bean<?> bean : beans.getBeans(Object.class, Any.Literal.INSTANCE)) {
      classes.add(bean.getBeanClass());
    }
    if (!anyOfflineCapable(classes)) {
      return;
    }
    if (!dataSource.isResolvable()) {
      throw new IllegalStateException(
          "@OfflineCapable operations need the default datasource, and this application has none");
    }
    try (Connection connection = dataSource.get().getConnection()) {
      require(connection);
    } catch (SQLException e) {
      throw new IllegalStateException("could not check the qits-offline-core schema", e);
    }
  }

  /** Whether any of the classes, or any method declared on one, carries {@link OfflineCapable}. */
  static boolean anyOfflineCapable(Iterable<Class<?>> classes) {
    for (Class<?> type : classes) {
      if (type == null) {
        continue;
      }
      if (type.isAnnotationPresent(OfflineCapable.class)) {
        return true;
      }
      for (Method method : type.getDeclaredMethods()) {
        if (method.isAnnotationPresent(OfflineCapable.class)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Throws {@link IllegalStateException} naming every missing table and function.
   */
  static void require(Connection connection) throws SQLException {
    List<String> missing = missing(connection);
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          "@OfflineCapable operations need the qits-offline-core schema, and the database lacks "
              + String.join(", ", missing)
              + ". Copy META-INF/qits-offline/schema.sql from qits-offline-core into a Flyway"
              + " migration of this service.");
    }
  }

  /** The missing objects, as {@code table qits_…} and {@code function qits_…()}. */
  static List<String> missing(Connection connection) throws SQLException {
    List<String> missing = new ArrayList<>();
    for (String table : TABLES) {
      if (!exists(connection, "select to_regclass(?) is not null", table)) {
        missing.add("table " + table);
      }
    }
    if (!exists(connection, "select to_regprocedure(?) is not null", FUNCTION)) {
      missing.add("function " + FUNCTION);
    }
    return missing;
  }

  private static boolean exists(Connection connection, String sql, String name)
      throws SQLException {
    try (PreparedStatement query = connection.prepareStatement(sql)) {
      query.setString(1, name);
      try (ResultSet result = query.executeQuery()) {
        return result.next() && result.getBoolean(1);
      }
    }
  }
}
