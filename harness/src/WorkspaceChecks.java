import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

import javax.tools.ToolProvider;

// check-workspace: Accept's writes in a real Eclipse workspace. A child JVM boots Equinox from Anypoint Studio's own
// plugins with the resources plugin, and runs WorkspaceWrites, compiled from the plugin source, where Eclipse
// enforces scheduling rules. Skipped when Studio is not installed (STUDIO_APP, as for install-dropins.sh).
final class WorkspaceChecks {
  private WorkspaceChecks() {}

  // In start order: Declarative Services first, because the content type manager the workspace needs is a component.
  private static final List<String> BUNDLES = List.of("org.osgi.service.prefs", "org.osgi.util.function",
      "org.osgi.util.promise", "org.osgi.service.component", "org.apache.felix.scr", "org.eclipse.equinox.common",
      "org.eclipse.core.jobs", "org.eclipse.equinox.registry",
      "org.eclipse.equinox.preferences", "org.eclipse.core.contenttype", "org.eclipse.equinox.app",
      "org.eclipse.core.runtime", "org.eclipse.core.expressions", "org.eclipse.core.filesystem",
      "org.eclipse.core.resources");
  private static final Path SOURCES = Path.of("workspace");
  private static final Path WRITES = Path.of("..", "com.mulesoftforge.studio.ai", "src", "com", "mulesoftforge",
      "studio", "ai", "ui", "WorkspaceWrites.java");

  static void run() throws Exception {
    Path plugins = Path.of(System.getenv().getOrDefault("STUDIO_APP", "/Applications/AnypointStudio728.app"),
        "Contents", "Eclipse", "plugins");
    if (!Files.isDirectory(plugins)) {
      System.out.println("SKIP  no Anypoint Studio plugins at " + plugins + ": set STUDIO_APP");
      System.exit(0);
    }
    Path osgi = bundle(plugins, "org.eclipse.osgi");
    List<Path> bundles = new ArrayList<>();
    for (String name : BUNDLES) {
      bundles.add(bundle(plugins, name));
    }
    Path work = Files.createTempDirectory("studio-ai-workspace");
    int failed;
    try {
      failed = launch(work, osgi, bundles);
    } finally {
      delete(work);
    }
    System.exit(failed == 0 ? 0 : 1);
  }

  private static int launch(Path work, Path osgi, List<Path> bundles) throws Exception {
    Path launcher = compile(work.resolve("launcher"), List.of(osgi), SOURCES.resolve("WorkspaceLauncher.java"));
    List<Path> classpath = new ArrayList<>(bundles);
    classpath.add(osgi);
    Path check = jar(compile(work.resolve("check"), classpath, SOURCES.resolve("WorkspaceCheck.java"), WRITES),
        work.resolve("check.jar"));
    List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", launcher + File.pathSeparator + osgi, "WorkspaceLauncher", work.toString(), check.toString()));
    bundles.forEach(b -> command.add(b.toString()));
    Path out = work.resolve("output.txt");
    Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(out.toFile()).start();
    boolean exited = process.waitFor(2, TimeUnit.MINUTES);
    if (!exited) {
      process.destroyForcibly();
    }
    int failed = 0;
    int results = 0;
    for (String line : Files.readAllLines(out, StandardCharsets.UTF_8)) {
      if (line.startsWith("PASS  ") || line.startsWith("FAIL  ")) {
        results++;
        failed += line.startsWith("FAIL") ? 1 : 0;
        System.out.println(line);
      } else {
        System.out.println("      " + line);
      }
    }
    return failed + check("the workspace check ran to the end", exited && process.exitValue() == 0 && results > 0);
  }

  private static Path bundle(Path plugins, String name) throws IOException {
    try (Stream<Path> files = Files.list(plugins)) {
      return files.filter(p -> p.getFileName().toString().startsWith(name + "_"))
          .max(Comparator.comparing(p -> p.getFileName().toString()))
          .orElseThrow(() -> new IllegalStateException("Studio has no " + name + " in " + plugins));
    }
  }

  private static Path compile(Path out, List<Path> classpath, Path... sources) throws IOException {
    Files.createDirectories(out);
    List<String> args = new ArrayList<>(List.of("-d", out.toString(), "-nowarn", "-proc:none", "-cp",
        String.join(File.pathSeparator, classpath.stream().map(Path::toString).toList())));
    for (Path source : sources) {
      args.add(source.toString());
    }
    if (ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)) != 0) {
      throw new IllegalStateException("could not compile " + List.of(sources));
    }
    return out;
  }

  private static Path jar(Path classes, Path jar) throws IOException {
    Manifest manifest = new Manifest();
    Attributes main = manifest.getMainAttributes();
    main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    main.putValue("Bundle-ManifestVersion", "2");
    main.putValue("Bundle-SymbolicName", "com.mulesoftforge.studio.ai.workspacecheck");
    main.putValue("Bundle-Version", "1.0.0");
    main.putValue("Require-Bundle", "org.eclipse.core.resources,org.eclipse.core.runtime");
    try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file, manifest);
        Stream<Path> paths = Files.walk(classes)) {
      for (Path path : paths.filter(Files::isRegularFile).toList()) {
        out.putNextEntry(new ZipEntry(classes.relativize(path).toString().replace(File.separatorChar, '/')));
        out.write(Files.readAllBytes(path));
        out.closeEntry();
      }
    }
    return jar;
  }

  private static void delete(Path root) throws IOException {
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  private static int check(String name, boolean ok) {
    System.out.println((ok ? "PASS" : "FAIL") + "  " + name);
    return ok ? 0 : 1;
  }
}
