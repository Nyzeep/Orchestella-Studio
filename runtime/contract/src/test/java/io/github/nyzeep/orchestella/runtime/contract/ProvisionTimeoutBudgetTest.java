package io.github.nyzeep.orchestella.runtime.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * Cross-layer consistency of the first-boot time budget. The guest sizes each provision step
 * from an I/O class, the guest oneshot wall has to cover two sequential steps, and the host
 * handshake deadline has to stay above both. These files are read as text so a change on one
 * side without the other fails here instead of on a device.
 */
public final class ProvisionTimeoutBudgetTest {
    private static final String PROVISION_SCRIPT = "guest-assets/scripts/orchestella-guest-provision.sh";
    private static final String RUNTIME_FUNCTIONS = "guest-assets/initramfs/runtime-functions.sh";
    private static final String GENERATED_INIT = "guest-assets/initramfs/init";
    private static final String BRIDGE_CLIENT =
            "runtime/contract/src/main/java/io/github/nyzeep/orchestella/runtime/contract/BridgeClient.java";
    private static final Pattern SYSTEMD_WALL = Pattern.compile("^TimeoutStartSec=(\\d+)$", Pattern.MULTILINE);
    private static final Pattern HOST_DEADLINE = Pattern.compile(
            "DEFAULT_STARTUP_TIMEOUT_MS\\s*=\\s*([0-9_]+)L\\s*;");

    private static Path repositoryRoot() {
        String start = System.getProperty("user.dir");
        Path current = Paths.get(start).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))
                    && Files.isRegularFile(current.resolve(PROVISION_SCRIPT))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("no repository root (settings.gradle.kts) above " + start);
    }

    private static long firstNumber(Path file, Pattern pattern) throws IOException {
        Matcher matcher = pattern.matcher(Files.readString(file, StandardCharsets.UTF_8));
        if (!matcher.find()) {
            throw new AssertionError("cannot parse " + pattern.pattern() + " from " + file);
        }
        return Long.parseLong(matcher.group(1).replace("_", ""));
    }

    private static long tierDefault(String tierClass) throws IOException {
        Pattern pattern = Pattern.compile("^\\s*" + tierClass + "\\)\\s*default=(\\d+);", Pattern.MULTILINE);
        return firstNumber(repositoryRoot().resolve(PROVISION_SCRIPT), pattern);
    }

    private static long guestWall(String relativePath) throws IOException {
        return firstNumber(repositoryRoot().resolve(relativePath), SYSTEMD_WALL);
    }

    private static long hostDeadline() throws IOException {
        return firstNumber(repositoryRoot().resolve(BRIDGE_CLIENT), HOST_DEADLINE);
    }

    @Test
    public void guestTierDefaultsDeclareOneBudgetPerIoClass() throws IOException {
        assertEquals("download tier default", 900L, tierDefault("download"));
        assertEquals("transaction tier default", 3600L, tierDefault("transaction"));
        assertEquals("compute tier default", 1800L, tierDefault("compute"));
        assertEquals("verify tier default", 600L, tierDefault("verify"));
    }

    @Test
    public void guestOneshotWallCoversADownloadFollowedByATransaction() throws IOException {
        long worstSequential = tierDefault("download") + tierDefault("transaction");
        long wall = guestWall(RUNTIME_FUNCTIONS);
        assertTrue("TimeoutStartSec=" + wall + " must cover " + worstSequential + "s of provision work",
                wall >= worstSequential);
    }

    @Test
    public void generatedInitCarriesTheSameWallAsItsTemplate() throws IOException {
        assertEquals("generated init must be re-synced from runtime-functions.sh",
                guestWall(RUNTIME_FUNCTIONS), guestWall(GENERATED_INIT));
    }

    @Test
    public void hostDeadlineOutlastsTheGuestWorkItWraps() throws IOException {
        long worstGuestSeconds = tierDefault("download") + tierDefault("transaction");
        long deadlineMs = hostDeadline();
        assertTrue("DEFAULT_STARTUP_TIMEOUT_MS=" + deadlineMs + " must exceed " + worstGuestSeconds + "s",
                deadlineMs > worstGuestSeconds * 1000L);
    }
}
