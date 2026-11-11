package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * P2-2: the API 35 seccomp profile sanity, as pure-JVM contract tests over the
 * checked-in generated profiles ({@code tools/research/uml-seccomp/profile/
 * generated/}). Check-only: nothing is regenerated — the byte-exactness of the
 * checked-in files against the generator pipeline stays with {@code
 * gen_profile.py --check} / {@code profile_sanity.py --check}; this test locks
 * the invariants those files must satisfy so a silent policy widening cannot
 * reach a device matrix (R1 §5.1 point 2):
 *
 * <ul>
 *   <li>the android15 (API 35) lane locks {@code android15-release} and is
 *   pipeline-generated: 216 allowed / 0 unmapped on arm64 (R1 §2.3);</li>
 *   <li>monotonicity: {@code android16 − android15 == {landlock_add_rule,
 *   landlock_create_ruleset, landlock_restrict_self, mseal}} with an empty
 *   reverse difference, per architecture — the C3 evidence that lets API-36
 *   device boot evidence move monotonically to the API-35 domain (never the
 *   other way); the android14→15 step is exactly {@code +clone3,
 *   +epoll_pwait2};</li>
 *   <li>the device-b triage facts (R1 §5.1 point 5, §5.2): host {@code mount}/
 *   {@code umount2} are NOT in the 35 face (guest mounts ride UML internals;
 *   a host mount would TRAP), {@code epoll_pwait2} IS allowed by number (so a
 *   5.10 host answers ENOSYS, never SIGSYS), the 36-only {@code landlock*}/
 *   {@code mseal} names are absent, and the {@code setrlimit}/{@code
 *   prlimit64} host names are present.</li>
 * </ul>
 *
 * <p>The profiles are parsed from the generator's frozen serialization
 * (byte-exact under {@code gen_profile.py --check}), so a small structural
 * reader is enough — no JSON library joins the unit-test classpath. The repo
 * root is located by walking up from the working directory to the directory
 * holding {@code tools/research/uml-seccomp/profile/refs.json}.
 */
public final class UmlSeccompProfileSanityTest {

    private static final String[] ARCHES = {"arm64", "x86_64"};
    private static final Set<String> ANDROID15_TO_16_ADDED = new HashSet<>(Arrays.asList(
            "landlock_add_rule", "landlock_create_ruleset",
            "landlock_restrict_self", "mseal"));
    private static final Set<String> ANDROID14_TO_15_ADDED = new HashSet<>(Arrays.asList(
            "clone3", "epoll_pwait2"));

