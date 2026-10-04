package android.system.virtualmachine;

/**
 * Test-only stand-in for the Android Context the reflective adapter queries
 * with {@code getSystemService("virtualization_service")}. Returns null for
 * every other service name so the adapter's manager-unavailable path is
 * exercised exactly as on a device without the service.
 */
public final class FakeVirtualizationContext {
    private final Object virtualizationService;

    public FakeVirtualizationContext(Object virtualizationService) {
        this.virtualizationService = virtualizationService;
    }

    public Object getSystemService(String name) {
        if ("virtualization_service".equals(name)) {
            return virtualizationService;
        }
        return null;
    }
}
