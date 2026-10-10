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
            "Init script must clean up legacy inittab proxy-relay on restart",
            content.contains("sed -i '/orchestella-proxy-relay/d' /newroot/etc/inittab")
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
