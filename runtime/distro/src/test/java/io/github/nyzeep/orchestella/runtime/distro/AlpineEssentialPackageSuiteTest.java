package io.github.nyzeep.orchestella.runtime.distro;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Pure JVM tests for AlpineEssentialPackageSuite package recommendations and provisioning scripts.
 */
public final class AlpineEssentialPackageSuiteTest {

    public static void main(String[] args) {
        testDefaultRecommendedPackagesContainsCoreTools();
        testGenerateInstallCommandWithSelectedPackages();
        testGenerateInstallCommandHandlesDuplicatesAndWhitespace();
        testGenerateInstallCommandEmptyOrNullReturnsEmpty();
        testGenerateShellSwitchCommandDefaults();
        testGenerateShellSwitchCommandCustomUser();
        testGenerateFullProvisioningScript();
    }

    private static void testDefaultRecommendedPackagesContainsCoreTools() {
        List<String> packages = AlpineEssentialPackageSuite.getRecommendedPackages();
        assertNotNull(packages);
        assertTrue("Must contain bash", packages.contains("bash"));
        assertTrue("Must contain bash-completion", packages.contains("bash-completion"));
        assertTrue("Must contain curl", packages.contains("curl"));
        assertTrue("Must contain wget", packages.contains("wget"));
        assertTrue("Must contain ca-certificates", packages.contains("ca-certificates"));
        assertTrue("Must contain sudo", packages.contains("sudo"));
        assertTrue("Must contain shadow", packages.contains("shadow"));
        assertTrue("Must contain coreutils", packages.contains("coreutils"));
        assertTrue("Must contain grep", packages.contains("grep"));
        assertTrue("Must contain sed", packages.contains("sed"));
        assertTrue("Must contain nano", packages.contains("nano"));
        assertTrue("Must contain procps", packages.contains("procps"));
        assertTrue("Must contain tar", packages.contains("tar"));
    }

    private static void testGenerateInstallCommandWithSelectedPackages() {
        List<String> selected = Arrays.asList("bash", "curl", "sudo");
        String cmd = AlpineEssentialPackageSuite.generateInstallCommand(selected);
        assertEquals("apk add --no-cache bash curl sudo", cmd);
    }

    private static void testGenerateInstallCommandHandlesDuplicatesAndWhitespace() {
        List<String> selected = Arrays.asList("bash", " curl ", "bash", "sudo ");
        String cmd = AlpineEssentialPackageSuite.generateInstallCommand(selected);
        assertEquals("apk add --no-cache bash curl sudo", cmd);
    }

    private static void testGenerateInstallCommandEmptyOrNullReturnsEmpty() {
        assertEquals("", AlpineEssentialPackageSuite.generateInstallCommand(Collections.emptyList()));
        assertEquals("", AlpineEssentialPackageSuite.generateInstallCommand(null));
    }

    private static void testGenerateShellSwitchCommandDefaults() {
        String cmd = AlpineEssentialPackageSuite.generateShellSwitchCommand();
        assertEquals("chsh -s /bin/bash root", cmd);
    }

    private static void testGenerateShellSwitchCommandCustomUser() {
        String cmd = AlpineEssentialPackageSuite.generateShellSwitchCommand("/bin/bash", "developer");
        assertEquals("chsh -s /bin/bash developer", cmd);
    }

    private static void testGenerateFullProvisioningScript() {
        List<String> selected = Arrays.asList("bash", "curl");
        String script = AlpineEssentialPackageSuite.generateFullProvisioningScript(selected);
        assertEquals("apk add --no-cache bash curl && chsh -s /bin/bash root", script);

        String emptyScript = AlpineEssentialPackageSuite.generateFullProvisioningScript(Collections.emptyList());
        assertEquals("chsh -s /bin/bash root", emptyScript);
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertTrue(String message, boolean condition) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertNotNull(Object obj) {
        if (obj == null) {
            throw new AssertionError("object must not be null");
        }
    }
}
