package com.google.android.diskusage.porter;

import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;

import com.google.android.diskusage.utils.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Wraps a process spawned via Porter's porsh endpoint (transaction 200).
 * Implements full java.lang.Process lifecycle, bounded exit code polling,
 * and reliable cancellation by closing stdin to trigger the child self-kill monitor.
 */
public class PorterProcess extends Process {

    private final IBinder binder;
    private final ParcelFileDescriptor stdinPfd;
    private final ParcelFileDescriptor stdoutPfd;
    private final ParcelFileDescriptor stderrPfd;

    private final OutputStream stdin;
    private final InputStream stdout;
    private final InputStream stderr;

    private volatile boolean isDestroyed = false;
    private volatile boolean hasExited = false;
    private volatile int cachedExitCode = -1;

    public PorterProcess(IBinder binder,
                         ParcelFileDescriptor stdinPfd,
                         ParcelFileDescriptor stdoutPfd,
                         ParcelFileDescriptor stderrPfd) {
        this.binder = binder;
        this.stdinPfd = stdinPfd;
        this.stdoutPfd = stdoutPfd;
        this.stderrPfd = stderrPfd;

        this.stdin = stdinPfd != null ? new ParcelFileDescriptor.AutoCloseOutputStream(stdinPfd) : null;
        this.stdout = stdoutPfd != null ? new ParcelFileDescriptor.AutoCloseInputStream(stdoutPfd) : null;
        this.stderr = stderrPfd != null ? new ParcelFileDescriptor.AutoCloseInputStream(stderrPfd) : null;
    }

    @Override
    public OutputStream getOutputStream() {
        return stdin;
    }

    @Override
    public InputStream getInputStream() {
        return stdout;
    }

    @Override
    public InputStream getErrorStream() {
        return stderr;
    }

    private int transactExitCode() throws IOException {
        if (binder == null || !binder.isBinderAlive()) {
            throw new IOException("Porter service binder is dead or unavailable");
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("eu.darken.porter.server.IPorterService");
            binder.transact(202, data, reply, 0); // PorshConfig.TRANSACTION_getExitCode
            reply.readException();
            return reply.readInt();
        } catch (Throwable t) {
            throw new IOException("Failed to transact getExitCode on Porter: " + t.getMessage(), t);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    public synchronized int waitFor() throws InterruptedException {
        if (hasExited) {
            return cachedExitCode;
        }

        long deadline = System.currentTimeMillis() + 600_000L; // 10 minutes max scan timeout
        while (System.currentTimeMillis() < deadline) {
            if (binder == null || !binder.isBinderAlive()) {
                throw new IllegalStateException("Porter binder died while waiting for process");
            }
            try {
                int code = transactExitCode();
                if (code >= 0) {
                    hasExited = true;
                    cachedExitCode = code;
                    return code;
                }
            } catch (IOException e) {
                if (isDestroyed) {
                    hasExited = true;
                    cachedExitCode = 137; // SIGKILL equivalent (128 + 9)
                    return cachedExitCode;
                }
                throw new IllegalStateException("Error communicating with Porter during waitFor: " + e.getMessage(), e);
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Timeout waiting for Porter process to exit");
    }

    @Override
    public synchronized int exitValue() {
        if (hasExited) {
            return cachedExitCode;
        }
        try {
            int code = transactExitCode();
            if (code >= 0) {
                hasExited = true;
                cachedExitCode = code;
                return code;
            }
        } catch (Throwable ignored) {}

        throw new IllegalThreadStateException("Process has not exited yet");
    }

    @Override
    public synchronized void destroy() {
        if (isDestroyed) return;
        isDestroyed = true;

        // Closing stdin causes child subshell's 'cat > /dev/null' monitor to hit EOF,
        // which immediately executes kill -TERM and kill -KILL on the child process group.
        if (stdin != null) {
            try { stdin.close(); } catch (IOException ignored) {}
        }
        if (stdout != null) {
            try { stdout.close(); } catch (IOException ignored) {}
        }
        if (stderr != null) {
            try { stderr.close(); } catch (IOException ignored) {}
        }
    }
}
