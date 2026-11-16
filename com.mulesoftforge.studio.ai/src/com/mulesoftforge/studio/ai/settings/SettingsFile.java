package com.mulesoftforge.studio.ai.settings;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

// Settings in a .properties file that every Studio workspace and window reads. A read notices a save made by
// another window and reports the keys it changed. A save re-reads the file under a lock and changes only the
// keys it was given, so two windows saving at once keep each other's changes. The file is replaced atomically.
public final class SettingsFile implements KeyValues {
  // FileLock is held per process: two instances in one JVM must also queue here.
  private static final Object JVM_LOCK = new Object();

  private final Path file;
  private final Path lock;
  private final Consumer<Set<String>> onExternalChange;
  private Map<String, String> values = Map.of();
  private boolean loaded;
  private FileTime readTime;
  private long readSize = -1;

  public SettingsFile(Path file, Consumer<Set<String>> onExternalChange) {
    this.file = file;
    this.lock = file.resolveSibling(file.getFileName() + ".lock");
    this.onExternalChange = onExternalChange == null ? keys -> {} : onExternalChange;
  }

  public Path file() {
    return file;
  }

  @Override
  public String get(String key) {
    return all().getOrDefault(key, "");
  }

  @Override
  public Map<String, String> all() {
    Set<String> external;
    Map<String, String> current;
    synchronized (this) {
      external = refresh();
      current = values;
    }
    if (!external.isEmpty()) {
      onExternalChange.accept(external);
    }
    return current;
  }

  @Override
  public Set<String> update(Map<String, String> changes) throws IOException {
    Set<String> external;
    Set<String> changed = new HashSet<>();
    synchronized (JVM_LOCK) {
      Files.createDirectories(file.getParent());
      try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
          FileLock held = channel.lock()) {
        synchronized (this) {
          external = refresh();
          Map<String, String> next = new TreeMap<>(values);
          for (Map.Entry<String, String> change : changes.entrySet()) {
            String old = change.getValue() == null ? next.remove(change.getKey())
                : next.put(change.getKey(), change.getValue());
            if (!Objects.equals(old, change.getValue())) {
              changed.add(change.getKey());
            }
          }
          if (!changed.isEmpty()) {
            write(next);
            values = Collections.unmodifiableMap(next);
            stamp();
          }
        }
      }
    }
    external.removeAll(changed);
    if (!external.isEmpty()) {
      onExternalChange.accept(external);
    }
    return changed;
  }

  // Keys another window changed since the last read. The first read reports nothing.
  private Set<String> refresh() {
    FileTime time;
    long size;
    try {
      BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
      time = attributes.lastModifiedTime();
      size = attributes.size();
    } catch (IOException e) {
      time = null;
      size = -1;
    }
    if (loaded && Objects.equals(time, readTime) && size == readSize) {
      return new HashSet<>();
    }
    Map<String, String> next = time == null ? Map.of() : read();
    Set<String> changed = new HashSet<>();
    if (loaded) {
      Set<String> keys = new HashSet<>(values.keySet());
      keys.addAll(next.keySet());
      for (String key : keys) {
        if (!Objects.equals(values.get(key), next.get(key))) {
          changed.add(key);
        }
      }
    }
    values = next;
    loaded = true;
    readTime = time;
    readSize = size;
    return changed;
  }

  private Map<String, String> read() {
    Properties properties = new Properties();
    try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      properties.load(in);
    } catch (IOException | IllegalArgumentException e) {
      // Unreadable file: keep what was read before rather than reset every setting.
      return values;
    }
    Map<String, String> map = new TreeMap<>();
    properties.stringPropertyNames().forEach(name -> map.put(name, properties.getProperty(name)));
    return Collections.unmodifiableMap(map);
  }

  private void write(Map<String, String> next) throws IOException {
    Properties properties = new Properties();
    properties.putAll(next);
    Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
    try {
      try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
        properties.store(out, "MuleSoftForge Studio AI settings, shared by every workspace. API keys are in"
            + " Eclipse secure storage, not here.");
      }
      try {
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private void stamp() throws IOException {
    BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
    readTime = attributes.lastModifiedTime();
    readSize = attributes.size();
  }
}
