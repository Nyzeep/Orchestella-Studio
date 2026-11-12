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
 *   fixed codes with zero side effects and width 0.
 *
 * The compile channel stays unwired in this wiring (fabric-route doc D-1 is
 * ACR-bound): the face is honest capacity on the fabric ledger, and its
 * unroutability is stated — in the log lines, the dashboard copy and the
 * worker's fixed `CHANNEL_UNWIRED` refusal — never papered over.
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
 */
class UmlFaceRideAlong(
    private val backend: (instanceId: String, gateEnabled: Boolean) -> UmlFaceLauncher?,
    private val log: (String) -> Unit,
) : InstanceLaunchCoordinator.UmlFaceLane {

    override fun arm(instanceId: String, gateEnabled: Boolean): UmlFaceLauncher? {
        if (UmlFaceStatusRegistry.current() != null) {
            log("uml-face arm refused: another face is already bound")
            return null
        }
        val face = backend(instanceId, gateEnabled)
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
        // This wiring leaves the compile channel unwired (fabric-route D-1):
        // the width is honest capacity, the unroutability is stated everywhere.
        log("uml-face started width=${face.width()} channel=unwired")
        return face
    }

    override fun disarm(instanceId: String) {
        val bound = UmlFaceStatusRegistry.current() ?: return
        if (bound.instanceId != instanceId) {
            return
        }
        bound.face.stop()
        UmlFaceStatusRegistry.unregister(instanceId)
        log("uml-face stopped")
    }
}
