package com.google.android.diskusage.porter;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;

import com.google.android.diskusage.utils.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import eu.darken.porter.server.IPorterApplication;
import eu.darken.porter.server.IPorterService;

public class PorterClient {

    public interface OnPermissionResultListener {
        void onPermissionResult(int requestCode, boolean granted);
    }

    public interface OnBinderReceivedListener {
        void onBinderReceived();
    }

    public interface OnBinderLostListener {
        void onBinderLost();
    }

    private static final String TAG = "PorterClient";
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    private static final ExecutorService sAsyncExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PorterClient-worker");
        t.setDaemon(true);
        return t;
    });

    private static final Object sStateLock = new Object();
    private static final Object sProcessLock = new Object();

    private static final List<OnPermissionResultListener> sPermissionListeners = new CopyOnWriteArrayList<>();
    private static final List<OnBinderReceivedListener> sBinderListeners = new CopyOnWriteArrayList<>();
    private static final List<OnBinderLostListener> sBinderLostListeners = new CopyOnWriteArrayList<>();

    private static volatile IBinder sBinder;
    private static volatile IPorterService sService;
    private static volatile IBinder.DeathRecipient sDeathRecipient;
    private static volatile boolean sPermissionGranted = false;
    private static volatile boolean sUnsupported = false;

    private static final IPorterApplication sPorterApplication = new IPorterApplication.Stub() {
        @Override
        public void dispatchRequestPermissionResult(int requestCode, Bundle data) {
            // F4 fix: Pushed data bundle is authoritative. Never fallback to checkSelfPermission on Deny.
            boolean allowed = (data != null && data.containsKey("porter:permission-result-allowed"))
                    ? data.getBoolean("porter:permission-result-allowed", false)
                    : false;

            sPermissionGranted = allowed;
            Logger.getLOGGER().d("PorterClient: dispatchRequestPermissionResult: req=%d, allowed=%b", requestCode, allowed);

            sMainHandler.post(() -> {
                for (OnPermissionResultListener listener : sPermissionListeners) {
                    try {
                        listener.onPermissionResult(requestCode, allowed);
                    } catch (Throwable t) {
                        Logger.getLOGGER().e("Error in Porter permission listener: %s", t.getMessage());
                    }
                }
            });
        }

        @Override
        public void dispatchPermissionStateChanged(Bundle state) {
            // F4 & F5 fix: Read pushed state bundle directly without synchronous re-entrant IPC on binder thread
            if (state != null && state.containsKey("porter:reply-permission-granted")) {
                boolean granted = state.getBoolean("porter:reply-permission-granted", false);
                sPermissionGranted = granted;
                Logger.getLOGGER().d("PorterClient: dispatchPermissionStateChanged: granted=%b", granted);

                sMainHandler.post(() -> {
                    for (OnPermissionResultListener listener : sPermissionListeners) {
                        try {
                            listener.onPermissionResult(0, granted);
                        } catch (Throwable t) {
                            Logger.getLOGGER().e("Error in Porter permission listener: %s", t.getMessage());
                        }
                    }
                });
            }
        }
    };

    public static void init(Context context) {
        if (context == null) return;
        Context appContext = context.getApplicationContext();

        // F5 fix: offload all IPC to background worker thread
        sAsyncExecutor.execute(() -> {
            fetchBinderFromManagerInternal(appContext);

            try {
                Intent intent = new Intent("eu.darken.porter.intent.action.REQUEST_BINDER");
                intent.setPackage("eu.darken.porter");
                appContext.sendBroadcast(intent);
            } catch (Throwable ignored) {}
        });
    }

    public static void fetchBinderFromManager(Context context) {
        if (context == null) return;
        Context appContext = context.getApplicationContext();
        sAsyncExecutor.execute(() -> fetchBinderFromManagerInternal(appContext));
    }

    private static boolean fetchBinderFromManagerInternal(Context context) {
        if (isAlive()) {
            return true;
        }
        try {
            Bundle reply = context.getContentResolver().call(
                    Uri.parse("content://eu.darken.porter.porter.api"),
                    "getBinder",
                    null,
                    new Bundle()
            );
            if (reply != null) {
                IBinder binder = reply.getBinder("eu.darken.porter.server.extra.BINDER");
                if (binder != null && binder.isBinderAlive()) {
                    onBinderReceived(context, binder);
                    return true;
                }
            }
        } catch (Throwable t) {
            Logger.getLOGGER().d("PorterClient fetchBinderFromManager: %s", t.getMessage());
        }
        return false;
    }

    public static void onBinderReceived(Context context, IBinder binder) {
        if (binder == null) return;

        final IBinder targetBinder = binder;
        final Context appContext = context != null ? context.getApplicationContext() : null;

        IBinder.DeathRecipient recipient = new IBinder.DeathRecipient() {
            @Override
            public void binderDied() {
                Logger.getLOGGER().w("PorterClient: binder died: %s", targetBinder);
                synchronized (sStateLock) {
                    // F7 fix: Only clear if this exact binder is still the active one
                    if (sBinder == targetBinder) {
                        sBinder = null;
                        sService = null;
                        sPermissionGranted = false;
                        sDeathRecipient = null;
                        notifyBinderLost();
                    }
                }
                try {
                    targetBinder.unlinkToDeath(this, 0);
                } catch (Throwable ignored) {}
            }
        };

        synchronized (sStateLock) {
            if (sBinder == targetBinder && isAlive()) {
                return;
            }
            if (sBinder != null && sDeathRecipient != null) {
                try {
                    sBinder.unlinkToDeath(sDeathRecipient, 0);
                } catch (Throwable ignored) {}
            }

            sBinder = targetBinder;
            sDeathRecipient = recipient;
            try {
                targetBinder.linkToDeath(recipient, 0);
            } catch (Throwable ignored) {}

            sService = IPorterService.Stub.asInterface(targetBinder);
        }

        Logger.getLOGGER().i("PorterClient: binder received and attached to interface");

        // F5 fix: Perform attach on background worker thread without holding sStateLock
        sAsyncExecutor.execute(() -> {
            IPorterService service = sService;
            if (service != null && appContext != null) {
                try {
                    Bundle args = new Bundle();
                    args.putString("porter:attach-package-name", appContext.getPackageName());
                    args.putInt("porter:attach-protocol-version", 4);
                    Bundle reply = service.attach(sPorterApplication, args);
                    if (reply != null) {
                        // F6 fix: Check REPLY_UNSUPPORTED before assuming success
                        if (reply.getBoolean("porter:reply-unsupported", false)) {
                            int serverVer = reply.getInt("porter:reply-protocol-version", 0);
                            int minVer = reply.getInt("porter:reply-min-protocol-version", 0);
                            Logger.getLOGGER().w("PorterClient: attach refused (serverVer=%d, minVer=%d)", serverVer, minVer);
                            sUnsupported = true;
                            sPermissionGranted = false;
                            return;
                        }
                        sUnsupported = false;
                        sPermissionGranted = reply.getBoolean("porter:reply-permission-granted", false);
                    }
                    Logger.getLOGGER().i("PorterClient: attached with granted=%b", sPermissionGranted);
                } catch (Throwable t) {
                    Logger.getLOGGER().e("PorterClient attach failed: %s", t.getMessage());
                }
            }

            sMainHandler.post(() -> {
                for (OnBinderReceivedListener listener : sBinderListeners) {
                    try {
                        listener.onBinderReceived();
                    } catch (Throwable t) {
                        Logger.getLOGGER().e("Error in Porter binder listener: %s", t.getMessage());
                    }
                }
            });
        });
    }

    private static void notifyBinderLost() {
        sMainHandler.post(() -> {
            for (OnBinderLostListener listener : sBinderLostListeners) {
                try {
                    listener.onBinderLost();
                } catch (Throwable t) {
                    Logger.getLOGGER().e("Error in Porter binder lost listener: %s", t.getMessage());
                }
            }
        });
    }

    public static IBinder getBinder() {
        return sBinder;
    }

    public static boolean isAlive() {
        IBinder b = sBinder;
        return b != null && b.isBinderAlive() && sService != null && !sUnsupported;
    }

    public static boolean checkSelfPermission() {
        if (!isAlive()) return false;
        try {
            boolean granted = sService.checkSelfPermission();
            sPermissionGranted = granted;
            return granted;
        } catch (Throwable t) {
            Logger.getLOGGER().e("PorterClient checkSelfPermission failed: %s", t.getMessage());
            return false;
        }
    }

    public static boolean isGranted() {
        return isAlive() && sPermissionGranted;
    }

    public static boolean requestPermission(int requestCode) {
        if (!isAlive()) return false;
        try {
            Logger.getLOGGER().i("PorterClient: requesting permission for requestCode=%d", requestCode);
            sService.requestPermission(requestCode);
            return true;
        } catch (Throwable t) {
            Logger.getLOGGER().e("PorterClient requestPermission failed: %s", t.getMessage());
            return false;
        }
    }

    public static int getUid() {
        if (!isAlive()) return -1;
        try {
            return sService.getUid();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Escape an argument for safe single-quoted shell execution without subshell expansion.
     */
    public static String escapeShellArg(String arg) {
        if (arg == null) return "''";
        return "'" + arg.replace("'", "'\\''") + "'";
    }

    /**
     * Executes scan binary elevated via Porter.
     * Uses unique temp binary path, trap cleanup, and an asynchronous stdin-EOF monitor
     * that kills the process group immediately when PorterProcess.destroy() is called.
     */
    public static java.lang.Process runScannerProcess(String binaryPath, String root) throws IOException {
        synchronized (sProcessLock) { // F1 fix: Serialize scan execution per app PID
            if (!isAlive()) {
                throw new IOException("Porter service is not connected");
            }

            long timestamp = System.currentTimeMillis();
            int myPid = android.os.Process.myPid();
            String tmpBin = "/data/local/tmp/du_scan_" + myPid + "_" + timestamp;

            // F2 & F8 fix: Self-terminating wrapper with monitor subshell, setsid group, and EXIT trap
            String cmd = "(cat >/dev/null; kill -TERM -$PID 2>/dev/null || kill -TERM $PID 2>/dev/null; sleep 0.1; kill -KILL -$PID 2>/dev/null || kill -KILL $PID 2>/dev/null) & MON_PID=$!; "
                       + "TMP=" + escapeShellArg(tmpBin) + "; "
                       + "trap 'rm -f \"$TMP\"; kill $MON_PID 2>/dev/null' EXIT; "
                       + "cp -f " + escapeShellArg(binaryPath) + " \"$TMP\" && "
                       + "chmod 700 \"$TMP\" && "
                       + "(setsid \"$TMP\" " + escapeShellArg(root) + " 2>/dev/null || \"$TMP\" " + escapeShellArg(root) + ") & PID=$!; "
                       + "wait $PID; RET=$?; "
                       + "exit $RET";

            Logger.getLOGGER().d("PorterClient: executing via Porter: %s", cmd);

            // F3 fix: Create pipes safely inside try block with single null-guarded cleanup
            ParcelFileDescriptor[] stdinPipe = null;
            ParcelFileDescriptor[] stdoutPipe = null;
            ParcelFileDescriptor[] stderrPipe = null;
            Parcel data = null;
            Parcel reply = null;
            boolean success = false;

            try {
                stdinPipe = ParcelFileDescriptor.createPipe();
                stdoutPipe = ParcelFileDescriptor.createPipe();
                stderrPipe = ParcelFileDescriptor.createPipe();

                data = Parcel.obtain();
                reply = Parcel.obtain();

                data.writeInterfaceToken("eu.darken.porter.server.IPorterService");
                data.writeByte((byte) 0); // tty: Byte = 0
                data.writeFileDescriptor(stdinPipe[0].getFileDescriptor());
                data.writeFileDescriptor(stdoutPipe[1].getFileDescriptor());
                data.writeFileDescriptor(stderrPipe[1].getFileDescriptor());
                data.writeStringArray(new String[] { "sh", "-c", cmd });
                data.writeStringArray(new String[0]); // env
                data.writeString(null); // dir

                sBinder.transact(200, data, reply, 0); // TRANSACTION_createHost
                reply.readException();

                // Close remote ends locally; the remote process holds its own duplicates
                closeQuietly(stdinPipe[0]);
                stdinPipe[0] = null;
                closeQuietly(stdoutPipe[1]);
                stdoutPipe[1] = null;
                closeQuietly(stderrPipe[1]);
                stderrPipe[1] = null;

                PorterProcess process = new PorterProcess(sBinder, stdinPipe[1], stdoutPipe[0], stderrPipe[0]);
                success = true;
                return process;
            } catch (Throwable t) {
                throw new IOException("Failed to execute via Porter: " + t.getMessage(), t);
            } finally {
                if (data != null) data.recycle();
                if (reply != null) reply.recycle();
                if (!success) {
                    if (stdinPipe != null) {
                        closeQuietly(stdinPipe[0]);
                        closeQuietly(stdinPipe[1]);
                    }
                    if (stdoutPipe != null) {
                        closeQuietly(stdoutPipe[0]);
                        closeQuietly(stdoutPipe[1]);
                    }
                    if (stderrPipe != null) {
                        closeQuietly(stderrPipe[0]);
                        closeQuietly(stderrPipe[1]);
                    }
                }
            }
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try { c.close(); } catch (IOException ignored) {}
        }
    }

    public static void addPermissionResultListener(OnPermissionResultListener listener) {
        if (listener != null && !sPermissionListeners.contains(listener)) {
            sPermissionListeners.add(listener);
        }
    }

    public static void removePermissionResultListener(OnPermissionResultListener listener) {
        if (listener != null) {
            sPermissionListeners.remove(listener);
        }
    }

    public static void addBinderReceivedListener(OnBinderReceivedListener listener) {
        if (listener != null && !sBinderListeners.contains(listener)) {
            sBinderListeners.add(listener);
        }
    }

    public static void removeBinderReceivedListener(OnBinderReceivedListener listener) {
        if (listener != null) {
            sBinderListeners.remove(listener);
        }
    }

    public static void addBinderLostListener(OnBinderLostListener listener) {
        if (listener != null && !sBinderLostListeners.contains(listener)) {
            sBinderLostListeners.add(listener);
        }
    }

    public static void removeBinderLostListener(OnBinderLostListener listener) {
        if (listener != null) {
            sBinderLostListeners.remove(listener);
        }
    }
}
