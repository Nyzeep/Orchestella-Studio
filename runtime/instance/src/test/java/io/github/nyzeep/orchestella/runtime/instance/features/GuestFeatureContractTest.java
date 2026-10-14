package io.github.nyzeep.orchestella.runtime.instance.features;

/** Public input/output contracts; synthetic status never claims a guest ran. */
public final class GuestFeatureContractTest {
    private GuestFeatureContractTest() { }

    public static void main(String[] args) throws Exception {
        GuestFeatureStatus planned = GuestFeatureStatus.parse(
                "{\"version\":1,\"profile\":\"desktop-labwc\",\"state\":\"planned\",\"installed\":false,\"reason\":\"none\",\"generation\":\"none\"}");
        require(!planned.ready(), "planned must not be ready");
        require(!planned.input().keyboard(), "unprobed input must refuse");
        rejects(() -> GuestFeatureStatus.parse("{\"version\":2,\"profile\":\"podman\",\"state\":\"ready\"}"));
        rejects(() -> GuestFeatureStatus.parse("{\"version\":1,\"version\":1}"));
        rejects(() -> GuestFeatureStatus.parse("x".repeat(4097)));
        rejects(() -> GuestFeatureStatus.parse(
                "{\"version\":1,\"profile\":\"desktop-labwc\",\"state\":\"ready\",\"installed\":true,\"reason\":\"none\",\"generation\":\"00000000000000000000000000000000\"}"));
        GuestFeatureStatus awaiting = GuestFeatureStatus.parse(
                "{\"version\":1,\"profile\":\"desktop-labwc\",\"state\":\"awaiting-frame\",\"installed\":true,\"reason\":\"awaiting-host-frame\",\"generation\":\"0123456789abcdef0123456789abcdef\",\"capture_generation\":\"0123456789abcdef0123456789abcdef\",\"capture\":\"verified\",\"protocols\":\"verified\",\"input\":{\"keyboard\":true,\"unicodeText\":false,\"pointer\":true}}");
        require(!awaiting.ready(), "capture alone is not host confirmation");
        require(awaiting.input().keyboard() && awaiting.input().pointer(), "verified protocol input");
        require(!awaiting.input().unicodeText(), "Unicode applications remain unverified");
        require(GuestFeatureRequest.confirmFrame(awaiting).wireLine().equals(
                "ORCHESTELLA/FEATURES 1 CONFIRM-FRAME desktop-labwc 0123456789abcdef0123456789abcdef"), "exact frame command");
        rejects(() -> GuestFeatureRequest.command("INSTALL", "desktop-labwc;id"));
        rejects(() -> GuestFeatureRequest.command("EXEC", "podman"));
        rejects(() -> GuestFeatureRequest.command("EXPOSE", "podman"));
        rejects(() -> GuestFeatureRequest.confirmFrame(planned));
        rejects(() -> GuestFeatureRequest.resize(4096, 4096, 1));
        rejects(() -> GuestFeatureRequest.resize(1280, 720, Double.NaN));
        require(GuestFeatureRequest.resize(1280, 720, 1.0).cliArguments().equals(
                java.util.List.of("resize", "desktop-labwc", "1280", "720", "1.0")), "fixed resize arguments");
        GuestFeatureStatus editor = GuestFeatureStatus.parse(
                "{\"version\":1,\"profile\":\"code-server\",\"state\":\"ready\",\"installed\":true,\"reason\":\"none\",\"generation\":\"0123456789abcdef0123456789abcdef\",\"health\":\"authenticated-http\",\"authenticationConfigured\":true,\"exposed\":false}");
        require(editor.authenticationConfigured() && !editor.exposed(), "private authenticated editor");
        rejects(() -> GuestFeatureStatus.parse(
                "{\"version\":1,\"profile\":\"code-server\",\"state\":\"ready\",\"installed\":true,\"reason\":\"none\",\"generation\":\"0123456789abcdef0123456789abcdef\",\"health\":\"authenticated-http\",\"authenticationConfigured\":false}"));
        selectionDriverStopsBeforeEveryStaleMutation();
        selectionDriverRetainsPendingAfterAcceptedStartBecomesStale();
        selectionDriverStopsPollingWithoutCancellingGuestJob();
        selectionDriverWaitsThroughTransientReauthentication();
        System.out.println("GuestFeatureContractTest: contracts passed; synthetic only; guestValidated=false");
    }

