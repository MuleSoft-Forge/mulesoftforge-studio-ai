package com.mulesoftforge.studio.ai.launch;

// Starting and stopping the project's Mule app the way Studio's Run As and Debug As > Mule Application do. Studio
// backs it with its launch manager; the harness with a fake. mode is "run" or "debug".
public interface AppLauncher {
  // name is the launch configuration's name.
  record Running(String name, String mode) {}

  // The Mule app launch still running, preferring project's own; null when none is.
  Running running(String project);

  // Starts project in mode and returns once the launch is under way, not when the app has deployed.
  void start(String project, String mode) throws Exception;

  // Ends every running Mule app launch; false when none was running.
  boolean stop() throws Exception;

  enum Deployment { STARTED, FAILED }

  // What Studio's runtime reported for app since it was last started here, or null when it has reported nothing.
  // Null is also the answer where the runtime does not report deployments; callers then read the console.
  default Deployment deployment(String app) {
    return null;
  }
}
