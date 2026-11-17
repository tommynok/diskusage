package com.google.android.diskusage.datasource.fast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import androidx.annotation.NonNull;
import com.google.android.diskusage.utils.AppHelper;
import com.google.android.diskusage.utils.DeviceHelper;
import com.google.android.diskusage.utils.Logger;
import org.jetbrains.annotations.Contract;

public class NativeScannerStream extends InputStream {

  private final InputStream is;
  private final Process process;

  public NativeScannerStream(InputStream is, Process process) {
    this.is = is;
    this.process = process;
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
    try {
      InputStream es = process.getErrorStream();
      if (es != null) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        while (es.available() > 0) {
          int read = es.read(buf);
          if (read <= 0) break;
          baos.write(buf, 0, read);
        }
        return baos.toString("UTF-8").trim();
      }
    } catch (Throwable ignored) {}
    return "";
  }

  @Override
  public void close() throws IOException {
    is.close();
    try {
      process.waitFor();
    } catch (InterruptedException e) {
      throw new IOException(e.getMessage());
    }
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

      boolean useDirectSu = DeviceHelper.isDeviceRooted();
      boolean useShizuku = DeviceHelper.isShizukuGranted();

      if (useDirectSu) {
        try {
          process = runWithSu(binaryPath, root);
        } catch (IOException e) {
          Logger.getLOGGER().e("NativeScannerStream: direct su failed: %s", e.getMessage());
        }
      }

      if (process == null && useShizuku) {
        try {
          process = runWithShizuku(binaryPath, root);
        } catch (IOException e) {
          Logger.getLOGGER().e("NativeScannerStream: Shizuku execution failed: %s", e.getMessage());
        }
      }

      if (process == null) {
        if (rootRequired) {
          throw new IOException("Failed to obtain elevated process (su or Shizuku) to scan " + root);
        }
        process = Runtime.getRuntime().exec(new String[] { binaryPath, root });
      }

      InputStream is = process.getInputStream();
      return new NativeScannerStream(is, process);
    }

    private static Process runWithShizuku(String binaryPath, String root) throws IOException {
      String cmd = "TMP=/data/local/tmp/du_scan_$(id -u 2>/dev/null || echo 0); "
                 + "cp -f \"" + binaryPath + "\" \"$TMP\" 2>/dev/null; "
                 + "chmod 755 \"$TMP\" 2>/dev/null; "
                 + "\"$TMP\" \"" + root + "\" || \"" + binaryPath + "\" \"" + root + "\"";
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
      String cmd = "cp \"" + binaryPath + "\" /data/local/tmp/diskusage_scan 2>/dev/null; "
                 + "chmod 755 /data/local/tmp/diskusage_scan 2>/dev/null; "
                 + "/data/local/tmp/diskusage_scan \"" + root + "\" || \"" + binaryPath + "\" \"" + root + "\"";
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
