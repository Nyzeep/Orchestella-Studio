package io.github.nyzeep.orchestella

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

/**
 * Lane A (2026-11-13, the UML routable round): the wiring-owner side of the UML
 * compile-worker face. [AcceleratorLaneAssembly.umlFace] builds the face; this
 * file carries everything that wiring owner needs around it:
 *
 * - [UmlConsoleContract] — the built-in-initramfs console contract
 *   (`research/uml-fastboot` README §4): success is only `U1_T2_READY` /
 *   `U1_READY`, failure is only `UM_INIT_ERROR code=… step=…`; every other
 *   line is a plain console line. Pure and JVM-tested.
 * - [UmlConsoleDrain] — the read-until-EOF console loop. The fast shape writes
 *   its console to the process's fd 1 (`con0=null,fd:1`); an unread stdout pipe
 *   would eventually stall the kernel on a full buffer, so the drain is not
 *   optional — it is how the face's output is consumed at all.
 * - [ConsoleDrainingUmlSpawner] — the [UmlFaceLauncher.ProcessSpawner] the
 *   wiring owner hands to the assembly's spawner-injecting `umlFace` overload.
 *   It replicates the production `processBuilder()` seam line for line (merged
 *   stdout/stderr, explicit environment, kernel working dir) and attaches the
 *   drain thread; the spawner is exactly the "tests and the device window own
 *   exec" injection seam the launcher's class doc reserves. EOF (or a broken
 *   console stream) means the kernel's stdio is gone, so the sink's EOF handler
 *   probes the face and reaps it honestly — the built-in-initramfs probe shape
 *   self-terminates (`um.hold_ms=1000`, the locked `fastProfileCommand` output;
 *   `um.shell=1` is a test interface, not the profile's kernel contract), and
 *   the reap turns that expected exit into width 0 with the keep-alive released.
 * - [UmlFaceStatusRegistry] — the minimal read-only status holder the dashboard
 *   mirrors `ExecutionSessionRegistry`'s registry shape with: one bound face at
 *   a time, register/unregister by instance id, and a width read that is honest
 *   through every lifecycle state (a reaped or stopped face reads 0).
 * - [UmlFaceRideAlong] — the [InstanceLaunchCoordinator.UmlFaceLane] port
 *   implementation: arms the face through the assembly slot after a successful
 *   dispatch, refuses a second face while one is bound (never two kernels),
 *   and disarms — `stop()` plus unregister — exactly where the accelerator
 *   coordinator closes. Every outcome is a fixed log line on the
 *   `OrchestellaUml` family tag (bound at the Android edge), never a faked
 *   state: GATE_DISABLED, MISSING_ASSET and SPAWN_FAILED all answer their
 *   fixed codes with zero side effects and width 0. Since the compile-bridge
 *   wiring (Gate 1, ACR 2026-11-13 方案 A) the arm line states the channel
 *   truthfully — `channel=wired` when the D-1 UML-TCP lane channel rode into
 *   the assembly slot, `channel=unwired` when it did not.
 * - [UmlCompileBridgeWiring] — the compile-bridge wiring owner: builds the
 *   face channel through the D-1 seam ([UmlChannels.overTcpLane] over the
 *   bridge's `UmlTcpChannelFactory`), binds the fabric submitter listener to
 *   the armed orchestrator, and closes both exactly at disarm. Inert when the
 *   build flag is off — the historical `channel=unwired` arm path byte for
 *   byte.
 * - [UmlNetSupervisorSpawner] — the Gate 2 net-supervisor wrapper (ACR
 *   2026-11-13 §8, umnet 候选 a) around the draining spawner: flag on and
 *   both helper libs present rewrites the kernel command into the
 *   `libuml_net.so --passt <lib> -- <kernel> …` supervisor invocation with
 *   `PASST_ANDROID_NO_ISOLATION=1` in the environment; flag off is a
 *   byte-for-byte passthrough; flag on without the libs throws the named
 *   [IOException] the launcher's `SPAWN_FAILED` code catches — never a
 *   silent launch of an unroutable kernel under a wired arm line.
 * - [UmlRootfsExtraArgs] — the Gate 2 E2E wired-arm decision (launcher v2
 *   wiring, 2026-11-13): bridge on + the provisioned rootfs tree ready → the
 *   external-root triple `umroot=<extracted dir>`, `umrootfstype=hostfs`,
 *   `um.net=1` rides the launch params' existing extraArgs seam (the launcher
 *   appends them verbatim after the fast-profile base line); every other
 *   shape answers zero extra args and the face keeps the built-in-initramfs
 *   probe shape byte for byte.
 *
 * The compile channel wiring follows the fourth build flag
 * (`orchestella.uml.compileBridge` → `BuildConfig.UML_COMPILE_BRIDGE`, default
 * off; never derived from the fast-profile flag — launch shape != routability).
 * Flag off keeps the face honest capacity on the fabric ledger with its
 * unroutability stated everywhere; flag on wires the D-1 lane and states
 * `channel=wired` — whether the guest's distccd actually answers stays the
 * runtime's own honesty (a dead lane fails the job and rides the
 * orchestrator's eviction contract, never a faked route).
 */

