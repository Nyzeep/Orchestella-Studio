package io.github.nyzeep.orchestella.runtime.instance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Contract suite for {@link GatewaySlotArbiter} and its value types.
 *
 * <p>Every scenario pairs the case under test with a reverse control, because the defect this
 * module exists for is precisely a missing distinction: "the port is taken" must never collapse
 * into "our previous gateway is still running".
 *
 * <ul>
 *   <li>S1 — idle port: free even when a lookalike process is in the snapshot;</li>
 *   <li>S2 — bound port plus identity: only then an orphan of this instance;</li>
 *   <li>S3 — bound port, non-empty table, no identity: held by unknown;</li>
 *   <li>S4 — bound port, empty table: held by unknown;</li>
 *   <li>S5 — unreadable probe: evidence unavailable, never free;</li>
 *   <li>S6 — derived port matches the hand-computed values, including a negative hash;</li>
 *   <li>S7 — identity strings are stable and the hex directory name is unsigned;</li>
 *   <li>S8 — every verdict has a stable, path-free sentence;</li>
 *   <li>S9 — {@link ProcessRef} value semantics and substring matching.</li>
 * </ul>
 */
public final class GatewaySlotArbiterContractTest {

    public static void main(String[] args) throws Exception {
        Path root;
        boolean owned;
        if (args != null && args.length > 0 && args[0] != null && !args[0].isBlank()) {
            root = Path.of(args[0]).toAbsolutePath().normalize();
            deleteRecursively(root);
            Files.createDirectories(root);
            owned = false;
        } else {
            root = Files.createTempDirectory("gateway-slot-test-");
            owned = true;
        }
        try {
            s1IdlePortIsFreeEvenWithLookalikeProcess();
            s2BoundPortWithIdentityIsOrphan();
            s3BoundPortWithoutIdentityIsHeldByUnknown();
            s4BoundPortWithEmptyTableIsHeldByUnknown();
            s5UnreadableProbeIsNeverFree();
            s6DerivedPortMatchesHandComputedValues();
            s7IdentityStringsAreStable();
            s8DescribeIsStableAndPathFree();
            s9ProcessRefValueSemantics();
            System.out.println("GATEWAY_SLOT_ARBITER_TEST_OK");
        } finally {
            if (owned) {
                deleteRecursively(root);
            }
        }
    }

    @Test
    public void s1IdlePort() {
        s1IdlePortIsFreeEvenWithLookalikeProcess();
    }

    @Test
    public void s2BoundWithIdentity() {
        s2BoundPortWithIdentityIsOrphan();
    }

    @Test
    public void s3BoundWithoutIdentity() {
        s3BoundPortWithoutIdentityIsHeldByUnknown();
    }

    @Test
    public void s4BoundWithEmptyTable() {
        s4BoundPortWithEmptyTableIsHeldByUnknown();
    }

    @Test
    public void s5UnreadableProbe() {
        s5UnreadableProbeIsNeverFree();
    }

    @Test
    public void s6DerivedPort() {
        s6DerivedPortMatchesHandComputedValues();
    }

    @Test
    public void s7IdentityStrings() {
        s7IdentityStringsAreStable();
    }

    @Test
    public void s8Describe() {
        s8DescribeIsStableAndPathFree();
    }

    @Test
    public void s9ProcessRef() {
        s9ProcessRefValueSemantics();
    }

    /** S1: an idle port is idle, no matter what the snapshot looks like. */
    private static void s1IdlePortIsFreeEvenWithLookalikeProcess() {
        String instanceId = "alpha";
        ProcessRef lookalike = process(
                4242,
                "/data/app/libgvproxy.so",
                "-config",
                "/data/user/0/app/files/runtime/" + GatewaySlotArbiter.configFileName(instanceId));

        GatewaySlotEvidence idle =
                new GatewaySlotEvidence(false, true, List.of(lookalike), true);
        assertEquals("S1: an idle port must be FREE even when a lookalike process exists",
                GatewaySlotVerdict.FREE, GatewaySlotArbiter.verdict(instanceId, idle));

        // Reverse control: the very same snapshot, port taken -> the lookalike now counts.
        GatewaySlotEvidence taken =
                new GatewaySlotEvidence(true, true, List.of(lookalike), true);
        assertEquals("S1: with the port taken the same process is an orphan of this instance",
                GatewaySlotVerdict.ORPHAN_OF_THIS_INSTANCE,
                GatewaySlotArbiter.verdict(instanceId, taken));
    }

