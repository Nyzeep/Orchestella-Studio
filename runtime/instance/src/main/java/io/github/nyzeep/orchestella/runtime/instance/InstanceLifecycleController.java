package io.github.nyzeep.orchestella.runtime.instance;

import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.CrashDiagnostic;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.DistroReference;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.Instance;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.InvalidTransitionException;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.ProvisioningEvidence;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.SingleRunningInstanceException;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.State;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.Store;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Normal-path coordinator implementation; the public {@link InstanceLifecycle.Controller}
 * type stays the API surface and extends this class. Callers must keep it in one live host
 * process.
 *
 * <p>Extracted verbatim from {@link InstanceLifecycle} so the Instance state model and the
 * coordination boundary live in separate files; every method body is behavior-preserving.
 */
class InstanceLifecycleController {
    private final Store store;
    private final Clock clock;

    InstanceLifecycleController(Store store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized void delete(String id) throws IOException {
        synchronized (store.stateMonitor()) {
            deleteLocked(id);
        }
    }

    /**
     * Purges the root volume, the sidecar files and the metadata entry, so it must hold the
     * directory-wide state monitor: a custom store relies on the caller for exclusion, and
     * another Controller's create or export must not interleave with the physical purge.
     */
    private void deleteLocked(String id) throws IOException {
        store.delete(id);
    }

    public synchronized Instance create(DistroReference distro, Path rootVolume) throws IOException {
        return create(distro, rootVolume, VmHardwareConfig.defaultConfig(), InstanceUserConfig.defaultRoot());
    }

    public synchronized Instance create(
            DistroReference distro, Path rootVolume, VmHardwareConfig hardwareConfig) throws IOException {
        return create(distro, rootVolume, hardwareConfig, InstanceUserConfig.defaultRoot());
    }

    public synchronized Instance create(
            DistroReference distro,
            Path rootVolume,
            VmHardwareConfig hardwareConfig,
            InstanceUserConfig userConfig) throws IOException {
        Objects.requireNonNull(distro, "distro");
        Objects.requireNonNull(rootVolume, "rootVolume");
        Objects.requireNonNull(hardwareConfig, "hardwareConfig");
        InstanceUserConfig effectiveConfig = userConfig != null ? userConfig : InstanceUserConfig.defaultRoot();
        // Writes a fresh metadata entry, so it must hold the directory-wide state monitor:
        // custom stores rely on the caller for exclusion, and the save must not interleave
        // with another Controller's write on the same state directory.
        synchronized (store.stateMonitor()) {
            long now = clock.millis();
            Instance instance =
                    new Instance(
                            UUID.randomUUID().toString(),
                            distro,
                            rootVolume,
                            InstanceLifecycle.newBridgeToken(),
                            effectiveConfig.password(),
                            FullLinuxContract.RuntimeClass.FULL_LINUX,
                            State.CREATED,
                            now,
                            now,
                            null,
                            hardwareConfig,
                            effectiveConfig.hostname(),
                            effectiveConfig.username(),
                            effectiveConfig.hasSudo(),
                            null);
            store.save(instance);
            return instance;
        }
    }

    /**
     * Clones an existing instance into a new independent instance, duplicating its configuration
     * and physical root-volume disk image.
     *
     * @param sourceId the id of the source instance to clone
     * @param targetRootVolume the path for the new instance's root volume
     * @return the new cloned Instance in CREATED state
     * @throws IOException on I/O error copying disk
     * @throws IllegalStateException if source instance is RUNNING or PROVISIONING
     */
    public synchronized Instance cloneInstance(String sourceId, Path targetRootVolume) throws IOException {
        synchronized (store.stateMonitor()) {
            return cloneInstanceLocked(sourceId, targetRootVolume);
        }
    }

    /** Reads the source volume, so it must hold the directory-wide state monitor. */
    private Instance cloneInstanceLocked(String sourceId, Path targetRootVolume) throws IOException {
        Objects.requireNonNull(targetRootVolume, "targetRootVolume");
        Instance source = store.load(sourceId);
        if (source.state() != State.STOPPED && source.state() != State.CREATED) {
            throw new IllegalStateException(
                    "cannot clone instance in state: " + source.state() + "; instance must be STOPPED or CREATED");
        }
        if (targetRootVolume.getParent() != null) {
            Files.createDirectories(targetRootVolume.getParent());
        }
        if (source.rootVolume() != null && Files.isRegularFile(source.rootVolume())) {
            Files.copy(source.rootVolume(), targetRootVolume, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
        } else if (!Files.exists(targetRootVolume)) {
            Files.createFile(targetRootVolume);
        }
        long now = clock.millis();
        Instance cloned =
                new Instance(
                        UUID.randomUUID().toString(),
                        source.distro(),
                        targetRootVolume,
                        InstanceLifecycle.newBridgeToken(),
                        source.guestPassword(),
                        source.runtimeClass(),
                        State.CREATED,
                        now,
                        now,
                        null,
                        source.hardwareConfig(),
                        source.hostname(),
                        source.defaultUser(),
                        source.hasSudo(),
                        source.provisioningEvidence());
        store.save(cloned);
        return cloned;
    }

    /**
     * Convenience method to clone an instance, automatically placing the new root volume
     * beside the original with the new instance ID.
     */
    public synchronized Instance cloneInstance(String sourceId) throws IOException {
        synchronized (store.stateMonitor()) {
            return cloneInstanceLocked(sourceId);
        }
    }

    /** Reads the source volume, so it must hold the directory-wide state monitor. */
    private Instance cloneInstanceLocked(String sourceId) throws IOException {
        Instance source = store.load(sourceId);
        String newId = UUID.randomUUID().toString();
        Path sourceVolume = source.rootVolume();
        Path targetVolume;
        if (sourceVolume != null && sourceVolume.getParent() != null) {
            String fileName = sourceVolume.getFileName().toString();
            String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : ".raw";
            targetVolume = sourceVolume.getParent().resolve(newId + ext);
        } else {
            targetVolume = Path.of(newId + ".raw");
        }
        if (source.state() != State.STOPPED && source.state() != State.CREATED) {
            throw new IllegalStateException(
                    "cannot clone instance in state: " + source.state() + "; instance must be STOPPED or CREATED");
        }
        if (targetVolume.getParent() != null) {
            Files.createDirectories(targetVolume.getParent());
        }
        if (sourceVolume != null && Files.isRegularFile(sourceVolume)) {
            Files.copy(sourceVolume, targetVolume, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
        } else if (!Files.exists(targetVolume)) {
            Files.createFile(targetVolume);
        }
        long now = clock.millis();
        Instance cloned =
                new Instance(
                        newId,
                        source.distro(),
                        targetVolume,
                        InstanceLifecycle.newBridgeToken(),
                        source.guestPassword(),
                        source.runtimeClass(),
                        State.CREATED,
                        now,
                        now,
                        null,
                        source.hardwareConfig(),
                        source.hostname(),
                        source.defaultUser(),
                        source.hasSudo(),
                        source.provisioningEvidence());
        store.save(cloned);
        return cloned;
    }

    public synchronized Instance importFromArchive(
            InputStream archiveInputStream,
            Path targetRootVolume) throws IOException {
        return InstanceStorageManager.importFromArchive(archiveInputStream, targetRootVolume, store, clock);
    }

    public synchronized Instance importFromArchive(
            InputStream archiveInputStream,
            Path targetRootVolume,
            String targetInstanceId) throws IOException {
        return InstanceStorageManager.importFromArchive(archiveInputStream, targetRootVolume, targetInstanceId, store, clock);
    }

    public synchronized Instance updateHardwareConfig(String id, VmHardwareConfig newConfig) throws IOException {
        synchronized (store.stateMonitor()) {
            return updateHardwareConfigLocked(id, newConfig);
        }
    }

    private Instance updateHardwareConfigLocked(String id, VmHardwareConfig newConfig) throws IOException {
        Objects.requireNonNull(newConfig, "newConfig");
        Instance current = store.load(id);
        if (current.state() != State.STOPPED && current.state() != State.CREATED) {
            throw new IllegalStateException("cannot update hardware config while in state: " + current.state());
        }
        Instance updated = current.withHardwareConfig(newConfig, clock);
        store.save(updated);
        return updated;
    }

    public synchronized Instance updateGuestPassword(String id, String newPassword) throws IOException {
        synchronized (store.stateMonitor()) {
            return updateGuestPasswordLocked(id, newPassword);
        }
    }

    private Instance updateGuestPasswordLocked(String id, String newPassword) throws IOException {
        Objects.requireNonNull(newPassword, "newPassword");
        String trimmed = newPassword.trim();
        if (trimmed.length() < 8) {
            throw new IllegalArgumentException("guest password must be at least 8 characters");
        }
        if (trimmed.contains(" ") || trimmed.contains("\t") || trimmed.contains("\n") || trimmed.contains("\r")) {
            throw new IllegalArgumentException("guest password must not contain whitespace");
        }
        Instance current = store.load(id);
        Instance updated = current.withGuestPassword(trimmed, clock);
        store.save(updated);
        return updated;
    }

    public synchronized Instance beginProvisioning(String id) throws IOException {
        return transition(id, State.PROVISIONING);
    }

    public synchronized Instance completeProvisioning(String id) throws IOException {
        return transition(id, State.STOPPED);
    }

    /**
     * Persists observed guest Provisioning evidence without changing the lifecycle state.
     *
     * <p>Recording is idempotent metadata replacement: transitions, crash diagnostics, and
     * credentials are preserved. Callers must only pass evidence backed by a real guest marker.
     */
    public synchronized Instance recordProvisioning(String id, ProvisioningEvidence evidence)
            throws IOException {
        synchronized (store.stateMonitor()) {
            Objects.requireNonNull(evidence, "evidence");
            Instance current = store.load(id);
            Instance updated = current.withProvisioningEvidence(evidence, clock);
            store.save(updated);
            return updated;
        }
    }

    public synchronized Instance start(String id) throws IOException {
        synchronized (store.stateMonitor()) {
            return startLocked(id);
        }
    }

    private Instance startLocked(String id) throws IOException {
        Instance current = store.load(id);
        if (current.state() != State.STOPPED) {
            throw new InvalidTransitionException(
                    "cannot start instance in state " + current.state() + "; expected STOPPED");
        }
        for (Instance instance : store.list()) {
            if (!instance.id().equals(id) && instance.state() == State.RUNNING) {
                throw new SingleRunningInstanceException("another Instance is already RUNNING");
            }
        }
        return transition(id, State.RUNNING);
    }

    /**
     * Suspends a currently RUNNING instance, freezing CPU scheduling.
     */
    public synchronized Instance suspend(String id) throws IOException {
        synchronized (store.stateMonitor()) {
            return suspendLocked(id);
        }
    }

    private Instance suspendLocked(String id) throws IOException {
        Instance current = store.load(id);
        if (current.state() != State.RUNNING) {
            throw new InvalidTransitionException(
                    "cannot suspend instance in state " + current.state() + "; expected RUNNING");
        }
        return transition(id, State.SUSPENDED);
    }

    /**
     * Resumes a SUSPENDED instance, unfreezing CPU scheduling and returning to RUNNING.
     */
    public synchronized Instance resume(String id) throws IOException {
        synchronized (store.stateMonitor()) {
            return resumeLocked(id);
        }
    }

    private Instance resumeLocked(String id) throws IOException {
        Instance current = store.load(id);
        if (current.state() != State.SUSPENDED) {
            throw new InvalidTransitionException(
                    "cannot resume instance in state " + current.state() + "; expected SUSPENDED");
        }
        for (Instance instance : store.list()) {
            if (!instance.id().equals(id) && instance.state() == State.RUNNING) {
                throw new SingleRunningInstanceException("another Instance is already RUNNING");
            }
        }
        return transition(id, State.RUNNING);
    }

    /**
     * Force stops an instance, transitioning RUNNING or SUSPENDED states directly to STOPPED.
     */
    public synchronized Instance forceStop(String id) throws IOException {
        synchronized (store.stateMonitor()) {
            return forceStopLocked(id);
        }
    }

    private Instance forceStopLocked(String id) throws IOException {
        Instance current = store.load(id);
        if (current.state() == State.STOPPED) {
            return current;
        }
        return transition(id, State.STOPPED);
    }

    /** Records an observed normal guest-initiated stop; it is not a host power command. */
    public synchronized Instance recordNormalGuestStop(String id) throws IOException {
        synchronized (store.stateMonitor()) {
            return transition(id, State.STOPPED);
        }
    }

    /** Records an intentional graceful stop initiated by the host or guest. */
    public synchronized Instance recordGracefulStop(String id) throws IOException {
        synchronized (store.stateMonitor()) {
            return transition(id, State.STOPPED);
        }
    }

    public synchronized Instance recordCrash(String id, int exitCode, Path logPath) throws IOException {
        return recordCrash(id, exitCode, logPath, null);
    }

    public synchronized Instance recordCrash(String id, int exitCode, Path logPath, String message)
            throws IOException {
        return recordCrash(id, new CrashDiagnostic(clock.millis(), exitCode, logPath, message));
    }

    public synchronized Instance recordCrash(String id, CrashDiagnostic diagnostic) throws IOException {
        synchronized (store.stateMonitor()) {
            return recordCrashLocked(id, diagnostic);
        }
    }

    private Instance recordCrashLocked(String id, CrashDiagnostic diagnostic) throws IOException {
        Objects.requireNonNull(diagnostic, "diagnostic");
        Instance current = store.load(id);
        Instance transitioned = current.transitioned(State.CRASHED, clock, diagnostic);
        store.save(transitioned);
        return transitioned;
    }

    public synchronized Instance recordAbnormalTermination(String id, int exitCode, Path logPath)
            throws IOException {
        return recordCrash(id, exitCode, logPath);
    }

    public synchronized Instance recordAbnormalTermination(String id, CrashDiagnostic diagnostic)
            throws IOException {
        return recordCrash(id, diagnostic);
    }

    /**
     * Reconciles all stale RUNNING or SUSPENDED instances to INTERRUPTED (the default cold-restorable state).
     * Returns the list of reconciled instances.
     */
    public synchronized List<Instance> reconcileAfterHostDeath() throws IOException {
        return reconcileAfterHostDeath(State.INTERRUPTED);
    }

    /**
     * Reconciles all stale RUNNING or SUSPENDED instances to the specified target state (INTERRUPTED or STOPPED).
     */
    public synchronized List<Instance> reconcileAfterHostDeath(State targetState) throws IOException {
        synchronized (store.stateMonitor()) {
            return reconcileAfterHostDeathLocked(targetState);
        }
    }

    private List<Instance> reconcileAfterHostDeathLocked(State targetState) throws IOException {
        if (targetState != State.INTERRUPTED && targetState != State.STOPPED) {
            throw new IllegalArgumentException(
                    "targetState must be INTERRUPTED or STOPPED, got: " + targetState);
        }
        List<Instance> reconciled = new ArrayList<>();
        for (Instance instance : store.list()) {
            if (instance.state() == State.RUNNING || instance.state() == State.SUSPENDED) {
                Instance updated = instance.transitioned(targetState, clock);
                store.save(updated);
                reconciled.add(updated);
            }
        }
        return Collections.unmodifiableList(reconciled);
    }

    /**
     * Reconciles a single instance by id if it is in stale RUNNING or SUSPENDED state, setting it to INTERRUPTED.
     */
    public synchronized Instance reconcileAfterHostDeath(String id) throws IOException {
        return reconcileAfterHostDeath(id, State.INTERRUPTED);
    }

    /**
     * Reconciles a single instance by id to the specified target state (INTERRUPTED or STOPPED).
     */
    public synchronized Instance reconcileAfterHostDeath(String id, State targetState) throws IOException {
        synchronized (store.stateMonitor()) {
            return reconcileAfterHostDeathLocked(id, targetState);
        }
    }

    private Instance reconcileAfterHostDeathLocked(String id, State targetState) throws IOException {
        if (targetState != State.INTERRUPTED && targetState != State.STOPPED) {
            throw new IllegalArgumentException(
                    "targetState must be INTERRUPTED or STOPPED, got: " + targetState);
        }
        Instance instance = store.load(id);
        if (instance.state() == State.RUNNING || instance.state() == State.SUSPENDED) {
            Instance updated = instance.transitioned(targetState, clock);
            store.save(updated);
            return updated;
        }
        return instance;
    }

    /**
     * Restores an instance from an interrupted, crashed, or stopped state and prepares it for execution.
     * Enforces the single-running-instance constraint and clears prior crash diagnostics.
     */
    public synchronized Instance coldRestore(String id) throws IOException {
        synchronized (store.stateMonitor()) {
            return coldRestoreLocked(id);
        }
    }

    private Instance coldRestoreLocked(String id) throws IOException {
        for (Instance instance : store.list()) {
            if (!instance.id().equals(id) && instance.state() == State.RUNNING) {
                throw new SingleRunningInstanceException("another Instance is already RUNNING");
            }
        }
        Instance current = store.load(id);
        if (current.state() != State.INTERRUPTED
                && current.state() != State.CRASHED
                && current.state() != State.STOPPED) {
            throw new InvalidTransitionException(
                    "cannot cold-restore instance in state "
                            + current.state()
                            + "; expected INTERRUPTED, CRASHED, or STOPPED");
        }
        Instance transitioned = current.transitioned(State.RUNNING, clock, null);
        store.save(transitioned);
        return transitioned;
    }

    /**
     * Resets an interrupted or crashed instance back to STOPPED without starting it.
     */
    public synchronized Instance resetToStopped(String id) throws IOException {
        synchronized (store.stateMonitor()) {
            return resetToStoppedLocked(id);
        }
    }

    private Instance resetToStoppedLocked(String id) throws IOException {
        Instance current = store.load(id);
        if (current.state() != State.INTERRUPTED && current.state() != State.CRASHED) {
            throw new InvalidTransitionException(
                    "cannot reset instance in state " + current.state() + " to STOPPED");
        }
        Instance transitioned = current.transitioned(State.STOPPED, clock, null);
        store.save(transitioned);
        return transitioned;
    }

    /**
     * Exports structured diagnostic details and copies referenced log data (if available)
     * to the given destination path.
     */
    public synchronized void exportDiagnostic(String id, Path destination) throws IOException {
        synchronized (store.stateMonitor()) {
            exportDiagnosticLocked(id, destination);
        }
    }

    /** Reads the instance's crash log, so it must hold the directory-wide state monitor. */
    private void exportDiagnosticLocked(String id, Path destination) throws IOException {
        Objects.requireNonNull(destination, "destination");
        Instance instance = store.load(id);
        CrashDiagnostic diagnostic = instance.crashDiagnostic();
        if (diagnostic == null) {
            throw new IllegalStateException("instance " + id + " has no crash diagnostic to export");
        }

        if (destination.getParent() != null) {
            Files.createDirectories(destination.getParent());
        }

        StringBuilder report = new StringBuilder();
        report.append("Orchestella Crash Diagnostic Report\n");
        report.append("===================================\n");
        report.append("Instance ID:       ").append(instance.id()).append("\n");
        report.append("Distro:            ").append(instance.distro().id())
                .append(" ").append(instance.distro().version()).append("\n");
        report.append("Crash Timestamp:   ").append(diagnostic.timestampMillis()).append("\n");
        report.append("Exit Code:         ").append(diagnostic.exitCode()).append("\n");
        if (diagnostic.message() != null) {
            report.append("Message:           ").append(diagnostic.message()).append("\n");
        }
        if (diagnostic.logPath() != null) {
            report.append("Log Reference:     ").append(diagnostic.logPath()).append("\n");
            if (Files.isRegularFile(diagnostic.logPath())) {
                report.append("\n--- Log Contents ---\n");
                report.append(Files.readString(diagnostic.logPath()));
            }
        }

        Files.writeString(destination, report.toString());
    }

    /**
     * Resizes the root volume of an instance to {@code targetSizeBytes}.
     * Preflights available host storage space using {@code spaceChecker}.
     */
    public synchronized void resizeRootVolume(
            String id,
            long targetSizeBytes,
            StorageContract.StorageSpaceChecker spaceChecker)
            throws IOException {
        synchronized (store.stateMonitor()) {
            resizeRootVolumeLocked(id, targetSizeBytes, spaceChecker);
        }
    }

    /**
     * Rewrites the root volume, so it must hold the directory-wide state monitor: otherwise
     * another Controller could transition the instance to RUNNING between the state check and
     * the size change, and this method's stale snapshot could overwrite that transition.
     */
    private void resizeRootVolumeLocked(
            String id,
            long targetSizeBytes,
            StorageContract.StorageSpaceChecker spaceChecker)
            throws IOException {
        Instance instance = store.load(id);
        InstanceStorageManager.resizeRootVolume(instance, targetSizeBytes, spaceChecker);
        Instance updated = instance.withUpdatedAt(clock);
        store.save(updated);
    }

    /**
     * Exports an instance (root volume, metadata JSON, and checksums) to a directory.
     */
    public synchronized StorageContract.InstanceExportMetadata exportToDirectory(
            String id,
            Path destinationDirectory,
            StorageContract.StorageSpaceChecker spaceChecker)
            throws IOException {
        synchronized (store.stateMonitor()) {
            return exportToDirectoryLocked(id, destinationDirectory, spaceChecker);
        }
    }

    /** Copies the root volume, so it must hold the directory-wide state monitor. */
    private StorageContract.InstanceExportMetadata exportToDirectoryLocked(
            String id,
            Path destinationDirectory,
            StorageContract.StorageSpaceChecker spaceChecker)
            throws IOException {
        Instance instance = store.load(id);
        return InstanceStorageManager.exportToDirectory(instance, destinationDirectory, spaceChecker, clock);
    }

    /**
     * Exports an instance as an archive package (ZIP) to an output stream.
     */
    public synchronized StorageContract.InstanceExportMetadata exportToArchive(
            String id, OutputStream destinationStream) throws IOException {
        synchronized (store.stateMonitor()) {
            return exportToArchiveLocked(id, destinationStream);
        }
    }

    /** Reads the root volume, so it must hold the directory-wide state monitor. */
    private StorageContract.InstanceExportMetadata exportToArchiveLocked(
            String id, OutputStream destinationStream) throws IOException {
        Instance instance = store.load(id);
        return InstanceStorageManager.exportToArchive(instance, destinationStream, clock);
    }

    private Instance transition(String id, State next) throws IOException {
        synchronized (store.stateMonitor()) {
            Instance transitioned = store.load(id).transitioned(next, clock);
            store.save(transitioned);
            return transitioned;
        }
    }
}
