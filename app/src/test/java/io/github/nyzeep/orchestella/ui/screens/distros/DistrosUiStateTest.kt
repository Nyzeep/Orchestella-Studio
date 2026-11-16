package io.github.nyzeep.orchestella.ui.screens.distros

import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.UmlRootfsCardState
import io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistry
import io.github.nyzeep.orchestella.runtime.distro.DistroManifest
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3DistroIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发行版页的纯 JVM 契约：目录映射、能力 chip、未知 id 回退、单开手风琴、
 * UML 五个相位的映射与体积读数。不需要 Compose 或 Android 运行时。
 */
class DistrosUiStateTest {

    @Test
    fun catalogMappingKeepsEveryManifestInOrder() {
        val catalog = DistroCatalogRegistry.createDefault()
        val state = DistrosUiState.from(catalog)
        val manifests = catalog.listAll()

        assertEquals(7, manifests.size)
        assertEquals(manifests.size, state.distros.size)
        assertEquals(manifests.map { it.id() }, state.distros.map { it.id })
        assertFalse(state.isEmpty)

        state.distros.zip(manifests).forEach { (card, manifest) ->
            assertEquals(manifest.version(), card.version)
            assertEquals("aarch64", card.architecture)
            assertEquals(manifest.sizeBytes(), card.sizeBytes)
            assertEquals(manifest.sha256(), card.sha256)
            assertEquals(64, card.sha256.length)
            assertEquals(manifest.sha256().take(8), card.shaSummary)
            assertTrue("${card.id} must have a readable name", card.name.isNotBlank())
            assertTrue("${card.id} must carry a capability chip", card.capability != null)
            assertTrue("${card.id} must carry a description", card.descriptionRes != null)
        }
    }

    @Test
    fun capabilityAndCopyMatchTheOfficialCatalogue() {
        val cards = DistrosUiState.from(DistroCatalogRegistry.createDefault()).distros.associateBy { it.id }

        assertEquals(DistroCapability.Lts, cards.getValue("ubuntu").capability)
        assertEquals(DistroCapability.Minimal, cards.getValue("debian").capability)
        assertEquals(DistroCapability.Current, cards.getValue("fedora").capability)
        assertEquals(DistroCapability.Rolling, cards.getValue("arch").capability)
        assertEquals(DistroCapability.Enterprise, cards.getValue("opensuse").capability)
        assertEquals(DistroCapability.Enterprise, cards.getValue("almalinux").capability)
        assertEquals(DistroCapability.Enterprise, cards.getValue("rocky").capability)

        assertEquals(R.string.ui3_distros_description_ubuntu, cards.getValue("ubuntu").descriptionRes)
        assertEquals(R.string.ui3_distros_description_rocky, cards.getValue("rocky").descriptionRes)
        assertEquals("Ubuntu", cards.getValue("ubuntu").name)
        assertEquals("Arch Linux", cards.getValue("arch").name)
        assertEquals("openSUSE", cards.getValue("opensuse").name)
    }

    @Test
    fun unknownDistroFallsBackWithoutInventingCopy() {
        val registry = DistroCatalogRegistry()
        registry.register(
            DistroManifest(
                "gentoo",
                "2024",
                "aarch64",
                "https://example.com/gentoo.tar.xz",
                "gentoo.tar.xz",
                "b".repeat(64),
                42L * 1024L * 1024L,
            ),
        )

        val card = DistrosUiState.from(registry).distros.single()

        assertEquals("Gentoo", card.name)
        assertEquals("2024", card.version)
        assertNull("unknown ids must not claim a capability", card.capability)
        assertNull("unknown ids must not invent a description", card.descriptionRes)
        assertSame(
            "unknown ids must use the generic Linux mark",
            Oe3DistroIcons.generic,
            Oe3DistroIcons.of(card.id),
        )
    }

