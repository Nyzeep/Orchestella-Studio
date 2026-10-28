package io.github.nyzeep.orchestella

/**
 * Pure decision rules for the two-package pVM accelerator split.
 *
 * The main package ships no pVM payload; the payload, the compiler engine and
 * the PVM-COMPILER-MANIFEST ride in a companion extension APK whose package
 * name is the main package name plus [EXTENSION_SUFFIX]. These rules decide,
 * from observable facts only, whether that extension is usable — every
 * outcome maps to one honest, user-facing reason. No Android types here so
 * the whole decision surface stays JVM-testable.
 *
 * Version lock (D4): the extension must carry the same versionCode as the
 * main package — a mismatched extension describes a payload this build was
 * never verified against, so it is refused, not best-effort loaded.
 *
 * Engine gate (session-14 lesson): a manifest whose engine is not exactly
 * [REQUIRED_ENGINE] is refused fail-closed. An absent engine (manifest older
 * than the field) is also refused — "unknown" is not "clang".
 */
object AccelExtensionRules {
    /** Extension package name = main package name + this suffix. */
    const val EXTENSION_SUFFIX = ".accel"

    /** The only engine the runtime admits. */
    const val REQUIRED_ENGINE = "clang"

    enum class Status {
        /** Extension installed, version locked, engine admissible. */
        OK,

        /** No extension package installed under the derived name. */
        EXTENSION_MISSING,

        /** Extension installed but its versionCode differs from the main package. */
        VERSION_MISMATCH,

        /** Manifest present but the engine field is not [REQUIRED_ENGINE]. */
        ENGINE_REJECTED,

        /** Extension present but its compiler manifest is missing or unreadable. */
        MANIFEST_UNREADABLE,
    }

    /**
     * One verdict plus a short, path-free detail string safe for UI display.
     */
    data class Decision(val status: Status, val detail: String)

    /** The extension package name for a given main package name. */
    fun extensionPackageName(mainPackageName: String): String {
        require(mainPackageName.isNotBlank()) { "mainPackageName must not be blank" }
        return mainPackageName + EXTENSION_SUFFIX
    }

    /**
     * @param extInstalled whether the extension package is installed
     * @param extVersionCode the extension's versionCode, or null when absent
     * @param mainVersionCode this (main) package's versionCode
     * @param engine the parsed manifest engine, or null when the manifest is
     *   missing/unreadable/unparseable
     */
    fun evaluate(
        extInstalled: Boolean,
        extVersionCode: Long?,
        mainVersionCode: Long,
        engine: String?,
    ): Decision = when {
        !extInstalled || extVersionCode == null ->
            Decision(Status.EXTENSION_MISSING, "未安装 pVM 加速扩展包")
        extVersionCode != mainVersionCode ->
            Decision(
                Status.VERSION_MISMATCH,
                "扩展包版本与主包不一致（扩展 $extVersionCode / 主包 $mainVersionCode），请安装配套版本",
            )
        engine == null ->
            Decision(Status.MANIFEST_UNREADABLE, "扩展包编译器清单缺失或不可读")
        engine != REQUIRED_ENGINE ->
            Decision(Status.ENGINE_REJECTED, "扩展包引擎校验未通过（engine=$engine），已拒绝启用")
        else -> Decision(Status.OK, "扩展包就绪")
    }
}
