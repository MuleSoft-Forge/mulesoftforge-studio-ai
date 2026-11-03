package com.mulesoftforge.studio.ai.check;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.XMLConstants;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.w3c.dom.ls.LSInput;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

// Validates MUnit suites against the schemas MUnit's runtime enforces: the munit (munit-runner) and munit-tools
// XSDs that Studio generates per runtime under <workspace>/.mule/plugins-tmp/repository, on a stand-in for the
// core schema. Elements of other namespaces are not checked. Without those files (a workspace that has not run or
// opened MUnit yet) validate returns false and MunitChecks' own shape checks apply.
public final class MunitSchemas {
  private static final String CORE_NS = "http://www.mulesoft.org/schema/mule/core";
  private static final String STUB = "mule-core-stub.xsd";
  private static final String RUNNER_SCHEMA = "META-INF/mule-munit.xsd";
  private static final int MAX_PROBLEMS = 12;

  private static volatile List<Path> repositories = List.of();
  private static volatile String cachedKey;
  private static volatile Schema cached;
  private static volatile String unavailable;

  private MunitSchemas() {}

  // Folders laid out like a Maven repository (com/mulesoft/munit/munit-tools/<version>/…xsd), searched in order;
  // the newest schema of each kind is used.
  public static void searchIn(List<Path> folders) {
    repositories = List.copyOf(folders);
  }

  // The folders under workspace/.mule/plugins-tmp/repository, one per runtime version Studio has used, then the
  // local Maven repository that holds the munit-runner jar. Empty when Studio has generated no schemas.
  public static List<Path> studioRepositories(Path workspace) {
    Path root = workspace.resolve(".mule").resolve("plugins-tmp").resolve("repository");
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    List<Path> folders = new ArrayList<>();
    try (Stream<Path> children = Files.list(root)) {
      children.filter(Files::isDirectory).sorted().forEach(folders::add);
    } catch (IOException e) {
      return List.of();
    }
    folders.add(Path.of(System.getProperty("user.home"), ".m2", "repository"));
    return folders;
  }

  public static boolean available() {
    return schema() != null;
  }

  // Why the schemas found could not be loaded, or null.
  public static String unavailable() {
    return schema() == null ? unavailable : null;
  }

  // Adds schema errors in MUnit elements to found; false when the schemas are not available.
  static boolean validate(String path, String xml, List<Problem> found) {
    Schema schema = schema();
    if (schema == null) {
      return false;
    }
    Set<String> seen = new LinkedHashSet<>();
    List<Problem> problems = new ArrayList<>();
    Validator validator = schema.newValidator();
    validator.setErrorHandler(new ErrorHandler() {
      @Override
      public void warning(SAXParseException e) {}

      @Override
      public void error(SAXParseException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (!message.contains("munit") || !seen.add(e.getLineNumber() + message)) {
          return;
        }
        problems.add(new Problem(path, e.getLineNumber(), "MUnit schema: " + readable(message)));
      }

      @Override
      public void fatalError(SAXParseException e) throws SAXException {
        throw e;
      }
    });
    try {
      validator.validate(new StreamSource(new StringReader(xml)));
    } catch (SAXException | IOException e) {
      // Not well-formed: MuleChecks reports that for the same file.
    }
    found.addAll(problems.subList(0, Math.min(problems.size(), MAX_PROBLEMS)));
    if (problems.size() > MAX_PROBLEMS) {
      found.add(new Problem(path, 0, "MUnit schema: " + (problems.size() - MAX_PROBLEMS) + " more errors like"
          + " the ones above."));
    }
    return true;
  }

  // "cvc-complex-type.4: Attribute 'processor' must appear on element 'munit-tools:mock-when'." without the code.
  private static String readable(String message) {
    return message.replaceFirst("^cvc-[\\w.-]+:\\s*", "");
  }

