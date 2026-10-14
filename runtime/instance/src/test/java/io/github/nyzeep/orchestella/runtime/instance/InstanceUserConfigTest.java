package io.github.nyzeep.orchestella.runtime.instance;

import java.util.Objects;
import java.util.Properties;

/**
 * Pure JVM contract test for {@link InstanceUserConfig} (Ticket 03).
 */
public final class InstanceUserConfigTest {

    public static void main(String[] args) {
        testDefaultRootFactory();
        testValidUsernames();
        testInvalidUsernames();
        testConstructorValidation();
        testHostnameNormalizationAndValidation();
        testPropertiesSerializationRoundtrip();
        testUnknownStoredPasswordIsNotInvented();
        testStoredLegacyPasswordIsPreserved();
        System.out.println("InstanceUserConfigTest passed successfully!");
    }

    private static void testDefaultRootFactory() {
        InstanceUserConfig config = InstanceUserConfig.defaultRoot();
        assertEquals("root", config.username(), "defaultRoot username");
        assertTrue(config.password().length() >= 16, "defaultRoot must generate a strong password");
        assertFalse("1234".equals(config.password()), "defaultRoot must not use a public password");
        java.util.Set<String> passwords = new java.util.HashSet<>();
        for (int i = 0; i < 64; i++) {
            String password = InstanceUserConfig.defaultRoot().password();
            assertTrue(password.matches("[A-Za-z0-9]{16,}"), "generated password must be guest-safe");
            assertTrue(passwords.add(password), "new instances must not share generated passwords");
        }
        assertFalse(config.toString().contains(config.password()), "toString must not disclose password");
        assertEquals("orchestella", config.hostname(), "defaultRoot hostname");
        assertTrue(config.hasSudo(), "defaultRoot hasSudo");
    }

    private static void testValidUsernames() {
        String[] validNames = {"root", "dev", "nyzee", "orchestella", "user_1", "a-b", "_admin", "u123456789012345"};
        for (String name : validNames) {
            assertTrue(InstanceUserConfig.isValidUsername(name), "valid username: " + name);
        }
    }

    private static void testInvalidUsernames() {
        String[] invalidNames = {
                null,
                "",
                "a", // less than 2 chars
                "thisusernameiswaytoolongtobevalid", // more than 16 chars
                "1user", // starts with digit
                "Root", // contains uppercase
                "user@name", // invalid symbol
                "user name", // space
                "-user", // starts with hyphen
                "user.name", // dot not allowed
        };
        for (String name : invalidNames) {
            assertFalse(InstanceUserConfig.isValidUsername(name), "invalid username should be rejected: " + name);
        }
    }

    private static void testConstructorValidation() {
        InstanceUserConfig custom = new InstanceUserConfig("dev", "mySecret123", true, "workstation");
        assertEquals("dev", custom.username(), "custom username");
        assertEquals("mySecret123", custom.password(), "custom password");
        assertTrue(custom.hasSudo(), "custom hasSudo");
        assertEquals("workstation", custom.hostname(), "custom hostname");

        assertThrows(IllegalArgumentException.class,
                () -> new InstanceUserConfig("InvalidUser", "pass", true, "host"),
                "uppercase username must throw");

        assertThrows(IllegalArgumentException.class,
                () -> new InstanceUserConfig("123", "pass", true, "host"),
                "digit starting username must throw");

        assertThrows(IllegalArgumentException.class,
                () -> new InstanceUserConfig("dev", "", true, "host"),
                "blank password must throw");

        assertThrows(IllegalArgumentException.class,
                () -> new InstanceUserConfig("dev", null, true, "host"),
                "null password must throw");
    }

    private static void testHostnameNormalizationAndValidation() {
        // Null or blank hostname defaults to orchestella
        InstanceUserConfig withNullHost = new InstanceUserConfig("dev", "pass123", false, null);
        assertEquals("orchestella", withNullHost.hostname(), "null hostname normalized");

        InstanceUserConfig withBlankHost = new InstanceUserConfig("dev", "pass123", false, "   ");
        assertEquals("orchestella", withBlankHost.hostname(), "blank hostname normalized");

        // Valid custom hostnames (normalized to lowercase)
        InstanceUserConfig customHost = new InstanceUserConfig("dev", "pass123", false, "My-Host-01");
        assertEquals("my-host-01", customHost.hostname(), "hostname lowercase normalized");

        // Invalid hostname throws
        assertThrows(IllegalArgumentException.class,
                () -> new InstanceUserConfig("dev", "pass123", false, "-invalid-host"),
                "hostname starting with hyphen must throw");

        assertThrows(IllegalArgumentException.class,
                () -> new InstanceUserConfig("dev", "pass123", false, "host@name"),
                "hostname with special characters must throw");
    }

    private static void testPropertiesSerializationRoundtrip() {
        InstanceUserConfig original = new InstanceUserConfig("dev", "pass456", false, "box");
        Properties props = original.toProperties();
        assertEquals("dev", props.getProperty("username"), "props username");
        assertEquals("pass456", props.getProperty("password"), "props password");
        assertEquals("false", props.getProperty("hasSudo"), "props hasSudo");
        assertEquals("box", props.getProperty("hostname"), "props hostname");

        InstanceUserConfig restored = InstanceUserConfig.fromProperties(props);
        assertEquals(original.username(), restored.username(), "restored username");
        assertEquals(original.password(), restored.password(), "restored password");
        assertEquals(original.hasSudo(), restored.hasSudo(), "restored hasSudo");
        assertEquals(original.hostname(), restored.hostname(), "restored hostname");
        assertEquals(original, restored, "equals across roundtrip");
        assertEquals(original.hashCode(), restored.hashCode(), "hashcode across roundtrip");
    }

    private static void testUnknownStoredPasswordIsNotInvented() {
        for (String password : new String[] {null, "", "   "}) {
            Properties properties = new Properties();
            if (password != null) {
                properties.setProperty("password", password);
            }
            assertThrows(IllegalArgumentException.class,
                    () -> InstanceUserConfig.fromProperties(properties),
                    "missing stored password must not be replaced by a guessed or random value");
        }
    }

    private static void testStoredLegacyPasswordIsPreserved() {
        Properties properties = new Properties();
        properties.setProperty("password", "1234");
        InstanceUserConfig legacy = InstanceUserConfig.fromProperties(properties);
        assertEquals("1234", legacy.password(), "known legacy password is preserved on read");
        InstanceUserConfig generated = InstanceUserConfig.defaultRoot();
        assertEquals(generated.password(), InstanceUserConfig.fromProperties(generated.toProperties()).password(),
                "generated password survives serialization without regeneration");
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(description + ": values differ (redacted)");
        }
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }

    private static void assertFalse(boolean condition, String description) {
        if (condition) {
            throw new AssertionError(description);
        }
    }

    private static <T extends Throwable> void assertThrows(
            Class<T> type, ThrowingRunnable operation, String description) {
        try {
            operation.run();
        } catch (Throwable throwable) {
            if (type.isInstance(throwable)) {
                return;
            }
            throw new AssertionError(description + ": unexpected exception", throwable);
        }
        throw new AssertionError(description + ": expected exception " + type.getName());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
