// SPDX-License-Identifier: GPL-3.0-or-later

package io.orchestella.displayprobe;

import android.hardware.HardwareBuffer;

final class NativeProbe {
    static final String TAG = "W405DisplayProbe";

    static {
        System.loadLibrary("displayprobe");
    }

    private NativeProbe() {}

    static native void inspectHost();
    static native boolean fill(HardwareBuffer buffer);
    static native boolean importAndRead(HardwareBuffer buffer);
}
