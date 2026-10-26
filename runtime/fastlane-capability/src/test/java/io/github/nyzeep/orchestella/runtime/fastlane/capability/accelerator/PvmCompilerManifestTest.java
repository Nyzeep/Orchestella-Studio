package io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

/** PVM-09: only a complete, well-formed payload manifest proves the compiler. */
public final class PvmCompilerManifestTest {
    private static final String SHA = "a".repeat(64);
    private static final String OTHER_SHA = "b".repeat(64);

    private static final String SCHEMA_MEMBER = "\"schema\":1";
    private static final String VERSION_MEMBER = "\"compiler_version\":\"22.1.8\"";
    private static final String TRIPLE_MEMBER =
            "\"target_triple\":\"aarch64-unknown-linux-android\"";
    private static final String TOPOLOGY_MEMBER = "\"vcpu_topology\":\"one_cpu\"";
    private static final String MEMORY_MEMBER = "\"memory_mib\":2048";
    private static final String BINARY_MEMBER =
            "\"payload_binary\":\"libpvm_compiler_payload.so\"";
    // Deliberately out of order so the ascending-name contract is observable.
    private static final String LIBS_MEMBER =
            "\"libs\":[" + lib("libclang-cpp.so", "65200000", OTHER_SHA)
                    + "," + lib("libLLVM.so", "64700000", SHA) + "]";

    private static final List<String> ALL_MEMBERS = List.of(
            SCHEMA_MEMBER,
            VERSION_MEMBER,
            TRIPLE_MEMBER,
            TOPOLOGY_MEMBER,
            MEMORY_MEMBER,
            BINARY_MEMBER,
            LIBS_MEMBER);

    @Test
    public void parsesEverySchemaField() {
        Optional<PvmCompilerManifest> parsed = PvmCompilerManifest.parse(valid());

        assertTrue(parsed.isPresent());
        PvmCompilerManifest manifest = parsed.get();
        assertEquals("22.1.8", manifest.compilerVersion());
        assertEquals("aarch64-unknown-linux-android", manifest.targetTriple());
        assertEquals("one_cpu", manifest.vcpuTopology());
        assertEquals(2048, manifest.memoryMiB());
        assertEquals("libpvm_compiler_payload.so", manifest.payloadBinary());
        assertEquals(2, manifest.libs().size());
    }

    @Test
    public void missingManifestIsNotPresent() {
        assertFalse(PvmCompilerManifest.parse(null).isPresent());
        assertFalse(PvmCompilerManifest.parse("").isPresent());
        assertFalse(PvmCompilerManifest.parse("   \r\n\t ").isPresent());
    }

    @Test
    public void malformedJsonIsNotPresent() {
        assertFalse(PvmCompilerManifest.parse(truncated()).isPresent());
        assertFalse(PvmCompilerManifest.parse("{,}").isPresent());
        assertFalse(PvmCompilerManifest.parse("{\"schema\":1,}").isPresent());
        assertFalse(PvmCompilerManifest.parse(
                valid().replace("\"22.1.8\"", "\"22.1\\q8\"")).isPresent());
        assertFalse(PvmCompilerManifest.parse(
                valid().replace("\"22.1.8\"", "\"22.1\n8\"")).isPresent());
    }

    @Test
    public void nonObjectRootIsNotPresent() {
        assertFalse(PvmCompilerManifest.parse("[]").isPresent());
        assertFalse(PvmCompilerManifest.parse("\"manifest\"").isPresent());
        assertFalse(PvmCompilerManifest.parse("1").isPresent());
        assertFalse(PvmCompilerManifest.parse("null").isPresent());
    }

    @Test
    public void trailingCharactersAreRejected() {
        assertFalse(PvmCompilerManifest.parse(valid() + "x").isPresent());
        assertFalse(PvmCompilerManifest.parse(valid() + "{}").isPresent());
        assertFalse(PvmCompilerManifest.parse(valid() + " " + valid()).isPresent());
    }

    @Test
    public void everyRequiredFieldIsMandatory() {
        for (String omitted : ALL_MEMBERS) {
            assertFalse(omitted, PvmCompilerManifest.parse(without(omitted)).isPresent());
        }
    }

