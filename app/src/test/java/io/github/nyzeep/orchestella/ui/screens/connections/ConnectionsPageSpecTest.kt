package io.github.nyzeep.orchestella.ui.screens.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stack identities for the three connection pages must stay unique and stable. */
class ConnectionsPageSpecTest {

    @Test
    fun connectionPageKeysAreUnique() {
        assertEquals(
            ConnectionsPage.allKeys.size,
            ConnectionsPage.allKeys.toSet().size,
        )
    }

    @Test
    fun expectedKeysAreStable() {
        assertEquals("connections.ssh", ConnectionsPage.SSH_KEY)
        assertEquals("connections.port_forwarding", ConnectionsPage.PORT_FORWARDING_KEY)
        assertEquals("connections.vm_hardware", ConnectionsPage.VM_HARDWARE_KEY)
        assertTrue(ConnectionsPage.allKeys.containsAll(
            listOf(
                ConnectionsPage.SSH_KEY,
                ConnectionsPage.PORT_FORWARDING_KEY,
                ConnectionsPage.VM_HARDWARE_KEY,
            ),
        ))
    }
}
