package io.github.nyzeep.orchestella.ui.screens.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stack identities for the shell-pushed connection pages must stay unique and
 * stable. VM hardware is deliberately absent: it has no shell route (it opens
 * from the instance detail page through `ConnectionPagesActivity`), so a key
 * for it would pin a route nothing consumes.
 */
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
        assertEquals(
            "VM hardware opens only through the Activity host and must not join the shell stack keys",
            2,
            ConnectionsPage.allKeys.size,
        )
        assertTrue(
            ConnectionsPage.allKeys.containsAll(
                listOf(
                    ConnectionsPage.SSH_KEY,
                    ConnectionsPage.PORT_FORWARDING_KEY,
                ),
            ),
        )
    }
}
