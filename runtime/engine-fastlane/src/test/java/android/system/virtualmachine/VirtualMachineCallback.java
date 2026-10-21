package android.system.virtualmachine;

/** Test-only callback surface, mirroring the Android 14/15 SystemApi. */
public interface VirtualMachineCallback {
    int ERROR_UNKNOWN = 0;
    int ERROR_PAYLOAD_VERIFICATION_FAILED = 1;
    int ERROR_PAYLOAD_CHANGED = 2;
    int ERROR_PAYLOAD_INVALID_CONFIG = 3;

    int STOP_REASON_VIRTUALIZATION_SERVICE_DIED = -1;
    int STOP_REASON_INFRASTRUCTURE_ERROR = 0;
    int STOP_REASON_KILLED = 1;
    int STOP_REASON_UNKNOWN = 2;
    int STOP_REASON_SHUTDOWN = 3;
    int STOP_REASON_START_FAILED = 4;
    int STOP_REASON_REBOOT = 5;
    int STOP_REASON_CRASH = 6;
    int STOP_REASON_PVM_FIRMWARE_PUBLIC_KEY_MISMATCH = 7;
    int STOP_REASON_PVM_FIRMWARE_INSTANCE_IMAGE_CHANGED = 8;
    int STOP_REASON_BOOTLOADER_PUBLIC_KEY_MISMATCH = 9;
    int STOP_REASON_BOOTLOADER_INSTANCE_IMAGE_CHANGED = 10;
    int STOP_REASON_MICRODROID_FAILED_TO_CONNECT_TO_VIRTUALIZATION_SERVICE = 11;
    int STOP_REASON_MICRODROID_PAYLOAD_HAS_CHANGED = 12;
    int STOP_REASON_MICRODROID_PAYLOAD_VERIFICATION_FAILED = 13;
    int STOP_REASON_MICRODROID_INVALID_PAYLOAD_CONFIG = 14;
    int STOP_REASON_MICRODROID_UNKNOWN_RUNTIME_ERROR = 15;
    int STOP_REASON_HANGUP = 16;

    void onPayloadStarted(VirtualMachine vm);

    void onPayloadReady(VirtualMachine vm);

    void onPayloadFinished(VirtualMachine vm, int exitCode);

    void onError(VirtualMachine vm, int errorCode, String message);

    void onStopped(VirtualMachine vm, int reason);
}
