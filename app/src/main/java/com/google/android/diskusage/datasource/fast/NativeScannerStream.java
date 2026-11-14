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

      if (!(rootRequired && DeviceHelper.isDeviceRooted())) {
        try {
          process = Runtime.getRuntime().exec(new String[] { binaryPath, root });
        } catch (IOException e) {
          try {
            process = Runtime.getRuntime().exec(new String[] { "/system/bin/linker64", binaryPath, root });
          } catch (IOException e2) {
            process = Runtime.getRuntime().exec(new String[] { "/system/bin/linker", binaryPath, root });
          }
        }
      } else {
        String cmd = "chmod 755 \"" + binaryPath + "\" 2>/dev/null; \"" + binaryPath + "\" \"" + root + "\" || /system/bin/linker64 \"" + binaryPath + "\" \"" + root + "\" || /system/bin/linker \"" + binaryPath + "\" \"" + root + "\"";
        IOException lastException = null;
        for (String su : new String[] { "su", "/system/bin/su", "/system/xbin/su" }) {
          try {
            Logger.getLOGGER().d("NativeScannerStream: executing via %s: %s", su, cmd);
            process = Runtime.getRuntime().exec(new String[] { su, "-c", cmd });
            break;
          } catch (IOException e) {
            lastException = e;
          }
        }

        if (process == null && lastException != null) {
          throw lastException;
        }
      }
      InputStream is = process.getInputStream();
      return new NativeScannerStream(is, process);
    }

    @NonNull
    private static String getScanBinaryPath(String binaryName) {
      return AppHelper.getAppContext().getApplicationInfo().nativeLibraryDir
              + "/" + binaryName;
    }
  }
}
