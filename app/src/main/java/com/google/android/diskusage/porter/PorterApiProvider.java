package com.google.android.diskusage.porter;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;

import com.google.android.diskusage.utils.Logger;

public class PorterApiProvider extends ContentProvider {

    private static final String METHOD_SEND_BINDER = "sendBinder";
    private static final String METHOD_GET_BINDER = "getBinder";
    private static final String EXTRA_BINDER = "eu.darken.porter.server.extra.BINDER";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (extras == null) return null;

        if (METHOD_SEND_BINDER.equals(method)) {
            IBinder binder = extras.getBinder(EXTRA_BINDER);
            if (binder != null && getContext() != null) {
                Logger.getLOGGER().i("PorterApiProvider: received sendBinder call");
                PorterClient.onBinderReceived(getContext().getApplicationContext(), binder);
                Bundle reply = new Bundle();
                reply.putBoolean("result", true);
                return reply;
            }
        } else if (METHOD_GET_BINDER.equals(method)) {
            IBinder binder = PorterClient.getBinder();
            if (binder != null && binder.isBinderAlive()) {
                Bundle reply = new Bundle();
                reply.putBinder(EXTRA_BINDER, binder);
                return reply;
            }
        }
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
