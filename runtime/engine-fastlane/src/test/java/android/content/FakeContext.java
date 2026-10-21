package android.content;

import android.system.virtualmachine.VirtualMachineManager;

/** Fake Context that returns one manager and one permission result. */
public final class FakeContext extends Context {
    private final VirtualMachineManager manager;
    private int permission;

    public FakeContext(VirtualMachineManager manager, int permission) {
        this.manager = manager;
        this.permission = permission;
    }

    public FakeContext(VirtualMachineManager manager) {
        this(manager, 0);
    }

    public void setPermission(int value) {
        permission = value;
    }

    @Override
    public <T> T getSystemService(Class<T> serviceClass) {
        return manager != null && serviceClass.isInstance(manager)
                ? serviceClass.cast(manager)
                : null;
    }

    @Override
    public int checkSelfPermission(String name) {
        return permission;
    }

    @Override
    public String getPackageName() {
        return "io.github.nyzeep.orchestella.test";
    }
}