/** One classified console line of the built-in-initramfs contract. */
enum class UmlConsoleLineKind { READY, INIT_ERROR, PLAIN }

data class UmlConsoleLine(val kind: UmlConsoleLineKind, val raw: String)

/**
 * The §4 judgment contract (`research/uml-fastboot/README.md` §4): success is
 * only `U1_T2_READY` / `U1_READY`; failure is only `UM_INIT_ERROR code=… step=…`
 * (the init prints exactly one such line, then powers off). Everything else —
 * kernel chatter, the `U1_INFO` banner — is a plain line: logged, never judged.
 */
object UmlConsoleContract {
    private val READY_MARKERS = listOf("U1_T2_READY", "U1_READY")

    fun classify(raw: String): UmlConsoleLine {
        val trimmed = raw.trim()
        val kind = when {
            READY_MARKERS.any { trimmed.contains(it) } -> UmlConsoleLineKind.READY
            trimmed.startsWith("UM_INIT_ERROR") &&
                trimmed.contains("code=") &&
                trimmed.contains("step=") -> UmlConsoleLineKind.INIT_ERROR
            else -> UmlConsoleLineKind.PLAIN
        }
        return UmlConsoleLine(kind, raw)
    }
}

/** The console consumption seam: one classified line each, then one EOF. */
interface UmlConsoleSink {
    fun onLine(line: UmlConsoleLine)

    /** Console over — EOF or a broken stream; the kernel's stdio is gone. */
    fun onConsoleEof()
}

/** The read-until-EOF console loop; EOF terminates naturally. */
object UmlConsoleDrain {
    fun drain(reader: BufferedReader, sink: UmlConsoleSink) {
        while (true) {
            val line = reader.readLine() ?: return
            sink.onLine(UmlConsoleContract.classify(line))
        }
    }
}

/**
 * The wiring owner's spawner: the production `processBuilder()` seam plus the
 * console drain thread. The drain is a daemon (it never keeps the process
 * alive past the kernel) and names itself for diagnostics.
 */
class ConsoleDrainingUmlSpawner(
    private val sink: UmlConsoleSink,
) : UmlFaceLauncher.ProcessSpawner {

    override fun spawn(
        command: List<String>,
        environment: Map<String, String>,
        workingDir: File,
    ): UmlFaceLauncher.UmlProcess {
        // Replicates UmlFaceLauncher.ProcessSpawner.processBuilder() exactly:
        // merged stdout/stderr (the console IS fd 1), the explicit kernel
        // environment, and the kernel working dir.
        val builder = ProcessBuilder(command)
        builder.directory(workingDir)
        builder.redirectErrorStream(true)
        builder.environment().putAll(environment)
        val process = builder.start()
        Thread {
            try {
                UmlConsoleDrain.drain(
                    BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)),
                    sink,
                )
            } catch (_: IOException) {
                // A broken console stream is the same fact as EOF: the
                // kernel's stdio is gone; the EOF handler reaps honestly.
            } finally {
                sink.onConsoleEof()
            }
        }.apply {
            name = "OrchestellaUmlConsole"
            isDaemon = true
        }.start()
        return object : UmlFaceLauncher.UmlProcess {
            override fun isAlive(): Boolean = process.isAlive()

            override fun destroy() {
                process.destroy()
            }

            override fun destroyForcibly() {
                process.destroyForcibly()
            }

            override fun waitForExit(timeoutMs: Long): Boolean =
                process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }
}

