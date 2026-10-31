package com.mulesoftforge.studio.ai.dataweave;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

// Downloads MuleSoft's DataWeave CLI release for this computer from GitHub, checks it against the SHA-256 published
// with that release, and unpacks it into a folder of its own. The version and checksums are pinned here, so a changed
// file is refused rather than run. There is no build for Intel Macs or ARM Linux.
public final class CliDownload {
  public static final String VERSION = "2.12.3";
  private static final String BASE = "https://github.com/mulesoft/data-weave-cli/releases/download/v" + VERSION
      + "/dw-cli-" + VERSION + "-";
  private static final int MAX_REDIRECTS = 5;

  public record Asset(String version, String platform, String url, String sha256) {}

  private CliDownload() {}

  public static Asset current() {
    return forPlatform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
  }

  // null when MuleSoft publishes no build for this operating system and processor.
  public static Asset forPlatform(String osName, String osArch) {
    String os = osName.toLowerCase(Locale.ROOT);
    String arch = osArch.toLowerCase(Locale.ROOT);
    boolean arm = arch.equals("aarch64") || arch.equals("arm64");
    boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
    if (os.startsWith("mac") && arm) {
      return asset("macOS (Apple silicon)", "macos-arm64",
          "a75acfaf8947df06b33bef2a2039758c54fa24e88b30ca331afc5d21a64facce");
    }
    if (os.startsWith("windows") && x64) {
      return asset("Windows x64", "windows-x86_64", "9961e8bd0cfaa9131602dc756bfa8559731d3be96ed91b5c34c760c6193eb262");
    }
    if (os.startsWith("linux") && x64) {
      return asset("Linux x64", "linux-x86_64", "e99fc66ca7922e7ba8a780ef24557fff3367ce129460e778d58ed05ae1b4950c");
    }
    return null;
  }

  private static Asset asset(String platform, String file, String sha256) {
    return new Asset(VERSION, platform, BASE + file + ".zip", sha256);
  }

  // Unpacks into target/<version> and returns the dw binary there.
  public static Path install(HttpClient http, Asset asset, Path target, BooleanSupplier cancelled) throws Exception {
    Files.createDirectories(target);
    Path zip = Files.createTempFile(target, "dw-cli-", ".zip");
    Path staging = target.resolve(asset.version() + ".partial");
    try {
      download(http, URI.create(asset.url()), zip, cancelled);
      String actual = sha256(zip);
      if (!actual.equalsIgnoreCase(asset.sha256())) {
        throw new IllegalStateException("The downloaded DataWeave CLI does not match its published checksum"
            + " (expected " + asset.sha256() + ", got " + actual + "), so it was not installed.");
      }
      delete(staging);
      unzip(zip, staging);
      Path home = target.resolve(asset.version());
      delete(home);
      Files.move(staging, home, StandardCopyOption.ATOMIC_MOVE);
      Path binary = home.resolve("bin").resolve(DataWeaveCli.binaryName());
      if (!Files.isRegularFile(binary)) {
        throw new IllegalStateException("The download has no bin/" + DataWeaveCli.binaryName());
      }
      try {
        Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwxr-xr-x"));
      } catch (UnsupportedOperationException windows) {
        // Windows runs dw.exe without an execute bit.
      }
      return binary;
    } finally {
      Files.deleteIfExists(zip);
      delete(staging);
    }
  }

  private static void download(HttpClient http, URI uri, Path file, BooleanSupplier cancelled) throws Exception {
    URI next = uri;
    for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
      if (cancelled.getAsBoolean()) {
        throw new java.util.concurrent.CancellationException("Download cancelled");
      }
      HttpRequest request = HttpRequest.newBuilder(next).timeout(Duration.ofMinutes(5)).GET().build();
      HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(file));
      int status = response.statusCode();
      if (status / 100 == 3) {
        String location = response.headers().firstValue("Location")
            .orElseThrow(() -> new IllegalStateException("Redirect without a Location from " + uri.getHost()));
        next = next.resolve(location);
        continue;
      }
      if (status != 200) {
        throw new IllegalStateException("Download failed: HTTP " + status + " from " + next.getHost());
      }
      return;
    }
    throw new IllegalStateException("Too many redirects downloading the DataWeave CLI");
  }

  static String sha256(Path file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream in = Files.newInputStream(file)) {
      byte[] buffer = new byte[64 * 1024];
      for (int n; (n = in.read(buffer)) > 0;) {
        digest.update(buffer, 0, n);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  // Refuses entries that would land outside dir.
  static void unzip(Path zip, Path dir) throws Exception {
    Path root = dir.toAbsolutePath().normalize();
    Files.createDirectories(root);
    try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
      for (ZipEntry entry; (entry = in.getNextEntry()) != null;) {
        Path out = root.resolve(entry.getName()).normalize();
        if (!out.startsWith(root)) {
          throw new IllegalStateException("The download has an entry outside its folder: " + entry.getName());
        }
        if (entry.isDirectory()) {
          Files.createDirectories(out);
        } else {
          Files.createDirectories(out.getParent());
          Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }

  private static void delete(Path dir) throws Exception {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    }
  }
}
