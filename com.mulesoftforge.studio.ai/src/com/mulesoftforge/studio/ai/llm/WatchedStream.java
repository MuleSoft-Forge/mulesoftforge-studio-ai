package com.mulesoftforge.studio.ai.llm;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

// A response body read by one thread. It keeps what was read, for the raw log, and ends the read when nothing
// arrives for idle or when cancelled: it closes the stream and interrupts the reader, since a JDK response stream
// blocked in read() does not always return when closed from another thread.
final class WatchedStream extends FilterInputStream {
  private static final int KEEP_BYTES = 4 * 1024 * 1024;
  private static final ScheduledExecutorService WATCH = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread thread = new Thread(r, "MuleSoftForge Studio AI stream watch");
    thread.setDaemon(true);
    return thread;
  });

  private final long idleNanos;
  private final Thread reader = Thread.currentThread();
  private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
  private final ScheduledFuture<?> check;
  private final Object lock = new Object();
  private volatile long lastRead = System.nanoTime();
  private volatile boolean timedOut;
  private volatile boolean cancelled;
  private boolean finished;

  WatchedStream(InputStream in, Duration idle) {
    super(in);
    idleNanos = idle.toNanos();
    long period = Math.max(50, Math.min(1000, idle.toMillis() / 4));
    check = WATCH.scheduleAtFixedRate(() -> {
      if (System.nanoTime() - lastRead > idleNanos) {
        stop(false);
      }
    }, period, period, TimeUnit.MILLISECONDS);
  }

  @Override
  public int read() throws IOException {
    byte[] one = new byte[1];
    int n = read(one, 0, 1);
    return n < 0 ? -1 : one[0] & 0xff;
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    if (cancelled || timedOut) {
      throw new InterruptedIOException(cancelled ? "cancelled" : "idle");
    }
    int n = super.read(buffer, offset, length);
    if (n > 0) {
      lastRead = System.nanoTime();
      synchronized (kept) {
        kept.write(buffer, offset, Math.min(n, Math.max(0, KEEP_BYTES - kept.size())));
      }
    }
    return n;
  }

  void cancel() {
    stop(true);
  }

  // Ends the watch; clears an interrupt this stream caused, so the caller's thread carries on normally.
  void finish() {
    synchronized (lock) {
      finished = true;
    }
    check.cancel(false);
    try {
      in.close();
    } catch (IOException ignored) {
      // Already closed.
    }
    if ((cancelled || timedOut) && Thread.currentThread() == reader) {
      Thread.interrupted();
    }
  }

  boolean cancelled() {
    return cancelled;
  }

  boolean timedOut() {
    return timedOut && !cancelled;
  }

  String text() {
    synchronized (kept) {
      return kept.toString(StandardCharsets.UTF_8);
    }
  }

  private void stop(boolean byCancel) {
    synchronized (lock) {
      if (finished || cancelled || timedOut) {
        return;
      }
      if (byCancel) {
        cancelled = true;
      } else {
        timedOut = true;
      }
      reader.interrupt();
    }
    try {
      in.close();
    } catch (IOException ignored) {
      // The reader sees the end of the stream or an error either way.
    }
  }
}
