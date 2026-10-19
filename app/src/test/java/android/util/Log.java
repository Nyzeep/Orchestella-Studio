package android.util;

/**
 * No-op {@code android.util.Log} for JVM unit tests.
 *
 * <p>The mockable android.jar used by unit tests replaces every method body with
 * {@code throw new RuntimeException("not mocked")}. Production code legitimately
 * logs stop diagnostics, so tests that exercise an unconfirmed stop would fail on
 * the logger rather than on the behavior under test. Test sources precede the
 * mockable jar on the unit-test classpath, so this stub keeps existing assertions
 * intact without changing production code or loosening test expectations.
 */
public final class Log {
    private Log() {}

    public static int v(String tag, String message) { return 0; }
    public static int v(String tag, String message, Throwable throwable) { return 0; }

    public static int d(String tag, String message) { return 0; }
    public static int d(String tag, String message, Throwable throwable) { return 0; }

    public static int i(String tag, String message) { return 0; }
    public static int i(String tag, String message, Throwable throwable) { return 0; }

    public static int w(String tag, String message) { return 0; }
    public static int w(String tag, String message, Throwable throwable) { return 0; }

    public static int e(String tag, String message) { return 0; }
    public static int e(String tag, String message, Throwable throwable) { return 0; }

    public static int wtf(String tag, String message) { return 0; }
    public static int wtf(String tag, String message, Throwable throwable) { return 0; }

    public static int println(int priority, String tag, String message) { return 0; }

    public static boolean isLoggable(String tag, int level) { return false; }

    public static String getStackTraceString(Throwable throwable) { return ""; }
}