    /** S2: a bound port is an orphan only when the identity of this instance is present. */
    private static void s2BoundPortWithIdentityIsOrphan() {
        String instanceId = "orphan-1";
        ProcessRef gateway = process(
                9001,
                "/data/app/libgvproxy.so",
                "-config",
                "/data/user/0/app/files/runtime/" + GatewaySlotArbiter.configFileName(instanceId));

        GatewaySlotEvidence matching =
                new GatewaySlotEvidence(true, true, List.of(gateway), true);
        assertEquals("S2: a bound port plus a matching command line is an orphan of this instance",
                GatewaySlotVerdict.ORPHAN_OF_THIS_INSTANCE,
                GatewaySlotArbiter.verdict(instanceId, matching));

        // Reverse control: same bound port, but the gateway belongs to another instance.
        ProcessRef foreign = process(
                9002,
                "/data/app/libgvproxy.so",
                "-config",
                "/data/user/0/app/files/runtime/" + GatewaySlotArbiter.configFileName("someone-else"));
        GatewaySlotEvidence mismatched =
                new GatewaySlotEvidence(true, true, List.of(gateway, foreign), true);
        assertEquals("S2: a matching gateway still wins when a foreign one is present too",
                GatewaySlotVerdict.ORPHAN_OF_THIS_INSTANCE,
                GatewaySlotArbiter.verdict(instanceId, mismatched));
        assertEquals("S2: without the matching command line the slot is held by unknown",
                GatewaySlotVerdict.HELD_BY_UNKNOWN,
                GatewaySlotArbiter.verdict(instanceId,
                        new GatewaySlotEvidence(true, true, List.of(foreign), true)));
    }

    /**
     * S3: the defect this ticket exists for — a bound port with a non-empty process table in which
     * nothing carries this instance's identity must not be read as an orphan.
     */
    private static void s3BoundPortWithoutIdentityIsHeldByUnknown() {
        String instanceId = "unknown-7";
        String configFileName = GatewaySlotArbiter.configFileName(instanceId);
        List<ProcessRef> nearMisses = List.of(
                process(501, "/data/app/libgvproxy.so", "-config", "/runtime/net-other.yaml"),
                process(502, "gvproxy", "-config", "/runtime/net-unknown-8.yaml"),
                process(503, "gvproxy", "-config", "/runtime/net-" + instanceId + ".yml"),
                process(504, "com.android.systemui"));

        GatewaySlotEvidence evidence =
                new GatewaySlotEvidence(true, false, nearMisses, true);
        assertEquals("S3: a non-empty table without a matching command line is HELD_BY_UNKNOWN",
                GatewaySlotVerdict.HELD_BY_UNKNOWN,
                GatewaySlotArbiter.verdict(instanceId, evidence));

        // Reverse control: adding the exact identity flips the verdict.
        List<ProcessRef> withIdentity = new ArrayList<>(nearMisses);
        withIdentity.add(process(505, "gvproxy", "-config", "/runtime/" + configFileName));
        assertEquals("S3: adding the exact identity string must flip the verdict",
                GatewaySlotVerdict.ORPHAN_OF_THIS_INSTANCE,
                GatewaySlotArbiter.verdict(instanceId,
                        new GatewaySlotEvidence(true, false, withIdentity, true)));
    }

    /** S4: a bound port with nothing in the process table is unknown, not an orphan. */
    private static void s4BoundPortWithEmptyTableIsHeldByUnknown() {
        String instanceId = "empty-3";
        GatewaySlotEvidence bound = new GatewaySlotEvidence(true, false, List.of(), true);
        assertEquals("S4: a bound port with an empty table is HELD_BY_UNKNOWN",
                GatewaySlotVerdict.HELD_BY_UNKNOWN, GatewaySlotArbiter.verdict(instanceId, bound));

        // Reverse control: the same empty table on an idle port is free.
        GatewaySlotEvidence idle = new GatewaySlotEvidence(false, false, List.of(), true);
        assertEquals("S4: an empty table on an idle port is FREE",
                GatewaySlotVerdict.FREE, GatewaySlotArbiter.verdict(instanceId, idle));
    }

