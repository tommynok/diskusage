package com.google.android.diskusage.porter;

import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class PorterProcess extends Process {

    private final IBinder binder;
    private final ParcelFileDescriptor stdinPfd;
    private final ParcelFileDescriptor stdoutPfd;
    private final ParcelFileDescriptor stderrPfd;

    private final OutputStream stdin;
    private final InputStream stdout;
    private final InputStream stderr;

    private Integer cachedExitCode = null;

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

    @Override
    public int waitFor() throws InterruptedException {
        if (cachedExitCode != null) {
            return cachedExitCode;
        }
        if (binder != null && binder.isBinderAlive()) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken("eu.darken.porter.server.IPorterService");
                binder.transact(202, data, reply, 0); // TRANSACTION_getExitCode
                reply.readException();
                cachedExitCode = reply.readInt();
                return cachedExitCode;
            } catch (Throwable ignored) {
            } finally {
                data.recycle();
                reply.recycle();
            }
        }
        return 0;
    }

    @Override
    public int exitValue() {
        if (cachedExitCode != null) {
            return cachedExitCode;
        }
        try {
            return waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalThreadStateException("Process has not exited yet");
        }
    }

    @Override
    public void destroy() {
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