/**
 * Gate 2 (ACR 2026-11-13 §8, fd 路径＝候选 a, the umnet native supervisor):
 * the net-supervisor wrapper around a [UmlFaceLauncher.ProcessSpawner] — in
 * production around [ConsoleDrainingUmlSpawner], so the console drain keeps
 * its owner and this layer stays a pure command-shaper. The Gate 2 preflight
 * (round 2026-11-13, device-b, untrusted_app + SELinux Enforcing) proved the
 * app domain execs the static bionic pair and umnet's socketpair/passt-fork
 * chain runs green; this class only wires that proven invocation into the
 * face's existing spawn seam.
 *
 * <p>Behavior, exactly three shapes:
 * <ul>
 *   <li>flag off — byte-for-byte passthrough of the original command list,
 *   environment and working dir (the historical behavior; the wrapper is
 *   invisible);</li>
 *   <li>flag on and both helper libraries present and executable in
 *   [nativeLibraryDir]'s dir — the kernel command is rewritten into
 *   `[libuml_net.so, --passt, <libuml_passt.so>, --, <kernel>, <original
 *   args…>]` (the kernel is the original command's first element — both the
 *   runner and the fast-profile command lead with the kernel path, and the
 *   launcher is this seam's only caller) with `PASST_ANDROID_NO_ISOLATION=1`
 *   added to the environment (the caller's map is never mutated). umnet then
 *   builds the vec0 fd pair, forks passt and execs the kernel; it appends the
 *   `vec0:transport=fd,…` kernel argument and assembles passt's `-F`
 *   interaction internally — this layer never spells them. The kernel's
 *   console still rides fd 1 through umnet's stdio passthrough, so the
 *   delegate's drain semantics are untouched; umnet's own stderr diagnostics
 *   mix in as plain console lines the contract already tolerates.</li>
 *   <li>flag on but a helper library missing or not executable — [spawn]
 *   throws the named [IOException] (`uml-net-helper missing: <lib>`), which
 *   the launcher's `SPAWN_FAILED` fixed code catches with the message riding
 *   in its detail. The flag claims routability, so a build without its
 *   helpers must fail the spawn — never launch an unroutable kernel while
 *   the arm line reads `channel=wired`.</li>
 * </ul>
 *
 * @param delegate the spawner this layer wraps (console ownership stays with
 *   it — the drain thread, EOF handling and process adaptation are its job)
 * @param enabled the fourth build flag (`BuildConfig.UML_COMPILE_BRIDGE`) as
 *   the Android edge consumed it; off = pure passthrough
 * @param nativeLibraryDir supplies the app's native library dir at spawn
 *   time — the Android edge defers `applicationInfo` reads past attach; the
 *   passt path and the supervisor path both derive from it, never from the
 *   command or the working dir
 */
class UmlNetSupervisorSpawner(
    private val delegate: UmlFaceLauncher.ProcessSpawner,
    private val enabled: Boolean,
    private val nativeLibraryDir: () -> File,
) : UmlFaceLauncher.ProcessSpawner {

    override fun spawn(
        command: List<String>,
        environment: Map<String, String>,
        workingDir: File,
    ): UmlFaceLauncher.UmlProcess {
        if (!enabled) {
            return delegate.spawn(command, environment, workingDir)
        }
        val libDir = nativeLibraryDir()
        val umnet = File(libDir, NET_HELPER_LIB)
        if (!umnet.isFile || !umnet.canExecute()) {
            throw IOException("uml-net-helper missing: $NET_HELPER_LIB")
        }
        val passt = File(libDir, PASST_HELPER_LIB)
        if (!passt.isFile || !passt.canExecute()) {
            throw IOException("uml-net-helper missing: $PASST_HELPER_LIB")
        }
        val rewritten = ArrayList<String>(command.size + SUPERVISOR_PREFIX_LENGTH)
        rewritten.add(umnet.absolutePath)
        rewritten.add("--passt")
        rewritten.add(passt.absolutePath)
        rewritten.add(ARGUMENT_SEPARATOR)
        rewritten.addAll(command)
        val supervisorEnvironment = LinkedHashMap(environment)
        supervisorEnvironment[NO_ISOLATION_ENV] = NO_ISOLATION_VALUE
        return delegate.spawn(rewritten, supervisorEnvironment, workingDir)
    }

    companion object {
        /** The umnet supervisor, staged as a jniLib executable (Gate 2 staging). */
        const val NET_HELPER_LIB = "libuml_net.so"

        /** The passt child, static bionic, staged next to [NET_HELPER_LIB]. */
        const val PASST_HELPER_LIB = "libuml_passt.so"

        /**
         * passt's Android isolation escape: the preflight proved the app
         * domain rejects passt's default namespace setup, and the fork's
         * vendored Android fixes read this variable to skip it.
         */
        const val NO_ISOLATION_ENV = "PASST_ANDROID_NO_ISOLATION"

        /** The value the fork's Android fixes expect. */
        const val NO_ISOLATION_VALUE = "1"

        /** The umnet argument separator: everything after it is the kernel's. */
        const val ARGUMENT_SEPARATOR = "--"

        /** supervisor + --passt + passt + -- prepended to the kernel command. */
        const val SUPERVISOR_PREFIX_LENGTH = 4
    }
}