    private static Path repoRoot() throws IOException {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth < 8 && dir != null; depth++, dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve(
                    "tools/research/uml-seccomp/profile/refs.json"))) {
                return dir;
            }
        }
        throw new IOException("repo root with tools/research/uml-seccomp not found from "
                + Paths.get(System.getProperty("user.dir")).toAbsolutePath()
                + " — run the unit tests from a checkout of this repository");
    }

    private static String profileText(String ref, String arch) throws IOException {
        Path path = repoRoot().resolve(Paths.get(
                "tools", "research", "uml-seccomp", "profile", "generated",
                "aosp-app-" + ref + "-" + arch + ".json"));
        assertTrue("checked-in profile missing: " + path, Files.isRegularFile(path));
        // readString() is Java 11+; the unit-test source target is 8.
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /** The object body between two top-level keys of the frozen serialization. */
    private static String objectSlice(String text, String key, String nextKey) {
        // Search the end from start: inner objects may carry same-named keys
        // (counts.unmapped precedes the top-level unmapped array).
        int start = text.indexOf("\"" + key + "\": {");
        int end = start < 0 ? -1 : text.indexOf("\"" + nextKey + "\":", start + 1);
        assertTrue("frozen profile layout changed: " + key + "/" + nextKey,
                start >= 0 && end > start);
        return text.substring(start, end);
    }

    private static Set<String> syscallNames(String text) {
        Set<String> names = new HashSet<>();
        Matcher matcher = Pattern.compile("\"([A-Za-z0-9_]+)\": (\\d+)").matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static Map<String, Integer> numberFields(String text) {
        Map<String, Integer> values = new LinkedHashMap<>();
        Matcher matcher = Pattern.compile("\"([A-Za-z0-9_]+)\": (\\d+)").matcher(text);
        while (matcher.find()) {
            values.putIfAbsent(matcher.group(1), Integer.parseInt(matcher.group(2)));
        }
        return values;
    }

    private static String scalar(String text, String key) {
        Matcher matcher = Pattern.compile("\"" + key + "\": \"?([A-Za-z0-9_.-]+)\"?")
                .matcher(text);
        assertTrue("scalar " + key + " not found", matcher.find());
        return matcher.group(1);
    }

    private static final class Profile {
        final String name;
        final String arch;
        final int api;
        final String defaultAction;
        final Map<String, Integer> counts;
        final Set<String> syscalls;

        Profile(String ref, String arch) throws IOException {
            String text = profileText(ref, arch);
            this.name = scalar(text, "name");
            this.arch = scalar(text, "arch");
            this.api = Integer.parseInt(scalar(text, "android_api"));
            this.defaultAction = scalar(text, "default_action");
            this.counts = numberFields(objectSlice(text, "counts", "syscalls"));
            this.syscalls = syscallNames(objectSlice(text, "syscalls", "unmapped"));
        }
    }

    private static final class Step {
        final Set<String> added;
        final Set<String> removed;

        Step(Profile older, Profile newer) {
            Set<String> a = new HashSet<>(newer.syscalls);
            Set<String> b = new HashSet<>(older.syscalls);
            a.removeAll(b);
            b.removeAll(new HashSet<>(newer.syscalls));
            this.added = a;
            this.removed = b;
        }
    }

    @Test
    public void android15ProfileIsThePipelineGeneratedApi35Face() throws IOException {
        for (String arch : ARCHES) {
            Profile p15 = new Profile("android15", arch);
            assertEquals("aosp-app-android15-" + arch, p15.name);
            assertEquals(arch, p15.arch);
            assertEquals("android15 locks API 35 (R1 §2.5)", 35, p15.api);
            assertEquals("the default action stays SECCOMP_RET_TRAP", "trap", p15.defaultAction);
            assertEquals("every allowed name maps (0 unmapped)", 0,
                    p15.counts.get("unmapped").intValue());
            assertEquals("mapped == allowed", p15.counts.get("allowed_names"),
                    p15.counts.get("mapped"));
            Profile p16 = new Profile("android16", arch);
            assertEquals(36, p16.api);
            assertEquals("trap", p16.defaultAction);
        }
    }

    @Test
    public void android15Arm64CarriesTheR1Counts() throws IOException {
        Profile p15 = new Profile("android15", "arm64");
        assertEquals("R1 §2.3: android15-arm64 allowed = 216", 216,
                p15.counts.get("allowed_names").intValue());
        assertEquals(216, p15.counts.get("mapped").intValue());
    }

    @Test
    public void android16MinusAndroid15IsExactlyLandlockAndMseal() throws IOException {
        for (String arch : ARCHES) {
            Step step = new Step(new Profile("android15", arch), new Profile("android16", arch));
            assertEquals("android15 → android16 added set (R1 §2.3, " + arch + ")",
                    ANDROID15_TO_16_ADDED, step.added);
            assertTrue("the app seccomp face only grows: nothing is removed",
                    step.removed.isEmpty());
        }
    }

    @Test
    public void android14ToAndroid15AddsExactlyClone3AndEpollPwait2() throws IOException {
        for (String arch : ARCHES) {
            Step step = new Step(new Profile("android14", arch), new Profile("android15", arch));
            assertEquals("android14 → android15 added set (R1 §2.3, " + arch + ")",
                    ANDROID14_TO_15_ADDED, step.added);
            assertTrue(step.removed.isEmpty());
        }
    }

    @Test
    public void android15Arm64FaceCarriesTheDeviceBTriageFacts() throws IOException {
        Profile p15 = new Profile("android15", "arm64");
        // R1 §5.2 mount row: the 35 face has no host mount/umount2 — the U3
        // mount case is by design (guest mounts ride UML internals; a host
        // mount would be a filter TRAP, never a kernel ENOSYS).
        assertFalse(p15.syscalls.contains("mount"));
        assertFalse(p15.syscalls.contains("umount2"));
        // R1 §5.1 point 5 / §2.4: epoll_pwait2 is allowed BY NUMBER, so on the
        // 5.10 device host it answers ENOSYS — an errno fact, never SIGSYS.
        assertTrue(p15.syscalls.contains("epoll_pwait2"));
        assertTrue(p15.syscalls.contains("clone3"));
        // The landlock trio and mseal are the 36 face (Android 16+); on the 35
        // domain they are filter-absent AND kernel-absent on 5.10.
        assertFalse(p15.syscalls.contains("landlock_add_rule"));
        assertFalse(p15.syscalls.contains("landlock_create_ruleset"));
        assertFalse(p15.syscalls.contains("landlock_restrict_self"));
        assertFalse(p15.syscalls.contains("mseal"));
        // The rlimit names the U3 setrlimit case touches: setrlimit (guest
        // probe) and prlimit64 (the arm64 host face) are both present, so the
        // case's host-side read is a pass, not a filter kill.
        assertTrue(p15.syscalls.contains("setrlimit"));
        assertTrue(p15.syscalls.contains("prlimit64"));
    }
}
