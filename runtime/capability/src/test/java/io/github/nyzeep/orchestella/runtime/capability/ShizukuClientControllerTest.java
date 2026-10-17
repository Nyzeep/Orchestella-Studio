package io.github.nyzeep.orchestella.runtime.capability;

/** User-visible client behavior at the SDK transport boundary, no Android or VM fixtures. */
public final class ShizukuClientControllerTest {
    public static void main(String[] arguments) {
        missingManagerDoesNotRequestPermission();
        permissionIsRequestedOnlyByClickAndDenialDoesNotRepeat();
        deathAndReconnectRetireOldPermissionRequests();
        stoppedListenersCannotChangeAResumedClient();
        readFailureRetiresThePendingRequest();
        rememberedDenialAndOldServerNeverPrompt();
        permissionRequestTimeoutDoesNotAuthorizeOrRepeat();
        grantedReadbackRetiresTheOriginalTimeout();
    }

    private static void missingManagerDoesNotRequestPermission() {
        FakeTransport transport = new FakeTransport();
        transport.facts = new ShizukuClientController.Facts(
                ShizukuClientController.Installation.MISSING, false, -1, -1, false, false);
        ShizukuClientController client = new ShizukuClientController(transport, state -> {});
        client.start();
        require(client.snapshot().status() == ShizukuClientController.Status.NOT_INSTALLED,
                "missing manager must produce installation guidance");
        client.requestPermission();
        require(transport.requests == 0, "no permission request without manager and Binder");
        client.stop();
    }

    private static void permissionIsRequestedOnlyByClickAndDenialDoesNotRepeat() {
        FakeTransport transport = new FakeTransport();
        transport.facts = new ShizukuClientController.Facts(
                ShizukuClientController.Installation.INSTALLED, true, 2000, 13, false, false);
        ShizukuClientController client = new ShizukuClientController(transport, state -> {});
        client.start();
        require(client.snapshot().status() == ShizukuClientController.Status.UNAUTHORIZED,
                "live Binder alone is not client authorization");
        require(transport.requests == 0, "observing never prompts for permission");
        client.requestPermission();
        require(client.snapshot().status() == ShizukuClientController.Status.REQUESTING,
                "visible request enters pending state");
        client.requestPermission();
        require(transport.requests == 1, "a repeated click cannot duplicate a pending request");
        transport.events.permissionResult(transport.lastRequestCode, false);
        require(client.snapshot().status() == ShizukuClientController.Status.DENIED,
                "denial is visible without a new request");
        client.refresh();
        require(transport.requests == 1, "refresh after denial never prompts automatically");
        client.stop();
    }

    private static void deathAndReconnectRetireOldPermissionRequests() {
        FakeTransport transport = new FakeTransport();
        transport.facts = liveFacts(false);
        ShizukuClientController client = new ShizukuClientController(transport, state -> {});
        client.start();
        client.requestPermission();
        int retiredCode = transport.lastRequestCode;
        long retiredEpoch = client.snapshot().epoch();
        transport.facts = new ShizukuClientController.Facts(
                ShizukuClientController.Installation.INSTALLED, false, -1, -1, true, false);
        transport.events.binderDied();
        require(client.snapshot().status() == ShizukuClientController.Status.DEAD,
                "server death invalidates even a cached permission grant");
        transport.facts = liveFacts(false);
        transport.events.binderReceived();
        require(client.snapshot().status() == ShizukuClientController.Status.UNAUTHORIZED,
                "a new Binder must not inherit the retired request");
        require(client.snapshot().epoch() > retiredEpoch, "reconnect advances Binder epoch");
        client.requestPermission();
        int currentCode = transport.lastRequestCode;
        require(currentCode != retiredCode, "SDK result correlation needs a fresh process-wide code");
        transport.events.permissionResult(retiredCode, true);
        require(client.snapshot().status() == ShizukuClientController.Status.REQUESTING,
                "old SDK result must not complete the new request");
        transport.facts = liveFacts(true);
        transport.events.permissionResult(currentCode, true);
        require(client.snapshot().status() == ShizukuClientController.Status.AUTHORIZED,
                "current request plus current readback authorizes only the client");
        client.stop();
    }

    private static void stoppedListenersCannotChangeAResumedClient() {
        FakeTransport transport = new FakeTransport();
        transport.facts = liveFacts(false);
        ShizukuClientController client = new ShizukuClientController(transport, state -> {});
        client.start();
        ShizukuClientController.Events retired = transport.events;
        client.stop();
        require(transport.closes == 1, "lifecycle stop retires its SDK listeners");
        transport.facts = liveFacts(true);
        client.start();
        retired.binderDied();
        require(client.snapshot().status() == ShizukuClientController.Status.AUTHORIZED,
                "a queued old-listener death cannot invalidate the new lifecycle");
        client.stop();
        transport.events.binderReceived();
        require(client.snapshot().status() == ShizukuClientController.Status.STOPPED,
                "callbacks after stop must have no visible effect");
    }