    @Test
    fun accordionKeepsAtMostOneCardOpen() {
        assertEquals("ubuntu", DistrosAccordion.toggle(openId = null, targetId = "ubuntu"))
        assertEquals("debian", DistrosAccordion.toggle(openId = "ubuntu", targetId = "debian"))
        assertNull(DistrosAccordion.toggle(openId = "debian", targetId = "debian"))
    }

    @Test
    fun umlCardMapsEveryHonestPhase() {
        assertFalse(UmlRootfsUi.from(model(UmlRootfsCardState.Phase.DISABLED), true).visible)

        val notReady = UmlRootfsUi.from(model(UmlRootfsCardState.Phase.NOT_READY), false)
        assertTrue(notReady.visible)
        assertEquals(UmlRootfsPhase.NotReady, notReady.phase)
        assertFalse(notReady.isDownloading)
        assertEquals(UmlRootfsUi.PROGRESS_INDETERMINATE, notReady.progressPercent)
        assertNull(notReady.failureReason)

        val downloading = UmlRootfsUi.from(
            model(UmlRootfsCardState.Phase.DOWNLOADING, percent = 42),
            true,
        )
        assertEquals(UmlRootfsPhase.Downloading, downloading.phase)
        assertTrue(downloading.isDownloading)
        assertEquals(42, downloading.progressPercent)
        assertTrue(downloading.compileBridgeWired)

        val connecting = UmlRootfsUi.from(
            model(
                UmlRootfsCardState.Phase.DOWNLOADING,
                percent = UmlRootfsCardState.CardModel.PROGRESS_INDETERMINATE,
            ),
            true,
        )
        assertEquals(UmlRootfsUi.PROGRESS_INDETERMINATE, connecting.progressPercent)

        val ready = UmlRootfsUi.from(
            model(
                UmlRootfsCardState.Phase.READY,
                entries = 128,
                verified = true,
            ),
            true,
        )
        assertEquals(UmlRootfsPhase.Ready, ready.phase)
        assertEquals(128, ready.extractedEntries)
        assertTrue(ready.alreadyVerified)

        val failed = UmlRootfsUi.from(
            model(
                UmlRootfsCardState.Phase.FAILED,
                reason = "uml-rootfs-checksum-mismatch",
            ),
            false,
        )
        assertEquals(UmlRootfsPhase.Failed, failed.phase)
        assertEquals("uml-rootfs-checksum-mismatch", failed.failureReason)
    }

    @Test
    fun stateCarriesTheUmlPhaseAndFlagFacts() {
        val catalog = DistroCatalogRegistry.createDefault()
        val state = DistrosUiState.from(
            catalog = catalog,
            umlModel = model(UmlRootfsCardState.Phase.READY, entries = 64),
            compileBridgeWired = true,
        )

        assertEquals(7, state.distros.size)
        assertEquals(UmlRootfsPhase.Ready, state.uml?.phase)
        assertEquals(64, state.uml?.extractedEntries)
        assertTrue(state.uml?.compileBridgeWired == true)
    }

    @Test
    fun emptyCatalogueStaysRenderable() {
        val state = DistrosUiState(distros = emptyList())
        assertTrue(state.isEmpty)
        assertNull(state.uml)
    }

    @Test
    fun rootfsSizesReadCompactly() {
        assertEquals("0.0 MB", formatDistroSize(0L))
        assertEquals("3.5 MB", formatDistroSize((3.5 * 1024 * 1024).toLong()))
        assertEquals("42 MB", formatDistroSize(42L * 1024L * 1024L))
        assertEquals("1.5 GB", formatDistroSize((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    private fun model(
        phase: UmlRootfsCardState.Phase,
        percent: Int = UmlRootfsCardState.CardModel.PROGRESS_INDETERMINATE,
        reason: String? = null,
        entries: Int = 0,
        verified: Boolean = false,
    ): UmlRootfsCardState.CardModel = UmlRootfsCardState.CardModel(
        phase,
        percent,
        reason,
        entries,
        verified,
    )
}
