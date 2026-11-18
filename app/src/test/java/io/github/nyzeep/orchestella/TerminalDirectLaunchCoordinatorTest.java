package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import org.junit.Test;

/**
 * G-10：终端直启异步 bind 状态机。store 读取挪后台 executor，主线程先渲染解析
 * 占位卡，结果回来在主线程分发终态（成功/失败/重入）。
 *
 * <p>generation 防护语义对齐 TerminalActivity 既有 bindingGeneration/foregroundGeneration
 * 模式：主线程递增代数使在途投递失效（重入 start / cancel 后旧结果不再回调）。
 * 元素类型用 String 代位；生产装配为 {@code TerminalDirectLaunchCoordinator<InstanceLifecycle.Instance>}
 * （idOf = Instance::id，编译期绑定）。
 */
public final class TerminalDirectLaunchCoordinatorTest {

    /** Records background tasks without starting threads; the test drives execution. */
    private static final class QueuedBackground
            implements TerminalDirectLaunchCoordinator.BackgroundRunner {
        final List<Runnable> queued = new ArrayList<>();

        @Override public void run(Runnable task) {
            queued.add(task);
        }
    }

    /** Records UI marshalling without a Handler; the test drives execution. */
    private static final class QueuedUi implements TerminalDirectLaunchCoordinator.UiRunner {
        final List<Runnable> queued = new ArrayList<>();

        @Override public void post(Runnable task) {
            queued.add(task);
        }
    }

    private static final class RecordingCallbacks
            implements TerminalDirectLaunchCoordinator.Callbacks<String> {
        int resolvingCount;
        String singleInstanceId;
        List<String> pickerCandidates;
        int noInstanceCount;
        int storeErrorCount;

        @Override public void onResolving() {
            resolvingCount++;
        }

        @Override public void onSingleInstance(String instanceId) {
            singleInstanceId = instanceId;
        }

        @Override public void onPicker(List<String> candidates) {
            pickerCandidates = candidates;
        }

        @Override public void onNoInstance() {
            noInstanceCount++;
        }

        @Override public void onStoreError() {
            storeErrorCount++;
        }
    }

    private static TerminalDirectLaunchCoordinator<String> coordinator(
            TerminalDirectLaunchCoordinator.StoreLister<String> lister,
            QueuedBackground background, QueuedUi ui, RecordingCallbacks callbacks) {
        return new TerminalDirectLaunchCoordinator<>(
                lister, Function.identity(), background, ui, callbacks);
    }

    @Test
    public void startFiresResolvingThenDeliversSingleInstanceOnMainThread() {
        QueuedBackground background = new QueuedBackground();
        QueuedUi ui = new QueuedUi();
        RecordingCallbacks callbacks = new RecordingCallbacks();
        TerminalDirectLaunchCoordinator<String> coordinator =
                coordinator(() -> Arrays.asList("inst-1"), background, ui, callbacks);

        coordinator.start();
        // Resolving card renders synchronously; the outcome waits for the store read.
        assertEquals(1, callbacks.resolvingCount);
        assertNull(callbacks.singleInstanceId);
        assertTrue(coordinator.isResolutionPending());

        background.queued.get(0).run();
        // Read done, but delivery is marshalled to the UI runner, never the bg thread.
        assertNull(callbacks.singleInstanceId);

        ui.queued.get(0).run();
        assertEquals("inst-1", callbacks.singleInstanceId);
        assertFalse(coordinator.isResolutionPending());
    }

    @Test
    public void severalStoredInstancesAskViaPickerInsteadOfGuessing() {
        QueuedBackground background = new QueuedBackground();
        QueuedUi ui = new QueuedUi();
        RecordingCallbacks callbacks = new RecordingCallbacks();
        TerminalDirectLaunchCoordinator<String> coordinator = coordinator(
                () -> Arrays.asList("inst-1", "inst-2"), background, ui, callbacks);

        coordinator.start();
        background.queued.get(0).run();
        ui.queued.get(0).run();

        assertNull(callbacks.singleInstanceId);
        assertEquals(Arrays.asList("inst-1", "inst-2"), callbacks.pickerCandidates);
        assertEquals(0, callbacks.storeErrorCount);
    }

    @Test
    public void zeroStoredInstancesKeepMissingCardPath() {
        QueuedBackground background = new QueuedBackground();
        QueuedUi ui = new QueuedUi();
        RecordingCallbacks callbacks = new RecordingCallbacks();
        TerminalDirectLaunchCoordinator<String> coordinator =
                coordinator(ArrayList::new, background, ui, callbacks);

        coordinator.start();
        background.queued.get(0).run();
        ui.queued.get(0).run();

        assertEquals(1, callbacks.noInstanceCount);
        assertNull(callbacks.singleInstanceId);
        assertNull(callbacks.pickerCandidates);
    }

    @Test
    public void storeReadFailureKeepsPreG10TryCatchFallThrough() {
        QueuedBackground background = new QueuedBackground();
        QueuedUi ui = new QueuedUi();
        RecordingCallbacks callbacks = new RecordingCallbacks();
        TerminalDirectLaunchCoordinator<String> coordinator = coordinator(
                () -> { throw new java.io.IOException("store unreadable"); },
                background, ui, callbacks);

        coordinator.start();
        background.queued.get(0).run();
        ui.queued.get(0).run();

        assertEquals(1, callbacks.storeErrorCount);
        assertEquals(0, callbacks.noInstanceCount);
        assertNull(callbacks.singleInstanceId);
        assertNull(callbacks.pickerCandidates);
        assertFalse(coordinator.isResolutionPending());
    }

    @Test
    public void cancelDropsInFlightDeliveryViaGenerationGuard() {
        QueuedBackground background = new QueuedBackground();
        QueuedUi ui = new QueuedUi();
        RecordingCallbacks callbacks = new RecordingCallbacks();
        TerminalDirectLaunchCoordinator<String> coordinator =
                coordinator(() -> Arrays.asList("inst-1"), background, ui, callbacks);

        coordinator.start();
        coordinator.cancel(); // explicit instanceId arrived; in-flight read is now stale
        background.queued.get(0).run();
        ui.queued.get(0).run();

        assertEquals(1, callbacks.resolvingCount);
        assertNull(callbacks.singleInstanceId);
        assertNull(callbacks.pickerCandidates);
        assertEquals(0, callbacks.noInstanceCount);
        assertEquals(0, callbacks.storeErrorCount);
        assertFalse(coordinator.isResolutionPending());
    }

    @Test
    public void reentrantStartSupersedesTheFirstRead() {
        QueuedBackground background = new QueuedBackground();
        QueuedUi ui = new QueuedUi();
        RecordingCallbacks callbacks = new RecordingCallbacks();
        // Snapshot read like a real store: each call observes the store at read time.
        List<String> store = new ArrayList<>(Arrays.asList("inst-1"));
        TerminalDirectLaunchCoordinator<String> coordinator =
                coordinator(() -> new ArrayList<>(store), background, ui, callbacks);

        coordinator.start();
        background.queued.get(0).run(); // first read completes, delivery already queued
        store.clear();
        store.add("inst-2");
        coordinator.start(); // re-entrant start bumps the generation
        background.queued.get(1).run();

        assertEquals(2, callbacks.resolvingCount);
        ui.queued.get(0).run(); // stale delivery (ticket 1) must be dropped
        assertNull(callbacks.singleInstanceId);
        ui.queued.get(1).run(); // current delivery (ticket 2) binds the newest read
        assertEquals("inst-2", callbacks.singleInstanceId);
        assertFalse(coordinator.isResolutionPending());
    }
}