    /** S5: a probe that did not run must never degrade to FREE. */
    private static void s5UnreadableProbeIsNeverFree() {
        String instanceId = "unreadable-2";

        GatewaySlotEvidence idleButUnreadable =
                new GatewaySlotEvidence(false, false, List.of(), false);
        assertEquals("S5: an unreadable probe on an unbound port is EVIDENCE_UNAVAILABLE",
                GatewaySlotVerdict.EVIDENCE_UNAVAILABLE,
                GatewaySlotArbiter.verdict(instanceId, idleButUnreadable));
        assertEquals("S5: the unavailable() factory must classify as EVIDENCE_UNAVAILABLE",
                GatewaySlotVerdict.EVIDENCE_UNAVAILABLE,
                GatewaySlotArbiter.verdict(instanceId, GatewaySlotEvidence.unavailable()));

        // Even a matching command line cannot rescue an unreadable probe.
        GatewaySlotEvidence boundButUnreadable = new GatewaySlotEvidence(
                true, true,
                List.of(process(700, "gvproxy", "-config", GatewaySlotArbiter.configFileName(instanceId))),
                false);
        assertEquals("S5: an unreadable probe wins over every other signal",
                GatewaySlotVerdict.EVIDENCE_UNAVAILABLE,
                GatewaySlotArbiter.verdict(instanceId, boundButUnreadable));

        // Reverse control: readable evidence of the same shape is not EVIDENCE_UNAVAILABLE.
        GatewaySlotEvidence readable = GatewaySlotEvidence.free();
        assertNotEquals("S5: a readable probe must not report EVIDENCE_UNAVAILABLE",
                GatewaySlotVerdict.EVIDENCE_UNAVAILABLE,
                GatewaySlotArbiter.verdict(instanceId, readable));
        assertEquals("S5: a readable, unbound probe is FREE",
                GatewaySlotVerdict.FREE, GatewaySlotArbiter.verdict(instanceId, readable));
    }

    /**
     * S6: the derived port matches hand-computed values.
     *
     * <p>{@code "a"} hashes to 97 and {@code "ab"} to 3105; {@code "zzzzzz"} hashes to
     * {@code -685785664}, whose remainder in 800 is -64, so a {@code %} implementation would yield
     * 24036 while {@code floorMod} yields 736 and therefore 24836.
     */
    private static void s6DerivedPortMatchesHandComputedValues() {
        assertEquals("S6: derived port for \"a\"", 24197, GatewaySlotArbiter.derivedPort("a"));
        assertEquals("S6: derived port for \"ab\"", 24805, GatewaySlotArbiter.derivedPort("ab"));
        assertEquals("S6: a negative hash must still land inside the derived range",
                24836, GatewaySlotArbiter.derivedPort("zzzzzz"));
        assertTrue("S6: the negative-hash case must actually have a negative hash",
                "zzzzzz".hashCode() < 0);
        assertNotEquals("S6: floorMod must not behave like % on a negative hash",
                24100 - 64, GatewaySlotArbiter.derivedPort("zzzzzz"));
        assertEquals("S6: the null id falls back to hashCode(0)", 24100,
                GatewaySlotArbiter.derivedPort(null));

        int low = GatewaySlotArbiter.PORT_BASE;
        int high = GatewaySlotArbiter.PORT_BASE + GatewaySlotArbiter.PORT_SLOTS - 1;
        for (String instanceId : List.of("a", "ab", "zzzzzz", "instance-0", "upper-limit-2999")) {
            int port = GatewaySlotArbiter.derivedPort(instanceId);
            assertTrue("S6: port " + port + " must stay within [" + low + ", " + high + "]",
                    port >= low && port <= high);
            assertEquals("S6: the derivation must be repeatable for " + instanceId,
                    port, GatewaySlotArbiter.derivedPort(instanceId));
        }
    }

    /** S7: the two identity strings are stable and the hex name is unsigned. */
    private static void s7IdentityStringsAreStable() {
        String instanceId = "a";
        assertEquals("S7: config file name", "net-a.yaml",
                GatewaySlotArbiter.configFileName(instanceId));
        assertEquals("S7: hex directory name for hashCode 97", "61",
                GatewaySlotArbiter.apiSocketDirectoryName(instanceId));
        assertFalse("S7: an unsigned hex name must not carry a sign",
                GatewaySlotArbiter.apiSocketDirectoryName("zzzzzz").contains("-"));
        assertTrue("S7: an unsigned hex name must be non-empty",
                !GatewaySlotArbiter.apiSocketDirectoryName("zzzzzz").isEmpty());

        for (String id : List.of("a", "ab", "zzzzzz", "instance-0")) {
            assertEquals("S7: config file name must be stable for " + id,
                    GatewaySlotArbiter.configFileName(id), GatewaySlotArbiter.configFileName(id));
            assertEquals("S7: hex name must be stable for " + id,
                    GatewaySlotArbiter.apiSocketDirectoryName(id),
                    GatewaySlotArbiter.apiSocketDirectoryName(id));
            assertEquals("S7: hex name must match Integer.toUnsignedString for " + id,
                    Integer.toUnsignedString(id.hashCode(), 16),
                    GatewaySlotArbiter.apiSocketDirectoryName(id));
        }

        // Reverse control: the identity string is instance specific.
        assertNotEquals("S7: two different instances must not share a config file name",
                GatewaySlotArbiter.configFileName("a"), GatewaySlotArbiter.configFileName("ab"));
    }

