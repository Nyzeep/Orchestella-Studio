package io.github.nyzeep.orchestella.guestassets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * G-05 static checks for the guest audio relay and its wiring (JVM-side mirror of
 * audio/tests/test_audio_relay.py, which runs on Linux only).
 *
 * <p>Honesty boundary: these checks prove script shape and fixed endpoints only.
 * The guest -> host PCM closure is unverified on real devices.
 */
final class AudioRelayScriptTest {

    private static Path getGuestAssetsDir() {
        String projectDir = System.getProperty("guestAssetsProjectDir");
        if (projectDir != null && !projectDir.isBlank()) {
            return Path.of(projectDir);
        }
        return Path.of(".");
    }

    private static String audioRelayScript() throws IOException {
        Path script = getGuestAssetsDir().resolve("audio").resolve("audio-relay.py");
        assertTrue(Files.exists(script), "audio-relay.py must exist at guest-assets/audio/audio-relay.py");
        byte[] bytes = Files.readAllBytes(script);
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertFalse(content.contains("\r"), "audio-relay.py must use LF line endings only");
        return content;
    }

    private static String profilectlScript() throws IOException {
        Path script = getGuestAssetsDir().resolve("features").resolve("profilectl.py");
        assertTrue(Files.exists(script), "profilectl.py must exist");
        return Files.readString(script, StandardCharsets.UTF_8);
    }

    @Test
    void audioRelayScriptExistsWithShebangAndLfOnly() throws IOException {
        String content = audioRelayScript();
        assertTrue(content.startsWith("#!/usr/bin/env python3"), "audio-relay.py must start with the python3 shebang");
        assertTrue(content.contains("SPDX-License-Identifier: GPL-3.0-or-later"), "audio-relay.py must keep the display-relay license header");
    }

    @Test
    void audioRelayUsesFixedVirtioPortAndAudioProtocolPreamble() throws IOException {
        String content = audioRelayScript();
        assertTrue(content.contains("/dev/virtio-ports/orchestella.audio"),
                "audio relay must use the fixed orchestella.audio virtio port");
        assertTrue(content.contains("b\"orchestella.audio\\n\""),
                "audio relay must verify the port's sysfs identity");
        assertTrue(content.contains("ORCHESTELLA/AUDIO READY 1\\n"), "READY line must be the fixed audio protocol preamble");
        assertTrue(content.contains("ORCHESTELLA/AUDIO 1 "), "token preamble must be the fixed audio protocol preamble");
        assertTrue(content.contains("ORCHESTELLA/AUDIO ERROR "), "error lines must use the audio protocol prefix");
    }

    @Test
    void audioRelayBackendIsLoopbackPinnedToFixedPulseAudioEndpoint() throws IOException {
        String content = audioRelayScript();
        assertTrue(content.contains("AUDIO_BACKEND_HOST = \"127.0.0.1\""),
                "backend host must be pinned to loopback");
        assertTrue(content.contains("AUDIO_BACKEND_PORT = 9104"),
                "backend port must stay the fixed 9104 simple-protocol endpoint");
        assertFalse(content.contains("0.0.0.0"), "audio relay must never reference a wildcard address");
        assertFalse(content.contains("socket.bind("), "audio relay must not create any listener");
        assertFalse(content.contains("module-simple-protocol-tcp"),
                "audio relay must not touch pulseaudio configuration semantics");
    }

    @Test
    void audioRelayKeepsTokenAuthAndPrivilegeDropSkeleton() throws IOException {
        String content = audioRelayScript();
        assertTrue(content.contains("/etc/droidlayer/bridge.token"), "token path must stay fixed");
        assertTrue(content.contains("hmac.compare_digest"), "token comparison must be constant time");
        assertTrue(content.contains("def launch_worker"), "root supervisor must exec a dropped-privilege worker");
        assertTrue(content.contains("extra_groups=()"), "worker exec must clear supplementary groups");
        assertTrue(content.contains("--session"), "worker entry must be the hidden --session mode");
        assertTrue(content.contains("PCM playback has never been verified on a real device"),
                "the unverified-on-device note must stay in the script header");
    }

    @Test
    void audioRelayHasNoVsockTransportThisTicket() throws IOException {
        // AVF vsock audio is explicitly out of G-05 (recorded for the device window, G-08).
        String content = audioRelayScript();
        assertFalse(content.contains("AF_VSOCK"), "audio relay must not open a vsock transport in G-05");
        assertFalse(content.contains("--transport"), "audio relay must not expose a transport override");
    }

    @Test
    void desktopProfileWiresAudioRelayUnitAfterDisplayRelay() throws IOException {
        String content = profilectlScript();
        assertTrue(content.contains("\"orchestella-audio-relay.service\""),
                "desktop-labwc UNITS must append orchestella-audio-relay.service");
        int displayRelay = content.indexOf("orchestella-display-relay.service");
        int audioRelay = content.indexOf("orchestella-audio-relay.service");
        assertTrue(displayRelay >= 0 && audioRelay > displayRelay,
                "audio-relay unit must be appended after the display relay");
        assertTrue(content.contains("/usr/local/lib/orchestella/audio-relay.py --user "),
                "the audio relay unit must exec the payload audio-relay.py");
        assertTrue(content.contains("audio-relay-start-failed"),
                "the start sequence must attempt the audio relay best-effort");
        int audioStart = content.indexOf("UNITS[self.profile][3]");
        int readinessGate = content.indexOf("for unit in UNITS[self.profile][:3]");
        assertTrue(audioStart >= 0 && readinessGate > audioStart,
                "audio relay must start before, and stay outside, the display readiness gate");
    }

    @Test
    void payloadSyncEmbedsAudioRelayIntoGuestInit() throws IOException {
        Path sync = getGuestAssetsDir().resolve("scripts").resolve("sync-guest-init.py");
        String content = Files.readString(sync, StandardCharsets.UTF_8);
        assertTrue(content.contains("(\"audio/audio-relay.py\", \"audio-relay.py\", \"0755\")"),
                "payload sync must embed audio-relay.py like display-relay.py");
    }
}
