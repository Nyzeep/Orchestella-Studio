package io.github.nyzeep.orchestella.researchauth;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import rikka.shizuku.Shizuku;

/** Manual QA only; no Intent command/path extras or exported business receiver. */
public final class ProbeActivity extends Activity {
    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private static final ExecutorService CALLS = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();
    private static final AtomicInteger REQUESTS = new AtomicInteger(5700);
    private TextView output;
    private boolean visible, busy, permissionSession;
    private Object activeOperation;
    private long epoch = SystemClock.elapsedRealtimeNanos(), pendingLife, pendingEpoch;
    private int pendingRequest = -1;
    private ServiceConnection connection;
    private IIdentityProbe service;
    private Shizuku.UserServiceArgs args;
    private Shizuku.OnBinderReceivedListener received;
    private Shizuku.OnBinderDeadListener dead;
    private Shizuku.OnRequestPermissionResultListener permission;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        args = new Shizuku.UserServiceArgs(new ComponentName(this, IdentityProbeService.class))
                .daemon(false).tag("identity-only").version(3).processNameSuffix("identity_qa").debuggable(false);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        button(layout, "1 Refresh App identity / client", this::refresh);
        button(layout, "2 Request client consent", this::request);
        button(layout, "3 Bind + inspect fixed identity", () -> bind(0));
        button(layout, "4 Remove UserService", () -> { close(); refresh(); });
        button(layout, "5 New synthetic Context / FD gates", () -> bind(1));
        button(layout, "6 Manager capability only - no VM", () -> bind(3));
        button(layout, "7 Open QA permission session - read only", () -> bind(2));
        button(layout, "8 Grant QA MANAGE only", () -> permissionAction(1));
        button(layout, "9 Grant QA CUSTOM only", () -> permissionAction(2));
        button(layout, "10 Grant QA both selected permissions", () -> permissionAction(3));
        button(layout, "11 Restore only this session additions", () -> permissionAction(4));
        button(layout, "12 QA permission readback", () -> permissionAction(0));
        output = new TextView(this);
        output.setTextIsSelectable(true);
        layout.addView(output);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
    }
    private void button(LinearLayout layout, String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setOnClickListener(view -> action.run());
        layout.addView(button);
    }
    @Override public void onStart() {
        super.onStart(); visible = true;
        final long life = ++pendingLife;
        received = () -> { if (visible && life == pendingLife) { pendingRequest = -1; close(); refresh(); } };
        dead = () -> {
            if (!visible || life != pendingLife) return;
            pendingRequest = -1; service = null; permissionSession = false; ++epoch;
            show("SERVER_DEAD_PERMISSION_RESTORE_NOT_CONFIRMED", null);
        };
        permission = (code, result) -> {
            if (!visible || life != pendingLife || code != pendingRequest || pendingEpoch != epoch) return;
            pendingRequest = -1; refresh();
        };
        Shizuku.addBinderDeadListener(dead, main);
        Shizuku.addRequestPermissionResultListener(permission, main);
        Shizuku.addBinderReceivedListenerSticky(received, main);
        refresh();
    }
    @Override public void onStop() {
        visible = false; ++pendingLife; pendingRequest = -1;
        Shizuku.removeBinderReceivedListener(received);
        Shizuku.removeBinderDeadListener(dead);
        Shizuku.removeRequestPermissionResultListener(permission);
        close(); super.onStop();
    }
    private boolean authorized() {
        return Shizuku.pingBinder() && Shizuku.getVersion() >= 13 && Shizuku.getUid() == 2000
                && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    }
    private void refresh() {
        Bundle facts = ProbeSnapshot.inspect(this, epoch, "APP");
        boolean alive = Shizuku.pingBinder();
        facts.putBoolean("binderAlive", alive);
        facts.putString("serverCanGrant", "NOT_QUERIED_CLIENT_UNAUTHORIZED");
        if (alive) {
            try { facts.putInt("serverUid", Shizuku.getUid()); }
            catch (RuntimeException failure) { facts.putString("serverUidError", ProbeErrors.kind(failure)); }
            try { facts.putInt("serverVersion", Shizuku.getVersion()); }
            catch (RuntimeException failure) { facts.putString("serverVersionError", ProbeErrors.kind(failure)); }
            boolean granted = false;
            try {
                granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
                facts.putBoolean("clientGranted", granted);
            } catch (RuntimeException failure) { facts.putString("clientPermissionError", ProbeErrors.kind(failure)); }
            try { facts.putBoolean("clientDeniedRemembered", Shizuku.shouldShowRequestPermissionRationale()); }
            catch (RuntimeException failure) { facts.putString("clientRationaleError", ProbeErrors.kind(failure)); }
            if (granted && Shizuku.pingBinder()) {
                try { facts.putString("serverCanGrant", Shizuku.checkRemotePermission(
                        "android.permission.GRANT_RUNTIME_PERMISSIONS") == PackageManager.PERMISSION_GRANTED
                        ? "GRANTED_TO_SERVER" : "DENIED_TO_SERVER"); }
                catch (RuntimeException failure) { facts.putString("serverCanGrant", "UNKNOWN_" + ProbeErrors.kind(failure)); }
            }
        }
        show("APP_SNAPSHOT", facts);
    }
    private void request() {
        try {
            if (!Shizuku.pingBinder() || Shizuku.getUid() != 2000 || Shizuku.getVersion() < 13) {
                show("SHELL_SERVER_V13_REQUIRED", null); return;
            }
            if (authorized()) { refresh(); return; }
            if (pendingRequest != -1) { show("REQUEST_ALREADY_PENDING", null); return; }
            if (Shizuku.shouldShowRequestPermissionRationale()) { show("DENIED_REMEMBERED_OPEN_MANAGER", null); return; }
            final int code = REQUESTS.incrementAndGet();
            pendingRequest = code; pendingEpoch = epoch;
            Shizuku.requestPermission(code);
            main.postDelayed(() -> {
                if (visible && pendingRequest == code) { pendingRequest = -1; show("CLIENT_WAIT_DEADLINE_NO_RETRY", null); }
            }, 30000);
        } catch (RuntimeException failure) { pendingRequest = -1; show("CLIENT_REQUEST_FAILED", null); }
    }
    private interface Operation { Bundle run() throws Exception; }
    private void submit(long binding, Operation operation, String outcome, boolean opensPermissionSession) {
        if (!IN_FLIGHT.compareAndSet(false, true)) { show("ONE_CALL_ONLY", null); return; }
        final Object token = new Object();
        activeOperation = token; busy = true;
        final Runnable deadline = () -> {
            if (activeOperation == token && visible && binding == epoch && busy) {
                ++epoch; permissionSession = false;
                show("CALL_DEADLINE_UNDERLYING_NOT_CANCELLED", null);
            }
        };
        CALLS.execute(() -> {
            Bundle result;
            String status = outcome;
            try { result = operation.run(); }
            catch (Exception | LinkageError failure) {
                result = new Bundle(); result.putString("callFailureKind", ProbeErrors.kind(failure));
                status = "FIXED_QA_OPERATION_FAILED";
            } finally { IN_FLIGHT.set(false); }
            final Bundle finished = result;
            final String finishedStatus = status;
            main.post(() -> {
                main.removeCallbacks(deadline);
                if (activeOperation != token) return;
                activeOperation = null; busy = false;
                if (visible && binding == epoch) {
                    if (opensPermissionSession && "OPEN_FOR_EXPLICIT_QA_PERMISSION_ACTIONS".equals(
                            finished.getString("sessionState"))) permissionSession = true;
                    show(finishedStatus, finished);
                }
            });
        });
        main.postDelayed(deadline, 5000);
    }
    private void bind(int mode) {
        try {
            if (!authorized()) { show("CLIENT_CONSENT_REQUIRED", null); return; }
            if (busy || IN_FLIGHT.get() || connection != null) { show("ONE_BIND_OR_CALL_ONLY", null); return; }
            final long binding = ++epoch;
            connection = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                    if (!visible || binding != epoch || connection != this) return;
                    final IIdentityProbe probe = IIdentityProbe.Stub.asInterface(binder);
                    service = probe;
                    submit(binding, () -> {
                        Bundle result = new Bundle();
                        try {
                            result.putString("callStage", "BEGIN");
                            probe.begin(ProbeSession.PROTOCOL, binding);
                            result.putString("callStage", "IDENTITY");
                            merge(result, "", probe.inspect(binding));
                            if (result.getLong("generation") != binding || result.getInt("uid") != 2000) {
                                throw new SecurityException("ACTOR_OR_EPOCH_REJECTED");
                            }
                            boolean stale = false;
                            try { probe.inspect(binding - 1); } catch (SecurityException expected) { stale = true; }
                            result.putBoolean("staleEpochRejected", stale);
                            if (mode == 1) {
                                result.putString("callStage", "SYNTHETIC_CLIENT_STAGES");
                                result.putString("filesystemAndVmState", "SYNTHETIC_STEPS_OBSERVED_SEE_EFFECT_LEDGER");
                                merge(result, "", QaFdClient.run(ProbeActivity.this, probe, binding));
                            } else if (mode == 3) {
                                result.putString("callStage", "FIXED_MANAGER_CAPABILITY");
                                merge(result, "serviceCapability_", probe.managerCapability(binding));
                                merge(result, "appCapability_", QaManagerCapability.inspect(ProbeActivity.this, binding));
                            } else if (mode == 2) {
                                result.putString("callStage", "FIXED_QA_PERMISSION_SNAPSHOT");
                                merge(result, "permission_", probe.permissionSnapshot(binding));
                                result.putString("sessionState", "OPEN_FOR_EXPLICIT_QA_PERMISSION_ACTIONS");
                                return result;
                            }
                            result.putString("callStage", "END");
                            probe.end(binding);
                            boolean ended = false;
                            try { probe.inspect(binding); } catch (SecurityException expected) { ended = true; }
                            result.putBoolean("endedEpochRejected", ended);
                        } catch (Exception | LinkageError failure) {
                            result.putString("callFailureKind", ProbeErrors.kind(failure));
                        }
                        return result;
                    }, mode == 1 ? "SYNTHETIC_STAGES_RECORDED" : mode == 2 ? "QA_PERMISSION_SESSION_RECORDED"
                            : mode == 3 ? "CAPABILITY_STAGES_RECORDED_NO_VM" : "IDENTITY_STAGES_RECORDED", mode == 2);
                }
                @Override public void onServiceDisconnected(ComponentName name) {
                    ProbeConnectionEvents.disconnected(binding, this, epoch, connection, visible, () -> {
                        service = null; permissionSession = false; ++epoch; show("USER_SERVICE_DISCONNECTED", null);
                    });
                }
            };
            Shizuku.bindUserService(args, connection);
            show("BIND_REQUESTED_DAEMON_FALSE", null);
        } catch (RuntimeException failure) { close(); show("USER_SERVICE_BIND_FAILED", null); }
    }
    private void permissionAction(int action) {
        try {
            if (!authorized() || !permissionSession || service == null || busy || IN_FLIGHT.get()) {
                show("OPEN_CURRENT_QA_PERMISSION_SESSION_REQUIRED", null); return;
            }
            final IIdentityProbe probe = service;
            final long binding = epoch;
            submit(binding, () -> {
                Bundle result;
                if (action == 4) result = probe.restoreAdded(binding);
                else if (action == 0) result = probe.permissionSnapshot(binding);
                else result = probe.grantSelected(binding, action == 1 || action == 3, action == 2 || action == 3);
                merge(result, "appReadback_", ProbeSnapshot.inspect(ProbeActivity.this, binding, "APP"));
                return result;
            }, "QA_PERMISSION_ACTION_RECORDED", false);
        } catch (RuntimeException failure) { show("QA_PERMISSION_ACTION_REJECTED", null); }
    }
    private void close() {
        ++epoch; permissionSession = false; service = null;
        ServiceConnection previous = connection; connection = null;
        if (previous != null && Shizuku.pingBinder()) {
            try { Shizuku.unbindUserService(args, previous, true); }
            catch (RuntimeException failure) { if (visible) show("REMOVE_NOT_CONFIRMED", null); }
        }
    }
    private static void merge(Bundle output, String prefix, Bundle fields) {
        for (String key : fields.keySet()) {
            Object value = fields.get(key);
            if (value instanceof Boolean) output.putBoolean(prefix + key, (Boolean) value);
            else if (value instanceof Integer) output.putInt(prefix + key, (Integer) value);
            else if (value instanceof Long) output.putLong(prefix + key, (Long) value);
            else output.putString(prefix + key, String.valueOf(value));
        }
    }
    private void show(String status, Bundle fields) {
        try {
            JSONObject data = new JSONObject();
            data.put("status", status); data.put("epoch", epoch);
            data.put("elapsedRealtimeMs", SystemClock.elapsedRealtime()); data.put("avfSuccess", false);
            if (fields != null) for (String key : fields.keySet()) data.put(key, fields.get(key));
            String text = data.toString(2); output.setText(text);
            try (FileOutputStream stream = openFileOutput("identity-receipt.json", MODE_PRIVATE)) {
                stream.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception failure) { output.setText("EVIDENCE_WRITE_FAILED"); }
    }
}
