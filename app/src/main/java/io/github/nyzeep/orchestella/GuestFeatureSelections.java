package io.github.nyzeep.orchestella;

import android.content.Context;
import io.github.nyzeep.orchestella.runtime.contract.BridgeClient;
import io.github.nyzeep.orchestella.runtime.instance.features.GuestFeatureSelectionDriver;
import io.github.nyzeep.orchestella.runtime.instance.features.GuestFeatureStatus;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** Persists explicit experimental choices; no selection means no optional download. */
public final class GuestFeatureSelections {
    private static final Set<String> PROFILES = Set.of("desktop-labwc", "podman", "code-server", "docker-cli");
    private static final ConcurrentHashMap<String, BooleanSupplier> ACTIVE = new ConcurrentHashMap<>();
    private GuestFeatureSelections() { }

    public static void save(Context context, String instanceId, Set<String> profiles) {
        Set<String> selected = new HashSet<>(profiles);
        if (!PROFILES.containsAll(selected)) throw new IllegalArgumentException("unsupported optional profile");
        context.getSharedPreferences("optional-guest-features", Context.MODE_PRIVATE).edit()
                .putStringSet(instanceId, selected).apply();
    }

    public static void startSelected(Context context, String instanceId, BridgeClient client) {
        startSelected(context, instanceId, client, () -> {
            ExecutionSession current = ExecutionSessionRegistry.get(instanceId);
            return current != null && current.bridgeClient() == client;
        });
    }

    public static void startSelected(Context context, String instanceId, BridgeClient client,
            BooleanSupplier isCurrent) {
        if (client == null || instanceId == null || isCurrent == null) return;
        BooleanSupplier owner = () -> isCurrent.getAsBoolean() && !"failed".equals(client.readinessState());
        if (!owner.getAsBoolean()) return;
        Context app = context.getApplicationContext();
        Set<String> selected = new HashSet<>(app.getSharedPreferences("optional-guest-features", Context.MODE_PRIVATE)
                .getStringSet(instanceId, Set.of()));
        if (selected.isEmpty()) return;
        synchronized (GuestFeatureSelections.class) {
            BooleanSupplier previous = ACTIVE.get(instanceId);
            if (previous != null && previous.getAsBoolean()) return;
            ACTIVE.put(instanceId, owner);
        }
        BooleanSupplier currentOwner = () -> ACTIVE.get(instanceId) == owner && owner.getAsBoolean();
        Thread thread = new Thread(() -> {
            try {
                java.util.List<String> profiles = new java.util.ArrayList<>(selected);
                java.util.Collections.sort(profiles);
                for (String profile : profiles) {
                    if (!currentOwner.getAsBoolean()) return;
                    if (!PROFILES.contains(profile)) continue;
                    GuestFeatureSelectionDriver.drive(profile, new GuestFeatureSelectionDriver.Port() {
                        @Override
                        public boolean ready() { return client.isReady(); }

                        @Override
                        public GuestFeatureSelectionDriver.Observation status(String target) throws Exception {
                            if (!currentOwner.getAsBoolean()) return null;
                            BridgeClient.Result response = client.feature("STATUS", target);
                            if (!response.accepted() || !currentOwner.getAsBoolean()) return null;
                            GuestFeatureStatus status = GuestFeatureStatus.parse(response.payload());
                            if (!target.equals(status.profile())) return null;
                            org.json.JSONObject observation = new org.json.JSONObject(response.payload());
                            return new GuestFeatureSelectionDriver.Observation(status.state(), observation.optBoolean("job_active", false));
                        }

                        @Override
                        public boolean request(String action, String target) throws Exception {
                            return currentOwner.getAsBoolean() && client.feature(action, target).accepted();
                        }
                    }, currentOwner, consumed -> {
                        synchronized (GuestFeatureSelections.class) {
                            if (!owner.getAsBoolean() || ACTIVE.get(instanceId) != owner) return;
                            Set<String> pending = new HashSet<>(app.getSharedPreferences("optional-guest-features", Context.MODE_PRIVATE)
                                    .getStringSet(instanceId, Set.of()));
                            pending.remove(consumed);
                            if (owner.getAsBoolean()) save(app, instanceId, pending);
                        }
                    }, Thread::sleep);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // Retain pending choices for an explicit retry; do not cancel an accepted guest job.
            } finally {
                ACTIVE.remove(instanceId, owner);
            }
        }, "selected-guest-features");
        thread.setDaemon(true);
        thread.start();
    }
}