    @Test
    public void schemaMustBeTheIntegerOne() {
        assertFalse(PvmCompilerManifest.parse(
                valid().replace(SCHEMA_MEMBER, "\"schema\":2")).isPresent());
        assertFalse(PvmCompilerManifest.parse(
                valid().replace(SCHEMA_MEMBER, "\"schema\":\"1\"")).isPresent());
        assertFalse(PvmCompilerManifest.parse(
                valid().replace(SCHEMA_MEMBER, "\"schema\":1.0")).isPresent());
        assertFalse(PvmCompilerManifest.parse(
                valid().replace(SCHEMA_MEMBER, "\"schema\":01")).isPresent());
    }

    @Test
    public void topologyMustBeOneCpu() {
        assertFalse(PvmCompilerManifest.parse(
                valid().replace(TOPOLOGY_MEMBER, "\"vcpu_topology\":\"many_cpu\"")).isPresent());
        assertFalse(PvmCompilerManifest.parse(
                valid().replace(TOPOLOGY_MEMBER, "\"vcpu_topology\":\"\"")).isPresent());
    }

    @Test
    public void memoryMustBeAnIntegerInsideTheRange() {
        assertFalse(PvmCompilerManifest.parse(memory(0)).isPresent());
        assertFalse(PvmCompilerManifest.parse(memory(-1)).isPresent());
        assertFalse(PvmCompilerManifest.parse(memory(16385)).isPresent());
        assertFalse(PvmCompilerManifest.parse(memory("2048.0")).isPresent());
        assertFalse(PvmCompilerManifest.parse(memory("\"2048\"")).isPresent());
        assertFalse(PvmCompilerManifest.parse(memory("9".repeat(20))).isPresent());

        assertEquals(1, PvmCompilerManifest.parse(memory(1)).orElseThrow().memoryMiB());
        assertEquals(16384, PvmCompilerManifest.parse(memory(16384)).orElseThrow().memoryMiB());
    }

    @Test
    public void blankIdentityFieldsAreRejected() {
        assertFalse(PvmCompilerManifest.parse(
                valid().replace("\"22.1.8\"", "\"   \"")).isPresent());
        assertFalse(PvmCompilerManifest.parse(
                valid().replace(
                        "\"aarch64-unknown-linux-android\"", "\" \"")).isPresent());
    }

    @Test
    public void payloadBinaryMustBeABareSharedObjectName() {
        assertFalse(PvmCompilerManifest.parse(
                valid().replace(
                        "\"libpvm_compiler_payload.so\"",
                        "\"lib/libpvm_compiler_payload.so\"")).isPresent());
        assertFalse(PvmCompilerManifest.parse(
                valid().replace(
                        "\"libpvm_compiler_payload.so\"",
                        "\"libpvm_compiler_payload\"")).isPresent());
        assertFalse(PvmCompilerManifest.parse(
                valid().replace(
                        "\"libpvm_compiler_payload.so\"",
                        "\".so\"")).isPresent());
    }

    @Test
    public void emptyOrNonArrayLibsAreRejected() {
        assertFalse(PvmCompilerManifest.parse(libs("[]")).isPresent());
        assertFalse(PvmCompilerManifest.parse(libs("\"libLLVM.so\"")).isPresent());
        assertFalse(PvmCompilerManifest.parse(libs("null")).isPresent());
        assertFalse(PvmCompilerManifest.parse(libs("[\"libLLVM.so\"]")).isPresent());
    }

    @Test
    public void duplicateLibraryNameIsRejected() {
        String duplicated = "[" + lib("libLLVM.so", "1", SHA)
                + "," + lib("libLLVM.so", "2", OTHER_SHA) + "]";

        assertFalse(PvmCompilerManifest.parse(libs(duplicated)).isPresent());
    }

    @Test
    public void badSha256IsRejected() {
        assertFalse(PvmCompilerManifest.parse(
                libs("[" + lib("libLLVM.so", "1", "A".repeat(64)) + "]")).isPresent());
        assertFalse(PvmCompilerManifest.parse(
                libs("[" + lib("libLLVM.so", "1", "a".repeat(63)) + "]")).isPresent());
        assertFalse(PvmCompilerManifest.parse(
                libs("[" + lib("libLLVM.so", "1", "z".repeat(64)) + "]")).isPresent());
        assertFalse(PvmCompilerManifest.parse(
                libs("[" + lib("libLLVM.so", "1", "") + "]")).isPresent());
    }