    private static void readFailureRetiresThePendingRequest() {
        FakeTransport transport = new FakeTransport();
        transport.facts = liveFacts(false);
        ShizukuClientController client = new ShizukuClientController(transport, state -> {});
        client.start();
        client.requestPermission();
        int oldCode = transport.lastRequestCode;
        transport.readFailure = true;
        client.refresh();
        require(client.snapshot().status() == ShizukuClientController.Status.ERROR,
                "SDK read failure must be explicit and never preserve an authorized state");
        transport.readFailure = false;
        client.refresh();
        require(client.snapshot().status() == ShizukuClientController.Status.UNAUTHORIZED,
                "recovery from failure must not resurrect a retired request");
        transport.events.permissionResult(oldCode, true);
        require(client.snapshot().status() == ShizukuClientController.Status.UNAUTHORIZED,
                "a result retired by API failure is ignored");
        client.stop();
    }

    private static void rememberedDenialAndOldServerNeverPrompt() {
        FakeTransport transport = new FakeTransport();
        transport.facts = new ShizukuClientController.Facts(
                ShizukuClientController.Installation.INSTALLED, true, 2000, 13, false, true);
        ShizukuClientController client = new ShizukuClientController(transport, state -> {});
        client.start();
        client.requestPermission();
        require(client.snapshot().status() == ShizukuClientController.Status.DENIED_REMEMBERED,
                "remembered denial requires manager guidance");
        require(transport.requests == 0, "remembered denial is not prompted again");
        transport.facts = new ShizukuClientController.Facts(
                ShizukuClientController.Installation.INSTALLED, true, 2000, 10, true, false);
        transport.events.binderReceived();
        client.requestPermission();
        require(client.snapshot().status() == ShizukuClientController.Status.UNSUPPORTED,
                "unsupported server cannot be called as a compatible client");
        require(transport.requests == 0, "unsupported server cannot request permissions");
        client.stop();
    }

    private static void permissionRequestTimeoutDoesNotAuthorizeOrRepeat() {
        FakeTransport transport = new FakeTransport();
        transport.facts = liveFacts(false);
        ShizukuClientController client = new ShizukuClientController(transport, state -> {});
        client.start();
        client.requestPermission();
        int code = transport.lastRequestCode;
        client.requestTimedOut(code);
        require(client.snapshot().status() == ShizukuClientController.Status.TIMED_OUT,
                "no result within the visible deadline is a timeout, not success");
        transport.events.permissionResult(code, true);
        require(client.snapshot().status() == ShizukuClientController.Status.TIMED_OUT,
                "late result after timeout cannot authorize the client");
        require(transport.requests == 1, "timeout never retries by itself");
        client.stop();
    }

    private static void grantedReadbackRetiresTheOriginalTimeout() {
        FakeTransport transport = new FakeTransport();
        transport.facts = liveFacts(false);
        ShizukuClientController client = new ShizukuClientController(transport, state -> {});
        client.start();
        client.requestPermission();
        int code = transport.lastRequestCode;
        transport.facts = liveFacts(true);
        client.refresh();
        require(client.snapshot().status() == ShizukuClientController.Status.AUTHORIZED,
                "current granted readback can complete client authorization before SDK result");
        client.requestTimedOut(code);
        require(client.snapshot().status() == ShizukuClientController.Status.AUTHORIZED,
                "the original timeout cannot undo current granted readback");
        require(client.snapshot().requestCode() == -1,
                "granted readback retires the UI deadline correlation");
        client.stop();
    }

    private static ShizukuClientController.Facts liveFacts(boolean granted) {
        return new ShizukuClientController.Facts(
                ShizukuClientController.Installation.INSTALLED, true, 2000, 13, granted, false);
    }

    private static final class FakeTransport implements ShizukuClientController.Transport {
        ShizukuClientController.Facts facts;
        ShizukuClientController.Events events;
        int requests;
        int lastRequestCode;
        int closes;
        boolean readFailure;
        public ShizukuClientController.Registration observe(ShizukuClientController.Events listener) {
            events = listener;
            return () -> closes++;
        }
        public ShizukuClientController.Facts read() {
            if (readFailure) throw new IllegalStateException("synthetic SDK failure");
            return facts;
        }
        public void requestPermission(int requestCode) { requests++; lastRequestCode = requestCode; }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
