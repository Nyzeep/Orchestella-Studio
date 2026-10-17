package io.github.nyzeep.orchestella.researchauth;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

// Research-only fixed synthetic roles. Never accept path/command or create a VM.
interface IIdentityProbe {
    Bundle inspect(long generation) = 0;
    void begin(int protocol, long generation) = 1;
    void end(long generation) = 2;
    Bundle inspectContextState(long generation) = 3;
    Bundle exerciseSynthetic(long generation, in ParcelFileDescriptor readOnly,
            in ParcelFileDescriptor readWrite) = 4;
    Bundle permissionSnapshot(long generation) = 5;
    Bundle grantSelected(long generation, boolean manage, boolean custom) = 6;
    Bundle restoreAdded(long generation) = 7;
    Bundle managerCapability(long generation) = 8;
    void destroy() = 16777114;
}
