package com.mulesoftforge.studio.ai.llm;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

// Every outbound HTTPS call is built here. Corporate networks (Zscaler and similar) re-sign HTTPS with
// their own root certificate; the JVM bundled with Studio does not trust it, so we also trust the
// operating system's certificates and an optional PEM file.
public final class Network {
  public record Trust(boolean useSystem, String caPemPath) {}

  // How many certificates came from each source, for Preferences and support.
  public record TrustReport(Map<String, Integer> sources, List<String> problems) {
    public String describe() {
      StringBuilder out = new StringBuilder("Trusted certificates: ");
      List<String> parts = new ArrayList<>();
      sources.forEach((source, count) -> parts.add(source + " " + count));
      out.append(String.join(", ", parts));
      problems.forEach(p -> out.append("\n").append(p));
      return out.toString();
    }
  }

  private static Trust trust = new Trust(true, "");
  private static SSLContext context;
  private static TrustReport report;

  private Network() {}

  public static synchronized void configure(Trust value) {
    Trust next = value == null ? new Trust(true, "") : new Trust(value.useSystem(),
        value.caPemPath() == null ? "" : value.caPemPath().trim());
    if (!next.equals(trust)) {
      trust = next;
      context = null;
      report = null;
    }
  }

  public static HttpClient.Builder builder() {
    return HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .proxy(ProxySelector.getDefault())
        .connectTimeout(Duration.ofSeconds(20))
        .sslContext(sslContext());
  }

  public static synchronized TrustReport report() {
    sslContext();
    return report;
  }

  private static synchronized SSLContext sslContext() {
    if (context != null) {
      return context;
    }
    Map<String, Integer> sources = new LinkedHashMap<>();
    List<String> problems = new ArrayList<>();
    try {
      KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
      store.load(null, null);
      sources.put("Java", add(store, "jvm", jvmDefaults()));
      if (trust.useSystem()) {
        try {
          sources.put("Operating system", add(store, "os", systemCertificates()));
        } catch (Exception e) {
          problems.add("Could not read the operating system's certificates: " + e.getMessage());
        }
      }
      if (!trust.caPemPath().isBlank()) {
        Path pemPath = Path.of(expandHome(trust.caPemPath()));
        try {
          if (!Files.exists(pemPath)) {
            problems.add("Corporate CA file not found: " + pemPath);
          } else {
            sources.put("PEM file", add(store, "pem", pem(pemPath)));
          }
        } catch (Exception e) {
          problems.add("Could not read " + trust.caPemPath() + ": " + e.getMessage());
        }
      }
      TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      factory.init(store);
      SSLContext ssl = SSLContext.getInstance("TLS");
      ssl.init(null, factory.getTrustManagers(), null);
      context = ssl;
    } catch (Exception e) {
      problems.add("Falling back to Java's default certificates: " + e.getMessage());
      try {
        context = SSLContext.getDefault();
      } catch (Exception fatal) {
        throw new IllegalStateException("No TLS available", fatal);
      }
    }
    report = new TrustReport(sources, problems);
    return context;
  }

  private static int add(KeyStore store, String prefix, Collection<? extends Certificate> certs) throws Exception {
    int added = 0;
    for (Certificate cert : certs) {
      if (cert instanceof X509Certificate x509 && store.getCertificateAlias(x509) == null) {
        store.setCertificateEntry(prefix + "-" + added, x509);
        added++;
      }
    }
    return added;
  }

  private static List<X509Certificate> jvmDefaults() throws Exception {
    TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init((KeyStore) null);
    List<X509Certificate> certs = new ArrayList<>();
    for (TrustManager manager : factory.getTrustManagers()) {
      if (manager instanceof X509TrustManager x509) {
        certs.addAll(List.of(x509.getAcceptedIssuers()));
      }
    }
    return certs;
  }

  private static List<Certificate> systemCertificates() throws Exception {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    List<Certificate> certs = new ArrayList<>();
    if (os.contains("win")) {
      certs.addAll(keyStore("Windows-ROOT"));
      certs.addAll(keyStore("Windows-MY"));
    } else if (os.contains("mac")) {
      certs.addAll(keyStore("KeychainStore"));
      // Corporate roots are usually installed in the System keychain, which Java 17 cannot read.
      for (String keychain : List.of("/Library/Keychains/System.keychain",
          "/System/Library/Keychains/SystemRootCertificates.keychain")) {
        certs.addAll(macKeychain(keychain));
      }
    } else {
      for (String bundle : List.of("/etc/ssl/certs/ca-certificates.crt", "/etc/pki/tls/certs/ca-bundle.crt",
          "/etc/ssl/cert.pem")) {
        Path path = Path.of(bundle);
        if (Files.isRegularFile(path)) {
          certs.addAll(pem(path));
          break;
        }
      }
    }
    return certs;
  }

  private static List<Certificate> keyStore(String type) {
    List<Certificate> certs = new ArrayList<>();
    try {
      KeyStore store = KeyStore.getInstance(type);
      store.load(null, null);
      Enumeration<String> aliases = store.aliases();
      while (aliases.hasMoreElements()) {
        Certificate cert = store.getCertificate(aliases.nextElement());
        if (cert != null) {
          certs.add(cert);
        }
      }
    } catch (Exception e) {
      // Not available on this JVM or OS version.
    }
    return certs;
  }

  private static Collection<? extends Certificate> macKeychain(String keychain) {
    try {
      Process process = new ProcessBuilder("/usr/bin/security", "find-certificate", "-a", "-p", keychain)
          .redirectErrorStream(false)
          .start();
      byte[] out = process.getInputStream().readAllBytes();
      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return List.of();
      }
      return parse(new ByteArrayInputStream(out));
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return List.of();
    }
  }

  // A file, or a folder of .pem / .crt / .cer files.
  private static List<Certificate> pem(Path path) throws Exception {
    List<Certificate> certs = new ArrayList<>();
    if (Files.isDirectory(path)) {
      try (Stream<Path> files = Files.list(path)) {
        for (Path file : files.filter(f -> f.toString().matches("(?i).*\\.(pem|crt|cer)$")).toList()) {
          certs.addAll(pem(file));
        }
      }
      return certs;
    }
    try (InputStream in = Files.newInputStream(path)) {
      certs.addAll(parse(in));
    }
    if (certs.isEmpty()) {
      throw new IllegalArgumentException("no certificates found");
    }
    return certs;
  }

  private static Collection<? extends Certificate> parse(InputStream in) throws Exception {
    byte[] bytes = in.readAllBytes();
    if (bytes.length == 0) {
      return List.of();
    }
    return CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(bytes));
  }

  private static String expandHome(String path) {
    return path.startsWith("~") ? System.getProperty("user.home") + path.substring(1) : path;
  }
}
