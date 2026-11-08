package eu.wohlben.qits.archrules;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Packs an already-compiled fixture package into a plain jar — never one named {@code
 * *-tests.jar} — so {@code EntityExposureTest} and {@code EntityExposureUsageTest} can import
 * their fixtures without the classes reading as test output themselves.
 *
 * <p>The fixtures stand in for a service's production classes (entities, DTOs, JAX-RS resources),
 * but javac necessarily compiles them, like any other test source, under this module's own {@code
 * target/test-classes}. Since {@link EntityExposure#isTestOutput} now skips every class imported
 * from a {@code test-classes} directory or a {@code -tests.jar} — on purpose, so a service's map
 * and gate stay the same under {@code mvn test} and {@code mvn verify} (qits-1945) — importing the
 * fixtures straight out of {@code target/test-classes} would make every self-test analyze nothing.
 * Re-packing them into an ordinary jar first keeps the fixtures real compiled bytecode, just no
 * longer sitting under a path {@code isTestOutput} recognizes.
 */
final class FixtureImport {

  private static final Map<String, Path> JARS = new ConcurrentHashMap<>();

  private FixtureImport() {}

  /** The plain jar holding {@code packageName}'s classes, built once and cached. */
  static Path jarOf(String packageName, Class<?> anchorInPackage) {
    return JARS.computeIfAbsent(packageName, pkg -> buildJar(pkg, anchorInPackage));
  }

  private static Path buildJar(String packageName, Class<?> anchorInPackage) {
    try {
      Path classesRoot =
          Path.of(anchorInPackage.getProtectionDomain().getCodeSource().getLocation().toURI());
      String relativePackagePath = packageName.replace('.', '/');
      Path packageDir = classesRoot.resolve(relativePackagePath);
      Path jar = Files.createTempFile("fixture-", ".jar");
      jar.toFile().deleteOnExit();
      try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
        try (var paths = Files.walk(packageDir)) {
          for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
            String entryName =
                relativePackagePath
                    + "/"
                    + packageDir.relativize(path).toString().replace(File.separatorChar, '/');
            out.putNextEntry(new JarEntry(entryName));
            Files.copy(path, out);
            out.closeEntry();
          }
        }
      }
      return jar;
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
