package android.content;

/** Test-only subset of the Android Context signatures used at the AVF boundary. */
public abstract class Context {
    public abstract <T> T getSystemService(Class<T> serviceClass);

    public abstract int checkSelfPermission(String permission);

    public abstract String getPackageName();
}
