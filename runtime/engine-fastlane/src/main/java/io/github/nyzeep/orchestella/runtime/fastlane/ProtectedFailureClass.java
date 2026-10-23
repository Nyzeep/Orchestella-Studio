package io.github.nyzeep.orchestella.runtime.fastlane;

/** PL-02 fixed failure classes; one class per stable reason code. */
public enum ProtectedFailureClass {
    PLATFORM_BLOCKED,
    PERMISSION,
    SCHEMA,
    LIFECYCLE,
    STOP,
    INSTALL,
    UNKNOWN
}
