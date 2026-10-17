package io.github.nyzeep.orchestella.researchauth;

import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import rikka.shizuku.Shizuku;

/** Real Activity buttons/submit/lifecycle; synthetic main clock and remote Binder only. */
public final class ProbeActivityDeadlineHarness {
    private static int checks;
    private static void check(boolean value, String failure) {
        ++checks;
        if (!value) throw new AssertionError(failure);
    }
    private static Object field(ProbeActivity activity, String name) throws Exception {
        Field field = ProbeActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(activity);
    }
    private static boolean flag(ProbeActivity activity, String name) throws Exception {
        return (Boolean) field(activity, name);
    }
    private static long epoch(ProbeActivity activity) throws Exception { return (Long) field(activity, "epoch"); }
    private static boolean inFlight() throws Exception {
        return ((AtomicBoolean) field(null, "IN_FLIGHT")).get();
    }
    private static String text(ProbeActivity activity) throws Exception {
        return ((TextView) field(activity, "output")).getText();
    }
    private static void await(CountDownLatch latch, String stage) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("latch did not arrive: " + stage);
        } catch (InterruptedException failure) { throw new AssertionError("interrupted: " + stage); }
    }
    private static final class Screen {
        final ProbeActivity activity = new ProbeActivity();
        final int first = Button.all.size();
        Screen() { activity.onCreate(null); activity.onStart(); }
        void click(int number) { Button.all.get(first + number - 1).performClick(); }
        void connect(FakeProbe probe) {
            check(Shizuku.connection != null, "bind was not admitted");
            Shizuku.connection.onServiceConnected(null, probe);
        }
        void open(FakeProbe probe) throws Exception {
            click(7); connect(probe); Handler.takeCompletion().run.run();
            check(flag(activity, "permissionSession"), "permission session did not open");
        }
        void finishAction(int button) throws Exception {
            click(button); Handler.Task timer = Handler.latestTimer();
            Handler.takeCompletion().run.run();
            check(text(activity).contains("QA_PERMISSION_ACTION_RECORDED"), "permission action result not delivered");
            check(timer.removed, "normal action finish left its timer armed");
        }
    }
    private static final class FakeProbe implements IIdentityProbe {
        final ProbeSession session = new ProbeSession(10239);
        final FixedPermissionPlan.State[] states = {FixedPermissionPlan.State.DENIED, FixedPermissionPlan.State.DENIED};
        long generation;
        int grants, snapshots, restores;
        String block;
        CountDownLatch entered, released;
        final FixedPermissionPlan plan = new FixedPermissionPlan(new FixedPermissionPlan.Port() {
            public FixedPermissionPlan.Facts facts() {
                return new FixedPermissionPlan.Facts(true, true, 2000, 0, 10239, true, true, true, true, generation);
            }
            public FixedPermissionPlan.State read(FixedPermissionPlan.Permission permission) { return states[permission.ordinal()]; }
            public void grant(FixedPermissionPlan.Permission permission) { states[permission.ordinal()] = FixedPermissionPlan.State.GRANTED; }
            public void revoke(FixedPermissionPlan.Permission permission) { states[permission.ordinal()] = FixedPermissionPlan.State.DENIED; }
        });
        void block(String name) { block = name; entered = new CountDownLatch(1); released = new CountDownLatch(1); }
        void waitEntered() { await(entered, "remote entered"); }
        void release() { released.countDown(); }
        private void remote(String name, long epoch) {
            session.require(10239, epoch);
            if (name.equals(block)) {
                CountDownLatch ready = entered, release = released;
                block = null;
                ready.countDown(); await(release, "remote released");
            }
        }
        public void begin(int protocol, long epoch) { session.begin(10239, protocol, epoch); generation = epoch; }
        public void end(long epoch) { session.end(10239, epoch); }
        public Bundle inspect(long epoch) {
            session.require(10239, epoch);
            Bundle out = new Bundle(); out.putLong("generation", epoch); out.putInt("uid", 2000); return out;
        }
        private Bundle result(String operation) {
            Bundle out = new Bundle(); out.putString("permissionCall", operation);
            out.putString("qaManage", states[0].name()); out.putString("qaCustom", states[1].name()); return out;
        }
        public Bundle permissionSnapshot(long epoch) { remote("snapshot", epoch); ++snapshots; return result("SNAPSHOT"); }
        public Bundle grantSelected(long epoch, boolean manage, boolean custom) {
            remote("grant", epoch); ++grants;
            FixedPermissionPlan.Result result = plan.grantSelected(manage, custom);
            Bundle out = result("GRANT"); out.putString("permissionOutcome", result.outcome.name()); return out;
        }
        public Bundle restoreAdded(long epoch) {
            remote("restore", epoch); ++restores;
            FixedPermissionPlan.Result result = plan.restoreAdded(true);
            Bundle out = result("RESTORE"); out.putString("permissionOutcome", result.outcome.name()); return out;
        }
        public Bundle managerCapability(long epoch) { throw new AssertionError("not part of deadline test"); }
    }
    private static void sameSession(String operation, boolean replayFinish) throws Exception {
        Screen screen = new Screen(); FakeProbe probe = new FakeProbe();
        screen.click(7); screen.connect(probe);
        Handler.Task aTimer = Handler.latestTimer();
        Handler.Task aFinish = Handler.takeCompletion(); aFinish.run.run();
        long binding = epoch(screen.activity);
        check(flag(screen.activity, "permissionSession"), "A did not open session");
        if (!operation.equals("grant")) {
            SystemClock.now = 500;
            // Use the real permission plan to establish an addition for readback/restore.
            screen.click(8); Handler.takeCompletion().run.run();
            check(probe.states[0] == FixedPermissionPlan.State.GRANTED, "seed grant missing");
        }
        SystemClock.now = 1000;
        probe.block(operation);
        screen.click(operation.equals("grant") ? 8 : operation.equals("snapshot") ? 12 : 11);
        Handler.Task bTimer = Handler.latestTimer();
        probe.waitEntered();
        check(inFlight() && flag(screen.activity, "busy"), "B was not in flight");
        if (replayFinish) {
            aFinish.run.run();
            check(flag(screen.activity, "busy"), "old A result cleared B busy state");
        }
        Handler.advanceTo(5000);
        // Also exercise an already-dispatched stale callback even if removeCallbacks won.
        aTimer.run.run();
        System.out.println("TRACE A completed; B=" + operation + " blocked; A timer delivered; B current="
                + (epoch(screen.activity) == binding) + "; session=" + flag(screen.activity, "permissionSession"));
        check(epoch(screen.activity) == binding && flag(screen.activity, "permissionSession"), "stale A timer retired active B permission session");
        check(flag(screen.activity, "busy") && inFlight(), "stale A callback released active B");
        check(aTimer.removed && !bTimer.removed, "A finish did not retire only its own timer");
        probe.release(); Handler.Task bFinish = Handler.takeCompletion(); bFinish.run.run();
        check(!inFlight() && !flag(screen.activity, "busy"), "real B completion did not release its own flight");
        check(text(screen.activity).contains("QA_PERMISSION_ACTION_RECORDED")
                && text(screen.activity).contains("permissionCall=" + operation.toUpperCase()), "B result was not delivered");
        check(bTimer.removed && flag(screen.activity, "permissionSession"), "B finish did not retire timer and retain session");
        Handler.advanceTo(12000); bTimer.run.run(); aFinish.run.run();
        check(epoch(screen.activity) == binding && flag(screen.activity, "permissionSession"), "finished operation callback changed current session");
        screen.finishAction(11);
        check(probe.restores >= 1 && probe.states[0] == FixedPermissionPlan.State.DENIED, "restore unavailable after B result");
        check(!text(screen.activity).contains("REJECTED") && flag(screen.activity, "permissionSession"), "restore rejected the live session");
        System.out.println("TRACE B result delivered; explicit restore returned DENIED; live session retained");
    }
    private static void currentDeadline() throws Exception {
        Screen screen = new Screen(); FakeProbe probe = new FakeProbe(); screen.open(probe);
        long before = epoch(screen.activity);
        SystemClock.now = 1000; probe.block("grant"); screen.click(8);
        Handler.Task timer = Handler.latestTimer(); probe.waitEntered(); Handler.advanceTo(6000);
        check(text(screen.activity).contains("CALL_DEADLINE_UNDERLYING_NOT_CANCELLED"), "current deadline did not report uncancelled underlying call");
        check(epoch(screen.activity) == before + 1 && !flag(screen.activity, "permissionSession"), "current deadline did not retire current session");
        check(flag(screen.activity, "busy") && inFlight(), "deadline released global flight before real return");
        int binds = Shizuku.binds; Screen other = new Screen(); other.click(7);
        check(Shizuku.binds == binds && text(other.activity).contains("ONE_BIND_OR_CALL_ONLY"), "new Activity admitted another call before real return");
        probe.release(); Handler.Task finish = Handler.takeCompletion(); finish.run.run();
        check(!inFlight() && !flag(screen.activity, "busy") && timer.removed, "late real completion did not release own flight and timer");
        check(!flag(screen.activity, "permissionSession") && !text(screen.activity).contains("QA_PERMISSION_ACTION_RECORDED"), "late timed-out result revived session");
        screen.click(4); FakeProbe next = new FakeProbe(); screen.open(next);
        long current = epoch(screen.activity); timer.run.run(); finish.run.run();
        check(epoch(screen.activity) == current && flag(screen.activity, "permissionSession"), "old deadline/result retired later session");
        System.out.println("TRACE deadline kept global flight; real return released it; late callbacks ignored by next session");
    }
    private static void retiredSnapshot(String reason) throws Exception {
        Screen screen = new Screen(); FakeProbe probe = new FakeProbe(); probe.block("snapshot");
        screen.click(7); screen.connect(probe); Handler.Task timer = Handler.latestTimer(); probe.waitEntered();
        long before = epoch(screen.activity);
        if (reason.equals("deadline")) Handler.advanceTo(5000);
        else if (reason.equals("end")) { screen.click(4); probe.end(before); }
        else Shizuku.die();
        String retiredText = text(screen.activity);
        check(epoch(screen.activity) > before && !flag(screen.activity, "permissionSession"), "snapshot session not retired");
        check(inFlight() && flag(screen.activity, "busy"), "retirement released underlying flight");
        probe.release(); Handler.Task finish = Handler.takeCompletion(); finish.run.run();
        check(!flag(screen.activity, "permissionSession"), "late snapshot reopened retired permission session: " + reason);
        check(text(screen.activity).equals(retiredText), "late snapshot overwrote retirement receipt");
        check(!inFlight() && !flag(screen.activity, "busy") && timer.removed, "late snapshot left own flight/timer active");
        Shizuku.alive = true; screen.click(4); screen.open(new FakeProbe());
        long current = epoch(screen.activity); timer.run.run(); finish.run.run();
        check(epoch(screen.activity) == current && flag(screen.activity, "permissionSession"), "retired snapshot callback affected later session");
        System.out.println("TRACE " + reason + " retired snapshot; late result did not reopen session; next session remained live");
    }
    private static void oldActivity() throws Exception {
        Screen old = new Screen(); FakeProbe a = new FakeProbe(); a.block("snapshot");
        old.click(7); old.connect(a); Handler.Task aTimer = Handler.latestTimer(); a.waitEntered();
        Shizuku.OnBinderDeadListener oldDead = Shizuku.dead;
        old.activity.onStop();
        Screen current = new Screen(); int binds = Shizuku.binds; current.click(7);
        check(inFlight() && Shizuku.binds == binds, "Activity stop released shared underlying flight");
        a.release(); Handler.Task aFinish = Handler.takeCompletion();
        check(!inFlight(), "real A return did not release global flight");
        FakeProbe b = new FakeProbe(); b.block("snapshot"); current.click(7); current.connect(b);
        Handler.Task bTimer = Handler.latestTimer(); b.waitEntered();
        long binding = epoch(current.activity);
        aFinish.run.run(); aTimer.run.run(); oldDead.onBinderDead();
        check(!flag(old.activity, "permissionSession"), "old Activity late worker reopened its retired session");
        check(flag(current.activity, "busy") && inFlight() && epoch(current.activity) == binding, "old Activity callback cleared new Activity call");
        check(aTimer.removed && !bTimer.removed, "old Activity finish cancelled another Activity timer");
        b.release(); Handler.takeCompletion().run.run();
        check(flag(current.activity, "permissionSession") && !flag(current.activity, "busy"), "new Activity snapshot result lost");
        current.finishAction(8); current.finishAction(11);
        check(b.states[0] == FixedPermissionPlan.State.DENIED, "new Activity restore unavailable");
        System.out.println("TRACE old Activity stop/result/timer/death isolated; new Activity result and restore preserved");
    }
    public static void main(String[] args) {
        int exit = 0;
        try {
            String scenario = args[0];
            if (scenario.equals("same-session-grant")) sameSession("grant", false);
            else if (scenario.equals("same-session-readback")) sameSession("snapshot", false);
            else if (scenario.equals("same-session-restore")) sameSession("restore", false);
            else if (scenario.equals("replayed-finish")) sameSession("grant", true);
            else if (scenario.equals("current-deadline")) currentDeadline();
            else if (scenario.equals("snapshot-deadline")) retiredSnapshot("deadline");
            else if (scenario.equals("end-during-snapshot")) retiredSnapshot("end");
            else if (scenario.equals("death-during-snapshot")) retiredSnapshot("death");
            else if (scenario.equals("old-activity")) oldActivity();
            else throw new AssertionError("unknown scenario");
            System.out.println("PASS " + scenario + " checks=" + checks);
        } catch (Throwable failure) {
            failure.printStackTrace(System.out); exit = 1;
        }
        System.exit(exit); // The real Activity's process-global executor intentionally remains alive.
    }
}
