/*
 * SonarLint for Eclipse
 * Copyright (C) 2015-2016 SonarSource SA
 * sonarlint@sonarsource.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonarlint.eclipse.core.internal.jobs;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.eclipse.core.runtime.FileLocator;
import org.sonarlint.eclipse.core.internal.SonarLintCorePlugin;
import org.sonarsource.sonarlint.daemon.proto.SonarlintDaemon;
import org.sonarsource.sonarlint.daemon.proto.SonarlintDaemon.AnalysisReq;
import org.sonarsource.sonarlint.daemon.proto.SonarlintDaemon.Issue;
import org.sonarsource.sonarlint.daemon.proto.SonarlintDaemon.LogEvent;
import org.sonarsource.sonarlint.daemon.proto.SonarlintDaemon.RuleKey;
import org.sonarsource.sonarlint.daemon.proto.SonarlintDaemon.StandaloneConfiguration;
import org.sonarsource.sonarlint.daemon.proto.SonarlintDaemon.StandaloneConfiguration.Builder;
import org.sonarsource.sonarlint.daemon.proto.SonarlintDaemon.Void;
import org.sonarsource.sonarlint.daemon.proto.StandaloneSonarLintGrpc;
import org.sonarsource.sonarlint.daemon.proto.StandaloneSonarLintGrpc.StandaloneSonarLintBlockingStub;

public class StandaloneSonarLintClientFacade {

  private StandaloneSonarLintBlockingStub client;
  private ManagedChannel channel;
  private Process daemon;
  private StreamGobbler outputGobbler;
  private StreamGobbler errorGobbler;
  private ClientCall<Void, LogEvent> logCall;

  private synchronized StandaloneSonarLintBlockingStub getClient() {
    if (client == null) {
      SonarLintCorePlugin.getDefault().info("Starting standalone SonarLint engine");

      try {
        int freePort = getFreePort();
        URL daemonJarEntry = SonarLintCorePlugin.getDefault().getBundle().findEntries("/daemon", "*.jar", false).nextElement();
        File daemonJar = new File(FileLocator.toFileURL(daemonJarEntry).toURI());
        execute(Arrays.asList(thisJavaExe().getAbsolutePath(), "-jar", daemonJar.getAbsolutePath(), "--port", Integer.toString(freePort)));

        // TODO find a better way to wait for server to be started
        Thread.sleep(1000);

        Enumeration<URL> pluginEntries = SonarLintCorePlugin.getDefault().getBundle().findEntries("/plugins", "*.jar", false);

        channel = ManagedChannelBuilder.forAddress("localhost", freePort)
          .usePlaintext(true)
          .build();

        client = StandaloneSonarLintGrpc.newBlockingStub(channel);
        Builder configBuilder = StandaloneConfiguration.newBuilder();
        if (pluginEntries != null) {
          for (URL entry : Collections.list(pluginEntries)) {
            configBuilder.addPluginUrl(FileLocator.toFileURL(entry).toString());
          }
        }
        client.start(configBuilder
          // TODO I want to configure work dir
          // .setWorkDir(ResourcesPlugin.getWorkspace().getRoot().getLocation().append(".sonarlint").append("default").toFile().toPath())
          .build());

        logCall = channel.newCall(StandaloneSonarLintGrpc.METHOD_STREAM_LOGS, CallOptions.DEFAULT);
        logCall.start(new ClientCall.Listener<SonarlintDaemon.LogEvent>() {
          @Override
          public void onMessage(LogEvent message) {
            if (message.getIsDebug()) {
              SonarLintCorePlugin.getDefault().debug(message.getLog());
            } else {
              SonarLintCorePlugin.getDefault().info(message.getLog());
            }
          }
        }, new Metadata());
        logCall.sendMessage(Void.newBuilder().build());
        logCall.halfClose();
        logCall.request(Integer.MAX_VALUE);

      } catch (Throwable e) {
        SonarLintCorePlugin.getDefault().error("Unable to start SonarLint engine", e);
        client = null;
      }
    }
    return client;
  }

  private static boolean isWindows() {
    return System.getProperty("os.name").contains("Windows");
  }

  private static File thisJavaHome() {
    return new File(System.getProperty("java.home"));
  }

  /**
   * Path to the java executable used by this VM
   */
  private static File thisJavaExe() {
    File bin = new File(thisJavaHome(), "bin");
    return new File(bin, isWindows() ? "java.exe" : "java");
  }

  private void execute(List<String> command) {
    try {
      ProcessBuilder builder = new ProcessBuilder(command);
      daemon = builder.start();

      outputGobbler = new StreamGobbler(daemon.getInputStream(), false);
      errorGobbler = new StreamGobbler(daemon.getErrorStream(), true);
      outputGobbler.start();
      errorGobbler.start();
    } catch (Exception e) {
      throw new IllegalStateException("Fail to execute command", e);
    }
  }

  private void verifyGobbler(StreamGobbler gobbler, String type) {
    if (gobbler.getException() != null) {
      throw new IllegalStateException("Error inside " + type + " stream", gobbler.getException());
    }
  }

  private void closeStreams(Process process) {
    if (process != null) {
      closeQuietly(process.getInputStream());
      closeQuietly(process.getOutputStream());
      closeQuietly(process.getErrorStream());
    }
  }

  public static void closeQuietly(Closeable closeable) {
    try {
      if (closeable != null) {
        closeable.close();
      }
    } catch (IOException ioe) {
      // ignore
    }
  }

  private void waitUntilFinish(StreamGobbler thread) {
    if (thread != null) {
      try {
        thread.join();
      } catch (InterruptedException e) {
        System.err.println("InterruptedException while waiting finish of " + thread.toString());
        e.printStackTrace();
      }
    }
  }

  private static class StreamGobbler extends Thread {
    private final InputStream is;
    private volatile Exception exception;
    private final boolean error;

    StreamGobbler(InputStream is, boolean error) {
      super("ProcessStreamGobbler");
      this.is = is;
      this.error = error;
    }

    @Override
    public void run() {
      InputStreamReader isr = new InputStreamReader(is);
      BufferedReader br = new BufferedReader(isr);
      try {
        String line;
        while ((line = br.readLine()) != null) {
          consumeLine(line);
        }
      } catch (IOException ioe) {
        exception = ioe;

      } finally {
        closeQuietly(br);
        closeQuietly(isr);
      }
    }

    private void consumeLine(String line) {
      if (exception == null) {
        try {
          if (error) {
            SonarLintCorePlugin.getDefault().error("From syserr:" + line);
          } else {
            SonarLintCorePlugin.getDefault().info("From sysout:" + line);
          }
        } catch (Exception e) {
          exception = e;
        }
      }
    }

    public Exception getException() {
      return exception;
    }
  }

  private static int getFreePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }

  public Iterator<Issue> startAnalysis(AnalysisReq config) {
    return getClient().analyze(config);
  }

  public String getHtmlRuleDescription(String ruleKey) {
    return getClient().getRuleDetails(RuleKey.newBuilder().setKey(ruleKey).build()).getHtmlDescription();
  }

  public synchronized void stop() {
    if (channel != null) {
      logCall.cancel();
      channel.shutdownNow();
      try {
        channel.awaitTermination(2, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        // Ignore
      }
      channel = null;
      client = null;
      try {
        daemon.destroy();

      } catch (Exception e) {
        throw new IllegalStateException("Fail to stop daemon", e);
      } finally {
        waitUntilFinish(outputGobbler);
        waitUntilFinish(errorGobbler);
        closeStreams(daemon);
      }
      waitUntilFinish(outputGobbler);
      waitUntilFinish(errorGobbler);
      verifyGobbler(outputGobbler, "stdOut");
      verifyGobbler(errorGobbler, "stdErr");
    }
  }

}