    private static void selectionDriverStopsBeforeEveryStaleMutation() throws Exception {
        for (String state : java.util.List.of("planned", "installed", "ready")) {
            java.util.concurrent.atomic.AtomicBoolean current = new java.util.concurrent.atomic.AtomicBoolean(true);
            java.util.List<String> mutations = new java.util.ArrayList<>();
            GuestFeatureSelectionDriver.Port port = new GuestFeatureSelectionDriver.Port() {
                @Override public GuestFeatureSelectionDriver.Observation status(String profile) {
                    current.set(false);
                    return new GuestFeatureSelectionDriver.Observation(state, false);
                }
                @Override public boolean request(String action, String profile) { mutations.add(action); return true; }
            };
            GuestFeatureSelectionDriver.drive("podman", port, current::get,
                    profile -> mutations.add("SAVE"), millis -> { });
            require(mutations.isEmpty(), "stale STATUS may not install/start/save " + state);
        }
    }

    private static void selectionDriverRetainsPendingAfterAcceptedStartBecomesStale() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean current = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.List<String> mutations = new java.util.ArrayList<>();
        GuestFeatureSelectionDriver.Port port = new GuestFeatureSelectionDriver.Port() {
            @Override public GuestFeatureSelectionDriver.Observation status(String profile) {
                return new GuestFeatureSelectionDriver.Observation("installed", false);
            }
            @Override public boolean request(String action, String profile) {
                mutations.add(action);
                current.set(false);
                return true;
            }
        };
        GuestFeatureSelectionDriver.drive("podman", port, current::get,
                profile -> mutations.add("SAVE"), millis -> { });
        require(mutations.equals(java.util.List.of("START")), "accepted stale START keeps pending and never cancels guest");
    }

    private static void selectionDriverStopsPollingWithoutCancellingGuestJob() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean current = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.List<String> mutations = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger observations = new java.util.concurrent.atomic.AtomicInteger();
        GuestFeatureSelectionDriver.Port port = new GuestFeatureSelectionDriver.Port() {
            @Override public GuestFeatureSelectionDriver.Observation status(String profile) {
                observations.incrementAndGet();
                return new GuestFeatureSelectionDriver.Observation("installing", true);
            }
            @Override public boolean request(String action, String profile) { mutations.add(action); return true; }
        };
        GuestFeatureSelectionDriver.drive("podman", port, current::get, profile -> mutations.add("SAVE"),
                millis -> { require(millis <= 100, "stale guard polling has a short wait"); current.set(false); });
        require(observations.get() == 1 && mutations.isEmpty(), "old host driver exits without cancelling accepted job");
        current.set(true);
        GuestFeatureSelectionDriver.drive("podman", new GuestFeatureSelectionDriver.Port() {
            @Override public GuestFeatureSelectionDriver.Observation status(String profile) {
                return new GuestFeatureSelectionDriver.Observation("installed", false);
            }
            @Override public boolean request(String action, String profile) { mutations.add(action); return true; }
        }, current::get, profile -> mutations.add("SAVE"), millis -> { });
        require(mutations.equals(java.util.List.of("START", "SAVE")), "fresh driver resumes installed guest job once");
    }

    private static void selectionDriverWaitsThroughTransientReauthentication() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicInteger waits = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<String> calls = new java.util.ArrayList<>();
        GuestFeatureSelectionDriver.drive("podman", new GuestFeatureSelectionDriver.Port() {
            @Override public boolean ready() { return ready.get(); }
            @Override public GuestFeatureSelectionDriver.Observation status(String profile) {
                require(ready.get(), "STATUS waits for reauthentication");
                calls.add("STATUS");
                ready.set(false);
                return new GuestFeatureSelectionDriver.Observation("installed", false);
            }
            @Override public boolean request(String action, String profile) {
                require(ready.get(), "START waits for reauthentication");
                calls.add(action);
                ready.set(false);
                return true;
            }
        }, () -> true, profile -> { require(ready.get(), "SAVE waits for reauthentication"); calls.add("SAVE"); },
                millis -> { require(millis <= 100, "bounded transient poll"); waits.incrementAndGet(); ready.set(true); });
        require(waits.get() == 3 && calls.equals(java.util.List.of("STATUS", "START", "SAVE")),
                "same healthy owner and pending selection survive transient reauthentication");
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void rejects(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected deterministic refusal");
    }
}
