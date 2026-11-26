// SPDX-License-Identifier: GPL-3.0-or-later

package io.orchestella.displayprobe;

import android.app.Service;
import android.content.Intent;
import android.hardware.HardwareBuffer;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

public final class ProbeReceiver extends Service {
    static final String DESCRIPTOR = "io.orchestella.displayprobe.ProbeReceiver";

    private final Binder receiver = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code != FIRST_CALL_TRANSACTION) {
                return super.onTransact(code, data, reply, flags);
            }
            data.enforceInterface(DESCRIPTOR);
            boolean remote = getCallingPid() != Process.myPid();
            try (HardwareBuffer buffer = HardwareBuffer.CREATOR.createFromParcel(data)) {
                Log.i(NativeProbe.TAG, "ahb_binder received=" + (buffer != null)
                        + " distinct_process=" + remote);
                String result = "invalid_binder_transfer";
                if (remote && buffer != null) {
                    result = NativeProbe.importAndRead(buffer)
                            ? "binder_egl_pixel_match" : "binder_import_or_read_failed";
                }
                reply.writeNoException();
                reply.writeString(result);
            }
            return true;
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return receiver;
    }
}
