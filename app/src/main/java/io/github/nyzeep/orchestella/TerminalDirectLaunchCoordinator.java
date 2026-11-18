package io.github.nyzeep.orchestella;

import java.util.List;
import java.util.function.Function;

/**
 * G-10：终端直启路径的异步实例解析状态机。直启（intent 不带 instanceId）原先在
 * {@code onCreate} 主线程执行 {@code InstanceLifecycle.FileStore(...).list()} 磁盘
 * IO；本类把 store 读取挪到后台 executor，主线程先经 {@link Callbacks#onResolving}
 * 渲染解析占位卡，结果回来后在主线程分发终态。
 *
 * <p>分发语义与 pre-G-10 同步 try-catch 逐项对齐：单实例自动绑定（不猜）、多实例
 * 弹选择器（session-18 W-C.3）、零实例/读失败回落「未提供实例 ID」占位卡族（不猜）。
 *
 * <p>generation 防护对齐 TerminalActivity 既有 bindingGeneration/foregroundGeneration
 * 模式：主线程递增代数使在途投递失效——重入 {@link #start} 或 {@link #cancel} 后，
 * 旧读取的迟到投递在主线程被丢弃，不再回调。
 *
 * @param <E> 候选实例元素类型；生产装配为 {@code InstanceLifecycle.Instance}，
 *            测试用代位类型（idOf 抽取绑定保持纯 JVM 可测）。
 */
public final class TerminalDirectLaunchCoordinator<E> {

    /** Resolution outcome delivered on the main thread after the store read returns. */
    public enum OutcomeKind {
        SINGLE_INSTANCE, PICKER, NO_INSTANCE, STORE_ERROR
    }

    /** Main-thread callback surface; the activity owns every view/state mutation. */
    public interface Callbacks<T> {
        /** Fired synchronously by {@link #start()} before the background read begins. */
        void onResolving();

        /** Exactly one stored instance: unambiguous, bind it（pre-G-10 语义）。 */
        void onSingleInstance(String instanceId);

        /** Several stored instances: ask instead of guessing（session-18 W-C.3）。 */
        void onPicker(List<T> candidates);

        /** Zero stored instances: keep the missing-instance card（不猜实例）。 */
        void onNoInstance();

        /** Store unreadable: same fall-through as the pre-G-10 try-catch。 */
        void onStoreError();
    }

    /** Background executor seam; production binds the activity's dedicated executor. */
    public interface BackgroundRunner {
        void run(Runnable task);
    }

    /** Main-thread marshalling seam; production binds the activity UI Handler. */
    public interface UiRunner {
        void post(Runnable task);
    }

    /** Store read seam; mirrors {@code InstanceLifecycle.FileStore.list()} throws. */
    public interface StoreLister<T> {
        List<T> list() throws Exception;
    }

    private final StoreLister<E> storeLister;
    private final Function<E, String> idOf;
    private final BackgroundRunner backgroundRunner;
    private final UiRunner uiRunner;
    private final Callbacks<E> callbacks;
    /** Main-thread generation: a re-entrant start or cancel invalidates in-flight delivery. */
    private int generation;
    private boolean resolutionPending;

    public TerminalDirectLaunchCoordinator(StoreLister<E> storeLister, Function<E, String> idOf,
            BackgroundRunner backgroundRunner, UiRunner uiRunner, Callbacks<E> callbacks) {
        this.storeLister = storeLister;
        this.idOf = idOf;
        this.backgroundRunner = backgroundRunner;
        this.uiRunner = uiRunner;
        this.callbacks = callbacks;
    }

    /**
     * Renders the resolving card synchronously, then reads the instance store on the
     * background runner and marshals the outcome back through the UI runner.
     */
    public void start() {
        generation++; // a re-entrant start supersedes any in-flight read
        resolutionPending = true;
        callbacks.onResolving();
        final int ticket = generation;
        backgroundRunner.run(() -> {
            List<E> stored = null;
            boolean storeError = false;
            try {
                stored = storeLister.list();
            } catch (Exception readFailure) {
                storeError = true; // pre-G-10 catch: fall through to the missing-instance card
            }
            final List<E> resolved = stored;
            final boolean failed = storeError;
            uiRunner.post(() -> {
                if (ticket != generation) return; // superseded/cancelled while in flight
                resolutionPending = false;
                deliver(resolved, failed);
            });
        });
    }

    /** Invalidates an in-flight read; its late delivery is dropped on the main thread. */
    public void cancel() {
        generation++;
        resolutionPending = false;
    }

    /** True between {@link #start()} and the main-thread delivery; drives reconcile gating. */
    public boolean isResolutionPending() {
        return resolutionPending;
    }

    private void deliver(List<E> stored, boolean storeError) {
        if (storeError) {
            callbacks.onStoreError();
        } else if (stored == null || stored.isEmpty()) {
            callbacks.onNoInstance();
        } else if (stored.size() == 1) {
            callbacks.onSingleInstance(idOf.apply(stored.get(0)));
        } else {
            callbacks.onPicker(stored);
        }
    }
}