    /** S8: every verdict renders as a stable, non-empty sentence without absolute paths. */
    private static void s8DescribeIsStableAndPathFree() {
        String instanceId = "describe-4";
        int port = GatewaySlotArbiter.derivedPort(instanceId);

        List<String> sentences = new ArrayList<>();
        for (GatewaySlotVerdict verdict : GatewaySlotVerdict.values()) {
            String sentence = GatewaySlotArbiter.describe(instanceId, verdict);
            assertEquals("S8: " + verdict + " must describe identically twice",
                    sentence, GatewaySlotArbiter.describe(instanceId, verdict));
            assertTrue("S8: " + verdict + " must be described at all", !sentence.isEmpty());
            assertFalse("S8: " + verdict + " must not leak a path", sentence.contains("/"));
            assertFalse("S8: " + verdict + " must not leak a path", sentence.contains("\\"));
            sentences.add(sentence);
        }

        assertEquals("S8: FREE sentence", "gateway slot is free",
                GatewaySlotArbiter.describe(instanceId, GatewaySlotVerdict.FREE));
        for (GatewaySlotVerdict verdict : List.of(
                GatewaySlotVerdict.ORPHAN_OF_THIS_INSTANCE,
                GatewaySlotVerdict.HELD_BY_UNKNOWN,
                GatewaySlotVerdict.EVIDENCE_UNAVAILABLE)) {
            assertTrue("S8: " + verdict + " must name the derived port " + port,
                    GatewaySlotArbiter.describe(instanceId, verdict).contains(String.valueOf(port)));
        }

        // Reverse control: the four verdicts must stay distinguishable.
        assertEquals("S8: the four sentences must be pairwise distinct",
                (long) GatewaySlotVerdict.values().length,
                sentences.stream().distinct().count());
    }

    /** S9: {@link ProcessRef} is an immutable value with substring matching. */
    private static void s9ProcessRefValueSemantics() {
        List<String> mutable = new ArrayList<>(List.of("gvproxy", "-config", "/runtime/net-a.yaml"));
        ProcessRef reference = new ProcessRef(1234, mutable);
        mutable.add("mutated");

        assertEquals("S9: the snapshot must not see later mutations of the source list",
                List.of("gvproxy", "-config", "/runtime/net-a.yaml"), reference.cmdline());
        assertEquals("S9: pid accessor", 1234, reference.pid());

        assertTrue("S9: a contained substring must match",
                reference.cmdlineContains("net-a.yaml"));
        assertTrue("S9: a whole argument must match", reference.cmdlineContains("gvproxy"));
        assertFalse("S9: an absent substring must not match",
                reference.cmdlineContains("net-b.yaml"));
        assertFalse("S9: a null needle must not match", reference.cmdlineContains(null));
        assertFalse("S9: an empty needle must not match", reference.cmdlineContains(""));

        ProcessRef same = new ProcessRef(1234, List.of("gvproxy", "-config", "/runtime/net-a.yaml"));
        ProcessRef otherPid = new ProcessRef(4321, List.of("gvproxy", "-config", "/runtime/net-a.yaml"));
        ProcessRef otherArgs = new ProcessRef(1234, List.of("gvproxy"));
        assertEquals("S9: equal pid and argv must be equal", reference, same);
        assertEquals("S9: equal values must share a hash", reference.hashCode(), same.hashCode());
        assertNotEquals("S9: a different pid must differ", reference, otherPid);
        assertNotEquals("S9: a different argv must differ", reference, otherArgs);
        assertFalse("S9: nothing else must be equal", reference.equals("ProcessRef"));
        assertFalse("S9: a null must not be equal", reference.equals(null));

        String rendered = reference.toString();
        assertTrue("S9: toString must report the pid", rendered.contains("1234"));
        assertFalse("S9: toString must not print the argument vector",
                rendered.contains("net-a.yaml"));
    }

    private static ProcessRef process(int pid, String... argv) {
        return new ProcessRef(pid, List.of(argv));
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {}
            });
        }
    }
}