  // munit-tools comes from the schema Studio generates; munit from the mule-munit.xsd in the munit-runner jar,
  // which the runtime uses (Studio's generated munit-runner schema leaves out a test's behavior, execution and
  // validation). Without the jar, munit elements are skipped and only munit-tools ones are checked.
  private static Schema schema() {
    Optional<Path> tools = newest("munit-tools", "-mule-plugin.xsd");
    if (tools.isEmpty()) {
      return null;
    }
    Optional<Path> runner = newest("munit-runner", "-mule-plugin.jar");
    String key = tools.get() + "|" + modified(tools.get()) + "|" + runner.map(p -> p + "|" + modified(p)).orElse("");
    if (key.equals(cachedKey)) {
      return cached;
    }
    Schema schema;
    try (InputStream stub = MunitSchemas.class.getResourceAsStream(STUB)) {
      if (stub == null) {
        return null;
      }
      SchemaFactory factory = SchemaFactory.newDefaultInstance();
      factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file");
      byte[] core = stub.readAllBytes();
      factory.setResourceResolver((type, namespace, publicId, systemId, baseUri) ->
          CORE_NS.equals(namespace) ? new Input(core, systemId) : null);
      List<Source> sources = new ArrayList<>();
      sources.add(new StreamSource(new ByteArrayInputStream(core), STUB));
      byte[] munit = runner.isEmpty() ? null : entry(runner.get(), RUNNER_SCHEMA);
      if (munit != null) {
        sources.add(new StreamSource(new ByteArrayInputStream(munit), RUNNER_SCHEMA));
      }
      sources.add(new StreamSource(tools.get().toFile()));
      schema = factory.newSchema(sources.toArray(Source[]::new));
    } catch (SAXException | IOException | RuntimeException e) {
      schema = null;
      unavailable = e.getMessage();
    }
    cached = schema;
    cachedKey = key;
    return schema;
  }

  private static byte[] entry(Path jar, String name) {
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      ZipEntry entry = zip.getEntry(name);
      if (entry == null) {
        return null;
      }
      try (InputStream in = zip.getInputStream(entry)) {
        return in.readAllBytes();
      }
    } catch (IOException e) {
      return null;
    }
  }

  // The newest file of artifact whose name ends with suffix in the repositories.
  private static Optional<Path> newest(String artifact, String suffix) {
    List<Path> candidates = new ArrayList<>();
    for (Path repository : repositories) {
      Path folder = repository.resolve("com/mulesoft/munit").resolve(artifact);
      if (!Files.isDirectory(folder)) {
        continue;
      }
      try (Stream<Path> files = Files.walk(folder, 2)) {
        files.filter(p -> p.getFileName().toString().endsWith(suffix)).forEach(candidates::add);
      } catch (IOException e) {
        // Skip an unreadable folder.
      }
    }
    return candidates.stream().max(Comparator.comparingLong(MunitSchemas::modified));
  }

  private static long modified(Path file) {
    try {
      return Files.getLastModifiedTime(file).toMillis();
    } catch (IOException e) {
      return 0;
    }
  }

  private static final class Input implements LSInput {
    private final byte[] bytes;
    private String systemId;

    Input(byte[] bytes, String systemId) {
      this.bytes = bytes;
      this.systemId = systemId;
    }

    @Override
    public InputStream getByteStream() {
      return new ByteArrayInputStream(bytes);
    }

    @Override
    public String getSystemId() {
      return systemId;
    }

    @Override
    public void setSystemId(String systemId) {
      this.systemId = systemId;
    }

    @Override
    public Reader getCharacterStream() {
      return null;
    }

    @Override
    public void setCharacterStream(Reader characterStream) {}

    @Override
    public void setByteStream(InputStream byteStream) {}

    @Override
    public String getStringData() {
      return null;
    }

    @Override
    public void setStringData(String stringData) {}

    @Override
    public String getPublicId() {
      return null;
    }

    @Override
    public void setPublicId(String publicId) {}

    @Override
    public String getBaseURI() {
      return null;
    }

    @Override
    public void setBaseURI(String baseURI) {}

    @Override
    public String getEncoding() {
      return null;
    }

    @Override
    public void setEncoding(String encoding) {}

    @Override
    public boolean getCertifiedText() {
      return false;
    }

    @Override
    public void setCertifiedText(boolean certifiedText) {}
  }
}
