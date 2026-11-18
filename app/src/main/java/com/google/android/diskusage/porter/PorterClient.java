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

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import eu.darken.porter.server.IPorterApplication;
import eu.darken.porter.server.IPorterService;

public class PorterClient {

    public interface OnPermissionResultListener {
        void onPermissionResult(int requestCode, boolean granted);
    }

    public interface OnBinderReceivedListener {
        void onBinderReceived();
    }

    private static final String TAG = "PorterClient";
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    private static final List<OnPermissionResultListener> sPermissionListeners = new CopyOnWriteArrayList<>();
    private static final List<OnBinderReceivedListener> sBinderListeners = new CopyOnWriteArrayList<>();

    private static volatile IBinder sBinder;
    private static volatile IPorterService sService;
    private static volatile boolean sPermissionGranted = false;

    private static final IPorterApplication sPorterApplication = new IPorterApplication.Stub() {
        @Override
        public void dispatchRequestPermissionResult(int requestCode, Bundle data) {
            boolean allowed = (data != null && data.getBoolean("porter:permission-result-allowed", false))
                    || checkSelfPermission();
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
            boolean allowed = checkSelfPermission();
            sPermissionGranted = allowed;
            Logger.getLOGGER().d("PorterClient: dispatchPermissionStateChanged: allowed=%b", allowed);
        }
    };

    private static final IBinder.DeathRecipient sDeathRecipient = () -> {
        Logger.getLOGGER().w("PorterClient: binder died");
        sBinder = null;
        sService = null;
        sPermissionGranted = false;
    };

    public static void init(Context context) {
        if (context == null) return;
        Context appContext = context.getApplicationContext();

        // 1. Proactively query Porter manager provider if Porter is already running
        fetchBinderFromManager(appContext);

        // 2. Broadcast intent in case Porter service is active and listening for apps
        try {
            Intent intent = new Intent("eu.darken.porter.intent.action.REQUEST_BINDER");
            intent.setPackage("eu.darken.porter");
            appContext.sendBroadcast(intent);
        } catch (Throwable ignored) {}
    }

    public static boolean fetchBinderFromManager(Context context) {
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

    public static synchronized void onBinderReceived(Context context, IBinder binder) {
        if (binder == null) return;
        if (sBinder == binder && isAlive()) return;

        sBinder = binder;
        try {
            binder.linkToDeath(sDeathRecipient, 0);
        } catch (Throwable ignored) {}

        sService = IPorterService.Stub.asInterface(binder);
        Logger.getLOGGER().i("PorterClient: binder received and attached to interface");

        if (sService != null && context != null) {
            try {
                Bundle args = new Bundle();
                args.putString("porter:attach-package-name", context.getPackageName());
                args.putInt("porter:attach-protocol-version", 4);
                Bundle reply = sService.attach(sPorterApplication, args);
                if (reply != null) {
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
    }

    public static IBinder getBinder() {
        return sBinder;
    }

    public static boolean isAlive() {
        IBinder b = sBinder;
        return b != null && b.isBinderAlive() && sService != null;
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
        return isAlive() && (sPermissionGranted || checkSelfPermission());
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

    public static Process runScannerProcess(String binaryPath, String root) throws IOException {
        if (!isAlive()) {
            throw new IOException("Porter service is not connected");
        }
        String cmd = "TMP=/data/local/tmp/du_scan_$(id -u 2>/dev/null || echo 0); "
                   + "cp -f \"" + binaryPath + "\" \"$TMP\" 2>/dev/null; "
                   + "chmod 755 \"$TMP\" 2>/dev/null; "
                   + "\"$TMP\" \"" + root + "\" || \"" + binaryPath + "\" \"" + root + "\"";
        Logger.getLOGGER().d("PorterClient: executing via Porter: %s", cmd);

        ParcelFileDescriptor[] stdinPipe = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor[] stdoutPipe = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor[] stderrPipe = ParcelFileDescriptor.createPipe();

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("eu.darken.porter.server.IPorterService");
            data.writeByte((byte) 0); // tty: 0 = false
            data.writeFileDescriptor(stdinPipe[0].getFileDescriptor());
            data.writeFileDescriptor(stdoutPipe[1].getFileDescriptor());
            data.writeFileDescriptor(stderrPipe[1].getFileDescriptor());
            data.writeStringArray(new String[] { "sh", "-c", cmd });
            data.writeStringArray(new String[0]); // env
            data.writeString(null); // dir

            sBinder.transact(200, data, reply, 0); // TRANSACTION_createHost
            reply.readException();
        } catch (Throwable t) {
            try { stdinPipe[0].close(); } catch (Exception ignored) {}
            try { stdinPipe[1].close(); } catch (Exception ignored) {}
            try { stdoutPipe[0].close(); } catch (Exception ignored) {}
            try { stdoutPipe[1].close(); } catch (Exception ignored) {}
            try { stderrPipe[0].close(); } catch (Exception ignored) {}
            try { stderrPipe[1].close(); } catch (Exception ignored) {}
            throw new IOException("Failed to execute via Porter: " + t.getMessage(), t);
        } finally {
            data.recycle();
            reply.recycle();
            try { stdinPipe[0].close(); } catch (Exception ignored) {}
            try { stdoutPipe[1].close(); } catch (Exception ignored) {}
            try { stderrPipe[1].close(); } catch (Exception ignored) {}
        }

        return new PorterProcess(sBinder, stdinPipe[1], stdoutPipe[0], stderrPipe[0]);
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
}
