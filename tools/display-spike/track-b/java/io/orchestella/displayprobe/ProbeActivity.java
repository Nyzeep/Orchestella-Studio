// SPDX-License-Identifier: GPL-3.0-or-later

package io.orchestella.displayprobe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.widget.TextView;

public final class ProbeActivity extends Activity {
    private TextView status;
    private boolean bound;
    private boolean done;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            new Thread(() -> transferBuffer(service), "probe-transfer").start();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            complete("receiver_disconnected");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            complete("receiver_died");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            complete("receiver_unavailable");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        status = new TextView(this);
        status.setText("W4-05 display probe is running");
        setContentView(status);

        Log.i(NativeProbe.TAG, "BEGIN sdk=" + Build.VERSION.SDK_INT
                + " target_sdk=" + getApplicationInfo().targetSdkVersion);
        new Thread(() -> {
            NativeProbe.inspectHost();
            runOnUiThread(() -> {
                if (!isDestroyed()) {
                    bound = bindService(new Intent(this, ProbeReceiver.class),
                            connection, BIND_AUTO_CREATE);
                    if (!bound) {
                        complete("bind_failed");
                    }
                }
            });
        }, "probe-inspect").start();
    }

    private void transferBuffer(IBinder service) {
        String result = "allocation_failed";
        long usage = HardwareBuffer.USAGE_CPU_WRITE_RARELY
                | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT;
        try (HardwareBuffer buffer = HardwareBuffer.create(16, 16, HardwareBuffer.RGBA_8888,
                1, usage)) {
            Log.i(NativeProbe.TAG, "ahb_allocate status=ok");
            result = NativeProbe.fill(buffer) ? callReceiver(service, buffer) : "fill_failed";
        } catch (IllegalArgumentException exception) {
            Log.i(NativeProbe.TAG, "ahb_allocate status=failed");
        }
        String completedResult = result;
        runOnUiThread(() -> complete(completedResult));
    }

    private String callReceiver(IBinder service, HardwareBuffer buffer) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProbeReceiver.DESCRIPTOR);
            buffer.writeToParcel(data, 0);
            if (!service.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)) {
                return "transaction_unhandled";
            }
            reply.readException();
            return reply.readString();
        } catch (RemoteException | RuntimeException exception) {
            Log.i(NativeProbe.TAG, "ahb_binder status=failed error="
                    + exception.getClass().getSimpleName());
            return "transaction_failed";
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void complete(String result) {
        if (done) {
            return;
        }
        done = true;
        Log.i(NativeProbe.TAG, "END ahb_result=" + result);
        status.setText("W4-05 display probe finished\nAHardwareBuffer: " + result);
        releaseReceiver();
    }

    @Override
    protected void onDestroy() {
        releaseReceiver();
        super.onDestroy();
    }

    private void releaseReceiver() {
        if (bound) {
            unbindService(connection);
            bound = false;
        }
    }
}
