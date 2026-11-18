package io.github.nyzeep.orchestella.ui.screens.connections

import io.github.nyzeep.orchestella.ssh.SshProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Form contract for the SSH page; storage behaviour is untouched. */
class SshProfileDraftTest {

    @Test
    fun newDraftUsesTheCallerDefaults() {
        val draft = SshProfileDraftSpec.new("本地 Linux 实例", "127.0.0.1", 2222, "root")
        assertEquals("本地 Linux 实例", draft.label)
        assertEquals("127.0.0.1", draft.host)
        assertEquals("2222", draft.port)
        assertEquals("root", draft.username)
        assertFalse(draft.portError)
    }

    @Test
    fun editDraftPreservesIdAndInstanceBinding() {
        val existing = SshProfile(
            id = "profile-1",
            label = "My VM",
            host = "192.0.2.2",
            port = 2200,
            username = "dev",
            instanceId = "inst-9",
        )
        val draft = SshProfileDraftSpec.edit(existing)
        val applied = draft.toProfile(existing, instanceId = "inst-9", defaultLabel = "SSH 连接")
        assertEquals("profile-1", applied.id)
        assertEquals("inst-9", applied.instanceId)
        assertEquals("My VM", applied.label)
        assertEquals(2200, applied.port)
    }

    @Test
    fun blankFieldsFallBackToTheHistoricalDefaults() {
        val draft = SshProfileDraft("", " ", "", "  ")
        val applied = draft.toProfile(null, instanceId = null, defaultLabel = "SSH 连接")
        assertEquals("SSH 连接", applied.label)
        assertEquals("127.0.0.1", applied.host)
        assertEquals(22, applied.port)
        assertEquals("root", applied.username)
    }

    @Test
    fun unparsablePortFallsBackTo22AndFlagsTheError() {
        val draft = SshProfileDraft("conn", "host", "not-a-port", "root")
        assertTrue(draft.portError)
        assertEquals(22, draft.toProfile(null, null, "SSH 连接").port)
    }

    @Test
    fun outOfRangePortIsRejectedByTheForm() {
        assertTrue(SshProfileDraft("conn", "host", "70000", "root").portError)
        assertTrue(SshProfileDraft("conn", "host", "0", "root").portError)
        assertFalse(SshProfileDraft("conn", "host", "65535", "root").portError)
    }
}