    @Test
    public void badLibrarySizeIsRejected() {
        assertFalse(PvmCompilerManifest.parse(
                libs("[" + lib("libLLVM.so", "0", SHA) + "]")).isPresent());
        assertFalse(PvmCompilerManifest.parse(
                libs("[" + lib("libLLVM.so", "-1", SHA) + "]")).isPresent());
        assertFalse(PvmCompilerManifest.parse(
                libs("[" + lib("libLLVM.so", "\"64700000\"", SHA) + "]")).isPresent());
    }

    @Test
    public void badLibraryNameIsRejected() {
        assertFalse(PvmCompilerManifest.parse(
                libs("[" + lib("libLLVM.dylib", "1", SHA) + "]")).isPresent());
        assertFalse(PvmCompilerManifest.parse(
                libs("[" + lib("  ", "1", SHA) + "]")).isPresent());
    }

    @Test
    public void duplicateTopLevelKeyIsRejected() {
        String duplicated = valid().substring(0, valid().length() - 1) + "," + SCHEMA_MEMBER + "}";

        assertFalse(PvmCompilerManifest.parse(duplicated).isPresent());
    }

    @Test
    public void unknownFieldsAreIgnoredForForwardCompatibility() {
        String extended = withUnknown(",\"future_field\":\"value\",\"future_list\":[1,2,{\"a\":true}]");

        Optional<PvmCompilerManifest> parsed = PvmCompilerManifest.parse(extended);

        assertTrue(parsed.isPresent());
        assertEquals("22.1.8", parsed.get().compilerVersion());
    }

    @Test
    public void nestingIsBoundedButShallowUnknownObjectsAreFine() {
        assertTrue(PvmCompilerManifest.parse(withUnknown(",\"future\":" + nested(7))).isPresent());
        assertFalse(PvmCompilerManifest.parse(withUnknown(",\"future\":" + nested(9))).isPresent());
    }

    @Test
    public void libsAreSortedByName() {
        List<PvmCompilerManifest.Lib> libs = PvmCompilerManifest.parse(valid()).orElseThrow().libs();

        assertEquals("libLLVM.so", libs.get(0).name());
        assertEquals("libclang-cpp.so", libs.get(1).name());
        assertEquals(64700000L, libs.get(0).size());
        assertEquals(SHA, libs.get(0).sha256());
    }

    @Test
    public void libsAreImmutable() {
        PvmCompilerManifest manifest = PvmCompilerManifest.parse(valid()).orElseThrow();
        List<PvmCompilerManifest.Lib> libs = manifest.libs();

        assertThrows(UnsupportedOperationException.class,
                () -> libs.add(new PvmCompilerManifest.Lib("libextra.so", 1L, SHA)));
        assertThrows(UnsupportedOperationException.class, () -> libs.clear());
        assertEquals(2, manifest.libs().size());
    }

    private static String valid() {
        return "{" + String.join(",", ALL_MEMBERS) + "}";
    }

    private static String truncated() {
        String json = valid();
        return json.substring(0, json.length() - 1);
    }

    /** The valid document with the given member removed. */
    private static String without(String omitted) {
        List<String> members = new ArrayList<>();
        for (String member : ALL_MEMBERS) {
            if (!member.equals(omitted)) {
                members.add(member);
            }
        }
        return "{" + String.join(",", members) + "}";
    }

    /** The valid document with a replacement for its memory member. */
    private static String memory(Object value) {
        return valid().replace(MEMORY_MEMBER, "\"memory_mib\":" + value);
    }

    /** The valid document with a replacement for its libs member. */
    private static String libs(String rawLibs) {
        return valid().replace(LIBS_MEMBER, "\"libs\":" + rawLibs);
    }

    /** The valid document plus one extra member sequence. */
    private static String withUnknown(String extraMember) {
        String json = valid();
        return json.substring(0, json.length() - 1) + extraMember + "}";
    }

    private static String lib(String name, String size, String sha256) {
        return "{\"name\":\"" + name + "\",\"size\":" + size + ",\"sha256\":\"" + sha256 + "\"}";
    }

    private static String nested(int depth) {
        StringBuilder out = new StringBuilder();
        for (int level = 0; level < depth; level++) {
            out.append("{\"n\":");
        }
        out.append('0');
        for (int level = 0; level < depth; level++) {
            out.append('}');
        }
        return out.toString();
    }
}
