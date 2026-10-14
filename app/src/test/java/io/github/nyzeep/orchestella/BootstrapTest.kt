package io.github.nyzeep.orchestella

import org.junit.Assert.assertEquals
import org.junit.Test

class BootstrapTest {
    @Test
    fun applicationIdMatchesBootstrapChoice() {
        assertEquals("io.github.nyzeep.orchestella", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun manifestDeclaresAccessNetworkStatePermission() {
        val manifestFile = java.io.File("src/main/AndroidManifest.xml")
        val content = if (manifestFile.exists()) {
            manifestFile.readText()
        } else {
            java.io.File("app/src/main/AndroidManifest.xml").readText()
        }
        org.junit.Assert.assertTrue(
            "AndroidManifest.xml must declare ACCESS_NETWORK_STATE permission to prevent launch crashes",
            content.contains("android.permission.ACCESS_NETWORK_STATE")
        )
    }

    @Test
    fun alpineInittabDoesNotContainProxyRelayRespawn() {
        val initFile = java.io.File("guest-assets/initramfs/init")
        val content = if (initFile.exists()) {
            initFile.readText()
        } else {
            java.io.File("../guest-assets/initramfs/init").readText()
        }
        org.junit.Assert.assertFalse(
            "Alpine inittab template must NOT respawn proxy-relay to prevent terminal flood",
            content.contains("::respawn:/usr/sbin/orchestella-proxy-relay")
        )
        org.junit.Assert.assertTrue(
            "Managed Alpine inittab must remove the exact legacy proxy respawn entry",
            content.contains("/^::respawn:\\/usr\\/sbin\\/orchestella-proxy-relay$/d")
        )
        org.junit.Assert.assertTrue(
            "Managed Alpine inittab must remove the legacy DroidLayer entry too",
            content.contains("/^::respawn:\\/usr\\/sbin\\/droidlayer-proxy-relay$/d")
        )
        org.junit.Assert.assertTrue(
            "Managed block must preserve distro and custom inittab entries",
            content.contains("# ORCHESTELLA_OPENRC_BEGIN") &&
                content.contains("# ORCHESTELLA_OPENRC_END") &&
                content.contains("Keep the distro/user inittab")
        )
        org.junit.Assert.assertFalse(
            "Alpine restart must not overwrite the complete user inittab",
            content.contains("cat > \"\$target_root/etc/inittab\"")
        )
        org.junit.Assert.assertTrue(
            "OpenRC must invoke the synchronous provision script instead of pre-writing success",
            content.contains("/opt/orchestella/firstboot.sh --native") &&
                content.contains("required-openrc-service-missing")
        )
        org.junit.Assert.assertTrue(
            "Systemd dbus-broker enablement must target the real installed unit",
            content.contains("dbus_enable_unit=dbus-broker.service")
        )
    }

    @Test
    fun proxyRelayHasSafeShellPreamble() {
        val scriptFile = java.io.File("guest-assets/scripts/orchestella-proxy-relay.pl")
        val content = if (scriptFile.exists()) {
            scriptFile.readText()
        } else {
            java.io.File("../guest-assets/scripts/orchestella-proxy-relay.pl").readText()
        }
        org.junit.Assert.assertTrue(
            "Proxy relay script must start with /bin/sh preamble for compatibility",
            content.startsWith("#!/bin/sh")
        )
        org.junit.Assert.assertTrue(
            "Proxy relay script must check for perl before attempting execution",
            content.contains("command -v perl")
        )
    }
}
