package com.google.android.diskusage.datasource.fast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import androidx.annotation.NonNull;
import com.google.android.diskusage.porter.PorterClient;
import com.google.android.diskusage.utils.AppHelper;
import com.google.android.diskusage.utils.DeviceHelper;
import com.google.android.diskusage.utils.Logger;
import org.jetbrains.annotations.Contract;

public class NativeScannerStream extends InputStream {

  private final InputStream is;
  private final Process process;
  private final ByteArrayOutputStream stderrBuffer = new ByteArrayOutputStream();
  private final Thread stderrThread;

  public NativeScannerStream(InputStream is, Process process) {
    this.is = is;
    this.process = process;

    // F9 fix: Buffer stderr asynchronously in background daemon thread
    this.stderrThread = new Thread(() -> {
      try {
        InputStream es = process != null ? process.getErrorStream() : null;
        if (es != null) {
          byte[] buf = new byte[1024];
          int r;
          while ((r = es.read(buf)) > 0) {
            synchronized (stderrBuffer) {
              if (stderrBuffer.size() < 16384) { // Cap at 16KB
                stderrBuffer.write(buf, 0, r);
              }
            }
          }
        }
      } catch (Throwable ignored) {}
    }, "ScannerStream-stderr");
    this.stderrThread.setDaemon(true);
    this.stderrThread.start();
  }

  @Override
  public int read() throws IOException {
    return is.read();
  }

  @Override
  public int read(byte[] buffer) throws IOException {
    return is.read(buffer);
  }

  @Override
  public int read(byte[] buffer, int byteOffset, int byteCount)
      throws IOException {
    return is.read(buffer, byteOffset, byteCount);
  }

  public String getErrorOutput() {
    synchronized (stderrBuffer) {
      try {
        return stderrBuffer.toString("UTF-8").trim();
      } catch (Throwable ignored) {
        return stderrBuffer.toString().trim();
      }
    }
  }

  @Override
  public void close() throws IOException {
    try {
      if (process != null) {
        process.destroy();
      }
    } catch (Throwable ignored) {}
    try {
      if (stderrThread != null) {
        stderrThread.join(150);
      }
    } catch (Throwable ignored) {}
    try {
      is.close();
    } catch (Throwable ignored) {}
    try {
      if (process != null) {
        process.waitFor();
      }
    } catch (Throwable ignored) {}
  }

  public static class Factory {
    @NonNull
    @Contract("_, _ -> new")
    public static NativeScannerStream create(String path, boolean rootRequired)
        throws IOException, InterruptedException {
      return runScanner(path, rootRequired);
    }

    @NonNull
    @Contract("_, _ -> new")
    private static NativeScannerStream runScanner(String root, boolean rootRequired)
            throws IOException, InterruptedException {
      String binaryName = "libscan.so";
      String binaryPath = getScanBinaryPath(binaryName);
      Process process = null;

      // Always try su if rootRequired (cache may be stale or uninitialized for first scan)
      boolean useDirectSu = rootRequired || DeviceHelper.isDeviceRooted();
      boolean usePorter = DeviceHelper.isPorterGranted();
      boolean useShizuku = DeviceHelper.isShizukuGranted();

      // Fallback cascade: direct su -> Porter -> Shizuku
      if (useDirectSu) {
        try {
          process = runWithSu(binaryPath, root);
          // Check if su process died immediately (e.g. su binary broken or denied)
          try {
            Thread.sleep(50);
            int exit = process.exitValue();
            if (exit != 0) {
              Logger.getLOGGER().w("NativeScannerStream: su exited immediately with code %d, falling back", exit);
              process.destroy();
              process = null;
            }
          } catch (IllegalThreadStateException stillRunning) {
            // Process is alive and streaming
          }
        } catch (IOException e) {
          Logger.getLOGGER().e("NativeScannerStream: direct su failed: %s", e.getMessage());
          process = null;
        }
      }

      if (process == null && usePorter) {
        try {
          process = PorterClient.runScannerProcess(binaryPath, root);
        } catch (IOException e) {
          Logger.getLOGGER().e("NativeScannerStream: Porter execution failed: %s", e.getMessage());
          process = null;
        }
      }

      if (process == null && useShizuku) {
        try {
          process = runWithShizuku(binaryPath, root);
        } catch (IOException e) {
          Logger.getLOGGER().e("NativeScannerStream: Shizuku execution failed: %s", e.getMessage());
          process = null;
        }
      }

      if (process == null) {
        if (process == null && rootRequired) {
          throw new IOException("Failed to obtain elevated process (su, Porter or Shizuku) to scan " + root);
        }
        if (process == null) {
          process = Runtime.getRuntime().exec(new String[] { binaryPath, root });
        }
      }

      InputStream is = process.getInputStream();
      return new NativeScannerStream(is, process);
    }

    private static Process runWithShizuku(String binaryPath, String root) throws IOException {
      String cmd = "(setsid " + PorterClient.escapeShellArg(binaryPath) + " " + PorterClient.escapeShellArg(root)
                 + " 2>/dev/null || " + PorterClient.escapeShellArg(binaryPath) + " " + PorterClient.escapeShellArg(root) + ")";
      try {
        Logger.getLOGGER().d("NativeScannerStream: executing via Shizuku: %s", cmd);
        java.lang.reflect.Method m = rikka.shizuku.Shizuku.class.getDeclaredMethod(
            "newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (Process) m.invoke(null, new String[] { "sh", "-c", cmd }, null, null);
      } catch (Throwable t) {
        Throwable cause = t.getCause() != null ? t.getCause() : t;
        throw new IOException("Failed to execute via Shizuku: " + cause.getMessage(), cause);
      }
    }

    private static Process runWithSu(String binaryPath, String root) throws IOException {
      String cmd = PorterClient.escapeShellArg(binaryPath) + " " + PorterClient.escapeShellArg(root);
      IOException lastException = null;
      for (String su : new String[] { "su", "/system/bin/su", "/system/xbin/su" }) {
        try {
          Logger.getLOGGER().d("NativeScannerStream: executing via %s: %s", su, cmd);
          return Runtime.getRuntime().exec(new String[] { su, "-c", cmd });
        } catch (IOException e) {
          lastException = e;
        }
      }
      if (lastException != null) throw lastException;
      throw new IOException("Failed to execute su");
    }

    @NonNull
    private static String getScanBinaryPath(String binaryName) {
      return AppHelper.getAppContext().getApplicationInfo().nativeLibraryDir
              + "/" + binaryName;
    }
  }
}
