package eu.wohlben.qits.offline.testdb;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * One real PostgreSQL for this module's surefire JVM, copied from qits-db-core's rig (modules share
 * no test code). Zonky resolves the binaries as Maven artifacts and this class spawns them as a
 * child process, so a clone of this repo alone tests green with no docker.
 *
 * <p><b>It refuses to run as root, and so does this suite</b>: {@code initdb} exits rather than run
 * as the superuser. {@link #available()} is what the postgres-backed test classes gate on.
 *
 * <p>The port is tracked in a system property as well as a static field, because a Quarkus test run
 * loads this class in more than one classloader and the property is what those copies share.
 */
public final class EmbeddedPg {

  /** Zonky's superuser; its authentication is `trust`, so the password is a placeholder. */
  public static final String USER = "postgres";

  /** Any string does: the embedded instance trusts local connections. Never a real credential. */
  public static final String PASSWORD = "embedded";

  /** The database the @QuarkusTests run against. */
  public static final String DATABASE = "qits_offline_core_test";

  private static final String PORT_PROPERTY = "qits.test.embedded-pg.port";

  private static EmbeddedPostgres started;

  private EmbeddedPg() {}

  /** False under root, where {@code initdb} refuses to work. */
  public static boolean available() {
    return !"root".equals(System.getProperty("user.name"));
  }

  /** The port the one embedded instance listens on, starting it on the first call. */
  public static synchronized int port() {
    String recorded = System.getProperty(PORT_PROPERTY);
    if (recorded != null) {
      return Integer.parseInt(recorded);
    }
    try {
      started = EmbeddedPostgres.builder().start();
    } catch (Exception e) {
      throw new IllegalStateException("could not start the embedded postgres", e);
    }
    System.setProperty(PORT_PROPERTY, String.valueOf(started.getPort()));
    Runtime.getRuntime().addShutdownHook(new Thread(EmbeddedPg::stop, "embedded-pg-stop"));
    return started.getPort();
  }

  /** A JDBC url for the test database, creating it on first use. */
  public static String directUrl() {
    return url(DATABASE);
  }

  /** A JDBC url for {@code database}, creating it on first use. */
  public static synchronized String url(String database) {
    String admin = "jdbc:postgresql://localhost:" + port() + "/postgres";
    try (Connection connection = DriverManager.getConnection(admin, USER, PASSWORD);
        Statement sql = connection.createStatement()) {
      boolean exists;
      try (ResultSet found =
          sql.executeQuery("select 1 from pg_database where datname = '" + database + "'")) {
        exists = found.next();
      }
      if (!exists) {
        sql.execute("create database " + database);
      }
    } catch (Exception e) {
      throw new IllegalStateException("could not create the test database " + database, e);
    }
    return "jdbc:postgresql://localhost:" + port() + "/" + database;
  }

  private static synchronized void stop() {
    if (started != null) {
      try {
        started.close();
      } catch (Exception e) {
        // A JVM on its way out; a postgres that outlives it by a moment is not worth a stack trace.
      }
      started = null;
    }
  }
}
