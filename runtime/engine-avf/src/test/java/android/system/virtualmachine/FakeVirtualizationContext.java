package android.system.virtualmachine;

import android.content.Context;

/** Fixture Context with the official typed service and runtime permission methods. */
public final class FakeVirtualizationContext extends Context {
    private final VirtualMachineManager virtualizationService;
    private final boolean manageGranted;
    private final boolean customGranted;

    public FakeVirtualizationContext(Object virtualizationService) {
        this((VirtualMachineManager) virtualizationService, true, true);
    }

    public FakeVirtualizationContext(VirtualMachineManager manager, boolean manage, boolean custom) {
        this.virtualizationService = manager;
        this.manageGranted = manage;
        this.customGranted = custom;
    }

    @Override
    public <T> T getSystemService(Class<T> serviceClass) {
        return serviceClass == VirtualMachineManager.class ? serviceClass.cast(virtualizationService) : null;
    }

    @Override
    public int checkSelfPermission(String permission) {
        boolean granted = switch (permission) {
            case "android.permission.MANAGE_VIRTUAL_MACHINE" -> manageGranted;
            case "android.permission.USE_CUSTOM_VIRTUAL_MACHINE" -> customGranted;
            default -> false;
        };
        return granted ? 0 : -1;
    }
}