/**
 * Gate 2 E2E (2026-11-13, launcher v2 wiring): the wired face's external-root
 * extra-args decision — pure and JVM-testable, so the Android edge only feeds
 * it the build flag and the pure-read readiness probe and never makes this
 * decision inline.
 *
 * <p>Exactly three honest states:
 * <ul>
 *   <li>compile bridge off, or the rootfs-download gate closed, or the tree
 *   not ready — [NONE]: the face keeps the built-in-initramfs probe shape
 *   byte for byte. An unwired or unprovisioned build never fakes a routable
 *   guest, and the probe's self-termination stays load-bearing for the
 *   unwired arm's EOF reap contract;</li>
 *   <li>bridge on and the provisioned tree ready — the external-root triple:
 *   `umroot=<the extracted tree's absolute path>` (the HOSTFS mount option
 *   the fast init switch_roots into, i.e. the tree whose `/um-command.sh` is
 *   the provisioner-owned v2 launcher), `umrootfstype=hostfs`, and
 *   [UM_NET_ARG] — the v2 launcher's guest-network boot knob (passt DHCP,
 *   apk add distcc, distccd daemonized on 3632, resident).</li>
 * </ul>
 *
 * <p>The caller invokes this off the main thread: [UmlRootfsProvisioner.readiness]
 * is a pure-read probe but hashes the pinned artifact (~4 MB), so it belongs
 * on the launch chain's IO executor — the arm path already runs there.
 */
object UmlRootfsExtraArgs {
    /** The zero-extra-args answer: the historical probe shape, untouched. */
    val NONE: List<String> = emptyList()

    /** The v2 launcher's guest-network boot knob (the um.net branch guard). */
    const val UM_NET_ARG = "um.net=1"

    /** The fast init's external-root fstype for the provisioned tree. */
    const val UMROOT_FSTYPE_ARG = "umrootfstype=hostfs"

    /**
     * The decision for one arm. The readiness report must come from the
     * caller's own off-main probe ([UmlRootfsProvisioner.readiness]); a
     * ready report's extracted dir is the HOSTFS umroot target by the
     * provisioner's own naming contract — nothing is restated here.
     */
    fun forArm(
        compileBridgeEnabled: Boolean,
        readiness: UmlRootfsProvisioner.ReadinessReport,
    ): List<String> {
        if (!compileBridgeEnabled || !readiness.enabled || !readiness.ready) {
            return NONE
        }
        val extractDir = readiness.extractedDir ?: return NONE
        return listOf(
            "umroot=" + extractDir.toAbsolutePath(),
            UMROOT_FSTYPE_ARG,
            UM_NET_ARG,
        )
    }
}

/**
 * The dashboard's read-only face status: mirrors the `ExecutionSessionRegistry`
 * registry shape (static register/unregister keyed by instance id) with the
 * minimal single-face state the ride-along actually needs. Reads are honest in
 * every state — the width comes from the face itself, so a stopped, reaped or
 * gate-refused face reads 0 and the UI says so.
 */
object UmlFaceStatusRegistry {
    class BoundFace(val instanceId: String, val face: UmlFaceLauncher)

    @Volatile
    private var bound: BoundFace? = null

    /** False when a face is already bound — at most one face may ride at a time. */
    @Synchronized
    @JvmStatic
    fun register(instanceId: String, face: UmlFaceLauncher): Boolean {
        if (bound != null) {
            return false
        }
        bound = BoundFace(instanceId, face)
        return true
    }

    /** Removes only the face bound for this instance; a newer binding stays. */
    @Synchronized
    @JvmStatic
    fun unregister(instanceId: String): Boolean {
        val current = bound ?: return false
        if (current.instanceId != instanceId) {
            return false
        }
        bound = null
        return true
    }

