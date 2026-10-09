# Orchestella Studio Proguard & R8 Symbol Protection Rules
# Phase 5.0 Release Pipeline Hardening (v0.5.0-alpha)

# 1. Preserve NDK JNI Bridge methods and classes
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class io.github.nyzeep.orchestella.NativeGlesSurfacePresenter {
    native <methods>;
    *;
}

-keep class io.github.nyzeep.orchestella.runtime.engineqemu.VirglJniBridge {
    native <methods>;
    *;
}

# 2. Preserve runtime contracts and wire protocols
-keep class io.github.nyzeep.orchestella.runtime.contract.** {
    *;
}

# 3. Preserve serialization models and metadata
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

-keep class * implements java.io.Serializable {
    *;
}

# 4. Preserve runtime instances, capabilities, and distro catalog models
-keep class io.github.nyzeep.orchestella.runtime.instance.** {
    *;
}

-keep class io.github.nyzeep.orchestella.runtime.capability.** {
    *;
}

-keep class io.github.nyzeep.orchestella.runtime.distro.** {
    *;
}

-keep class io.github.nyzeep.orchestella.runtime.network.** {
    *;
}

-keep class io.github.nyzeep.orchestella.runtime.engineqemu.** {
    *;
}

-keep class io.github.nyzeep.orchestella.runtime.engineavf.** {
    *;
}
