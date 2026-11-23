package io.github.nyzeep.orchestella;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import java.io.File;
import java.io.IOException;

/** Runs the second participant of the W3-03 gateway device test in the same app UID. */
public final class GatewaySlotProbeProvider extends ContentProvider {
    static final String IDENTIFY = "identify";
    static final String ACQUIRE = "acquire";
    static final String RELEASE = "release";
    static final String RESULT = "result";
    static final String INSTANCE_ID = "w303-gateway-probe";
    private NetworkGatewayDaemon.Running gateway;

    static NetworkGatewayDaemon.Running acquire(Context context) throws IOException {
        return NetworkGatewayDaemon.start(
                context, INSTANCE_ID, new File(context.getFilesDir(), INSTANCE_ID));
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public synchronized Bundle call(String method, String argument, Bundle extras) {
        if (!BuildConfig.IS_ISOLATED_QA_BUILD) {
            return null;
        }
        int result = switch (method) {
            case IDENTIFY -> Process.myPid();
            case ACQUIRE -> tryAcquire() ? 1 : 0;
            case RELEASE -> release() ? 1 : 0;
            default -> throw new IllegalArgumentException("unknown gateway probe command");
        };
        Bundle response = new Bundle();
        response.putInt(RESULT, result);
        return response;
    }

    private boolean tryAcquire() {
        if (gateway != null) return false;
        Context context = getContext();
        if (context == null) return false;
        try {
            gateway = acquire(context);
            return true;
        } catch (IOException unavailable) {
            return false;
        }
    }

    private boolean release() {
        if (gateway == null) return true;
        if (!gateway.stop(1_000)) return false;
        gateway = null;
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getType(Uri uri) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