    @Synchronized
    @JvmStatic
    fun current(): BoundFace? = bound

    /** The bound face's dispatchable width; 0 when no face is bound or it is down. */
    @JvmStatic
    fun runningWidth(): Int = current()?.face?.width() ?: 0
}

/**
 * The production console sink: one `OrchestellaUml` log line per console line,
 * and on EOF the honest reap — `probe()` turns the expected self-termination of
 * the §4 probe shape (exit 0 after `um.hold_ms`) into width 0 with the
 * keep-alive released, so nothing freeze-protects a kernel that is gone.
 *
 * <p>Device-verified detail (2026-11-13 device-b, the routable round): the
 * kernel's console closes at powerdown a beat <b>before</b> the process is
 * reaped, so the EOF probe can still observe HEALTHY — the measured window
 * was ~5 ms (`uml console closed while the kernel is alive` right before the
 * `[1.22] reboot: Power down` exit). One delayed re-probe closes that race
 * without a poller thread: if the kernel died in the grace window the reap
 * still runs (width 0, keep-alive released); if it is genuinely alive the
 * stop stays the wiring owner's call as before.
 *
 * @param delayedReprobe runs the given re-probe after [REPROBE_GRACE_MS]; the
 *   production default is a single daemon thread, tests pass a direct runner
 */
class UmlFaceConsoleSink(
    private val faceSource: () -> UmlFaceLauncher?,
    private val log: (String) -> Unit,
    private val delayedReprobe: (Runnable) -> Unit = { task ->
        Thread {
            try {
                Thread.sleep(REPROBE_GRACE_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            task.run()
        }.apply {
            name = "OrchestellaUmlReprobe"
            isDaemon = true
        }.start()
    },
) : UmlConsoleSink {

    override fun onLine(line: UmlConsoleLine) {
        log("uml console ${line.kind.name.lowercase()}: ${line.raw}")
    }

    override fun onConsoleEof() {
        val face = faceSource() ?: return
        when (face.probe()) {
            UmlFaceLauncher.Health.PROCESS_DEAD ->
                log("uml face reaped after console EOF; width reads 0")
            UmlFaceLauncher.Health.HEALTHY -> {
                log("uml console closed while the kernel is alive; re-probing after teardown grace")
                delayedReprobe {
                    val probed = faceSource() ?: return@delayedReprobe
                    when (probed.probe()) {
                        UmlFaceLauncher.Health.PROCESS_DEAD ->
                            log("uml face reaped after teardown grace; width reads 0")
                        else ->
                            log("kernel still alive at re-probe; stop stays the wiring owner's call")
                    }
                }
            }
            UmlFaceLauncher.Health.NOT_RUNNING -> {
                // Already stopped honestly; nothing to reap.
            }
        }
    }

    companion object {
        /** Covers the §4 probe shape's `hold_ms` + teardown window with margin. */
        const val REPROBE_GRACE_MS = 2_000L
    }
}

/**
 * The launch-chain ride-along: arms the face through the assembly slot when a
 * dispatch succeeded, disarms it where the accelerator coordinator closes.
 * Every outcome is a fixed line on the injected log seam (the Android edge
 * binds the `OrchestellaUml` family tag); a refused start never fakes a state —
 * width stays 0 and no keep-alive is touched (the launcher's own contract).
 *
 * @param backend builds the face; the third argument is the compile channel
 *   the ride-along provisioned from [compileBridge] (`null` = the historical
 *   unwired arm — the backend must call the assembly's five-argument slot)
 * @param compileBridge the compile-bridge wiring ([UmlCompileBridgeWiring]);
 *   `null` or flag-off keeps today's byte-identical `channel=unwired` path
 * @param log the fixed-line log seam
 */
class UmlFaceRideAlong(
    private val backend: (instanceId: String, gateEnabled: Boolean, channel: UmlFaceLauncher.Channel?) -> UmlFaceLauncher?,
    private val compileBridge: UmlCompileBridgeWiring? = null,
    private val log: (String) -> Unit,
) : InstanceLaunchCoordinator.UmlFaceLane {

    override fun arm(instanceId: String, gateEnabled: Boolean): UmlFaceLauncher? {
        if (UmlFaceStatusRegistry.current() != null) {
            log("uml-face arm refused: another face is already bound")
            return null
        }
        // Provision the D-1 lane channel first (flag off answers null); a stale
        // session from an earlier arm is closed by the wiring itself — never two.
        val channel = compileBridge?.faceChannel()
        val face = backend(instanceId, gateEnabled, channel)
        if (face == null) {
            log("uml-face arm declined: the wiring backend answered null")
            return null
        }
        val result = face.start()
        if (!result.started()) {
            log("uml-face start refused code=${result.code} detail=${result.detail ?: "-"}")
            return null
        }
        if (!UmlFaceStatusRegistry.register(instanceId, face)) {
            // A concurrent arm won the slot mid-start: never run two kernels.
            log("uml-face arm refused: registry slot taken mid-start; stopping this face")
            face.stop()
            return null
        }
        // The arm line states the channel truthfully: wired when the D-1 lane
        // channel rode into the assembly slot, unwired when it did not. The
        // width stays the face's own honest read either way.
        log("uml-face started width=${face.width()} channel=${if (channel != null) "wired" else "unwired"}")
        return face
    }

    override fun disarm(instanceId: String) {
        val bound = UmlFaceStatusRegistry.current() ?: return
        if (bound.instanceId != instanceId) {
            return
        }
        bound.face.stop()
        UmlFaceStatusRegistry.unregister(instanceId)
        // The compile bridge (channel + submitter listener) dies with the face
        // it serves — the listener never outlives the armed orchestrator.
        compileBridge?.disarm()
        log("uml-face stopped")
    }

    override fun bindFabric(orchestrator: FabricOrchestrator?) {
        compileBridge?.bindFabric(orchestrator)
    }
}

/**
 * Gate 1 (ACR 2026-11-13 方案 A, fabric-route doc D-1): the compile-bridge
 * wiring owner. One instance per process (the Android edge holds it next to
 * the ride-along); everything is inert while the build flag is off, so a
 * flag-off build runs today's `channel=unwired` path byte for byte.
 *
 * <p>What it owns, and nothing more:
 * <ul>
 *   <li>the face's D-1 lane channel — built per arm through
 *   [UmlChannels.overTcpLane] over the bridge's `UmlTcpChannelFactory`
 *   (loopback dial to the UML guest's distccd), opened once, adapted with
 *   [UmlChannels.asFaceChannel], closed at disarm (an arm that fails after
 *   provisioning leaves an inert open channel; the next arm or disarm closes
 *   it — the channel holds no transport resources between jobs);</li>
 *   <li>the fabric submitter listener — started when the launch's orchestrator
 *   is bound ([bindFabric]), stopped at disarm; it dispatches through the
 *   <em>current</em> orchestrator, so an arm/disarm cycle swaps the fabric
 *   without rebuilding the listener.</li>
 * </ul>
 *
 * <p>Provisional Gate 1 endpoints are code constants, logged at arm, never UI
 * copy: the guest dial target is passt's loopback forward of the guest's
 * distccd ([DEFAULT_GUEST_DISTCC_PORT]); the submitter listener is the port a
 * QEMU-guest distcc client reaches through the user-network gateway
 * ([DEFAULT_SUBMITTER_LISTENER_PORT]). Gate 2's device window confirms the
 * real forwarding table; a dial or bind refusal is an honest fixed log line
 * and the runtime's own failure contract (face eviction, connection
 * CHANNEL_FAILED), never a faked route.
 *
 * @param enabled the fourth build flag (`BuildConfig.UML_COMPILE_BRIDGE`) as
 *   the Android edge consumed it; off = every method is a no-op
 * @param log the fixed-line log seam (the `OrchestellaUml` family tag)
 * @param guestDistccPort the UML guest dial port (tests pass ephemerals; the
 *   shipped default is [DEFAULT_GUEST_DISTCC_PORT])
 * @param listenerPort the submitter listener port (tests pass ephemerals; the
 *   shipped default is [DEFAULT_SUBMITTER_LISTENER_PORT])
 */
class UmlCompileBridgeWiring(
    private val enabled: Boolean,
    private val log: (String) -> Unit,
    private val guestDistccPort: Int = DEFAULT_GUEST_DISTCC_PORT,
    private val listenerPort: Int = DEFAULT_SUBMITTER_LISTENER_PORT,
) {
    private class Session(val channel: UmlChannel)

    private val lock = Any()
    private var session: Session? = null

    @Volatile
    private var orchestrator: FabricOrchestrator? = null

    private var submitter: FabricCompileSubmitter? = null

    /**
     * The face's compile channel for one arm, or `null` when the flag is off
     * (the historical unwired arm) or the lane channel refused to open (an
     * honest log line; the face stays unwired). Closes any stale session
     * first — at most one armed face may hold a channel.
     */
    fun faceChannel(): UmlFaceLauncher.Channel? {
        if (!enabled) {
            return null
        }
        synchronized(lock) {
            session?.let { stale ->
                runCatching { stale.channel.close() }
                session = null
            }
            val laneFactory = io.github.nyzeep.orchestella.runtime.fastlane.distcc.UmlTcpChannelFactory.loopback(
                LOOPBACK_V4,
                guestDistccPort,
            )
            val channel = UmlChannels.overTcpLane(laneFactory)
            return try {
                channel.open()
                session = Session(channel)
                log(
                    "uml-face channel wired: uml-tcp lane " +
                        "127.0.0.1:$guestDistccPort (D-1, vsock=pVM / TCP=UML)",
                )
                UmlChannels.asFaceChannel(channel)
            } catch (openFailure: UmlChannel.UmlChannelException) {
                log("uml-face channel unwired: lane open refused ${openFailure.detail()}")
                null
            }
        }
    }

    /**
     * Publishes the launch's fabric orchestrator and ensures the submitter
     * listener is running. `null` (or a flag-off build) stops nothing here —
     * the listener stops at [disarm], the one place that mirrors the face's
     * own lifecycle. A listener bind refusal is an honest log line; the face
     * stays honest capacity and the routing simply has no entry point yet.
     */
    fun bindFabric(orchestrator: FabricOrchestrator?) {
        if (!enabled || orchestrator == null) {
            return
        }
        synchronized(lock) {
            this.orchestrator = orchestrator
            if (submitter?.isRunning() == true) {
                return
            }
            val started = FabricCompileSubmitter(
                { this.orchestrator },
                listenerPort,
                SUBMITTER_MAX_CONNECTIONS,
                SUBMITTER_IDLE_TIMEOUT_MS,
            ) { stats ->
                log(
                    "fabric submitter connection outcome=${stats.outcome()} " +
                        "bytes=${stats.bytesFromClient()}/${stats.bytesToClient()} " +
                        "failureCode=${stats.failureCode()}",
                )
            }
            try {
                started.start(LOOPBACK_V4)
                submitter = started
                log("fabric submitter listening 127.0.0.1:${started.port()}")
            } catch (bindFailure: IOException) {
                log("fabric submitter start refused: ${bindFailure.message}")
            }
        }
    }

    /** Closes the lane channel and stops the submitter; safe when nothing ran. */
    fun disarm() {
        if (!enabled) {
            return
        }
        synchronized(lock) {
            session?.let { armed ->
                runCatching { armed.channel.close() }
                session = null
            }
            submitter?.let { running ->
                runCatching { running.close() }
                submitter = null
            }
            orchestrator = null
        }
    }

    companion object {
        /**
         * The loopback these loopback-only hops dial and bind: IPv4 127.0.0.1,
         * explicitly. Device-measured 2026-11-14 (Lane A E2E, device-b):
         * [java.net.InetAddress.getLoopbackAddress] resolved to the IPv6
         * loopback on the device, which left the submitter listener bound
         * ::1-only (tcp6 table) and the lane dialing ::1:3632 — both refused
         * by the IPv4-only passt forward and the QEMU user-network gateway
         * (10.0.2.2 → host 127.0.0.1), Connection refused on every hop. Every
         * loopback constant in this wiring (the log lines, passt's bind, the
         * gateway's forward target) names 127.0.0.1; the address must be the
         * same IPv4 one.
         */
        val LOOPBACK_V4: java.net.InetAddress = java.net.InetAddress.getByName("127.0.0.1")

        /** Provisional Gate 1 dial target: the guest distccd via passt's loopback forward. */
        const val DEFAULT_GUEST_DISTCC_PORT = 3632

        /** Provisional Gate 1 listener: the QEMU guest's distcc clients enter here. */
        const val DEFAULT_SUBMITTER_LISTENER_PORT = 3633

        /** The bridge's connection cap for the submitter listener. */
        const val SUBMITTER_MAX_CONNECTIONS = 4

        /**
         * No idle watchdog on the submitter listener: a real distcc job can be
         * silent for minutes mid-compile; a stalled connection is the bridge's
         * own terminal classification (client disconnect), not a timeout.
         */
        const val SUBMITTER_IDLE_TIMEOUT_MS = 0
    }
}
