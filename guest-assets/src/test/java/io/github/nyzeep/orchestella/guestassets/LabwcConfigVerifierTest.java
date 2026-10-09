package io.github.nyzeep.orchestella.guestassets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

final class LabwcConfigVerifierTest {

    private static Path getDesktopDir() {
        String projectDir = System.getProperty("guestAssetsProjectDir");
        if (projectDir != null && !projectDir.isBlank()) {
            return Path.of(projectDir).resolve("scripts").resolve("desktop");
        }
        return Path.of("scripts").resolve("desktop");
    }

    @Test
    void rcXmlIsValidAndContainsEssentialTouchWindowControls() throws Exception {
        Path rcXmlPath = getDesktopDir().resolve("labwc").resolve("rc.xml");
        assertTrue(Files.exists(rcXmlPath), "rc.xml must exist at scripts/desktop/labwc/rc.xml");

        byte[] bytes = Files.readAllBytes(rcXmlPath);
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertFalse(content.contains("\r"), "rc.xml must use LF line endings only");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new ByteArrayInputStream(bytes));
        assertNotNull(doc, "rc.xml must parse as valid XML");

        NodeList actionNodes = doc.getElementsByTagName("action");
        assertTrue(actionNodes.getLength() > 0, "rc.xml must declare action nodes");

        boolean hasClose = false;
        boolean hasMaximize = false;
        boolean hasIconify = false;

        for (int i = 0; i < actionNodes.getLength(); i++) {
            String name = actionNodes.item(i).getAttributes().getNamedItem("name") != null
                    ? actionNodes.item(i).getAttributes().getNamedItem("name").getNodeValue()
                    : "";
            if ("Close".equalsIgnoreCase(name)) {
                hasClose = true;
            } else if ("ToggleMaximize".equalsIgnoreCase(name) || "Maximize".equalsIgnoreCase(name)) {
                hasMaximize = true;
            } else if ("Iconify".equalsIgnoreCase(name) || "Minimize".equalsIgnoreCase(name)) {
                hasIconify = true;
            }
        }

        assertTrue(hasClose, "rc.xml must declare Close action");
        assertTrue(hasMaximize, "rc.xml must declare ToggleMaximize action");
        assertTrue(hasIconify, "rc.xml must declare Iconify (minimize) action");

        NodeList mousebindNodes = doc.getElementsByTagName("mousebind");
        assertTrue(mousebindNodes.getLength() > 0, "rc.xml must declare mousebind elements for touch window control");
    }

    @Test
    void menuXmlIsValidAndContainsMinimalLaunchers() throws Exception {
        Path menuXmlPath = getDesktopDir().resolve("labwc").resolve("menu.xml");
        assertTrue(Files.exists(menuXmlPath), "menu.xml must exist at scripts/desktop/labwc/menu.xml");

        byte[] bytes = Files.readAllBytes(menuXmlPath);
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertFalse(content.contains("\r"), "menu.xml must use LF line endings only");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new ByteArrayInputStream(bytes));
        assertNotNull(doc, "menu.xml must parse as valid XML");

        NodeList menuNodes = doc.getElementsByTagName("menu");
        assertTrue(menuNodes.getLength() > 0, "menu.xml must declare at least one menu");

        NodeList itemNodes = doc.getElementsByTagName("item");
        assertTrue(itemNodes.getLength() > 0, "menu.xml must declare menu items");

        assertTrue(content.contains("foot"), "menu.xml must provide launcher entry for foot terminal");
    }

    @Test
    void autostartScriptExistsAndLaunchesEssentialServices() throws IOException {
        Path autostartPath = getDesktopDir().resolve("labwc").resolve("autostart");
        assertTrue(Files.exists(autostartPath), "autostart must exist at scripts/desktop/labwc/autostart");

        String content = Files.readString(autostartPath, StandardCharsets.UTF_8);
        assertFalse(content.contains("\r"), "autostart must use LF line endings only");
        assertTrue(content.contains("sfwbar"), "autostart must launch sfwbar status bar");
        assertTrue(content.contains("pulseaudio"), "autostart must launch pulseaudio audio sink daemon");
        assertTrue(content.contains("clipboard") || content.contains("wl-paste"),
                "autostart must launch clipboard agent");
    }

    @Test
    void sfwbarConfigExistsAndDefinesTaskbarAndLauncher() throws IOException {
        Path sfwbarConfig = getDesktopDir().resolve("sfwbar").resolve("sfwbar.config");
        assertTrue(Files.exists(sfwbarConfig), "sfwbar.config must exist at scripts/desktop/sfwbar/sfwbar.config");

        String content = Files.readString(sfwbarConfig, StandardCharsets.UTF_8);
        assertFalse(content.contains("\r"), "sfwbar.config must use LF line endings only");
        assertTrue(content.toLowerCase().contains("taskbar") || content.toLowerCase().contains("grid"),
                "sfwbar.config must define taskbar/window grid");
        assertTrue(content.toLowerCase().contains("menu") || content.toLowerCase().contains("launcher"),
                "sfwbar.config must define launcher menu");
    }

    @Test
    void footConfigExistsAndConfiguresCjkFonts() throws IOException {
        Path footConfig = getDesktopDir().resolve("foot").resolve("foot.ini");
        assertTrue(Files.exists(footConfig), "foot.ini must exist at scripts/desktop/foot/foot.ini");

        String content = Files.readString(footConfig, StandardCharsets.UTF_8);
        assertFalse(content.contains("\r"), "foot.ini must use LF line endings only");
        assertTrue(content.contains("font="), "foot.ini must define font setting");
        assertTrue(content.toLowerCase().contains("cjk") || content.toLowerCase().contains("noto"),
                "foot.ini must configure CJK font fallback");
    }

    @Test
    void westonConfigExistsForDiagnosticBaseline() throws IOException {
        Path westonConfig = getDesktopDir().resolve("weston").resolve("weston.ini");
        assertTrue(Files.exists(westonConfig), "weston.ini must exist at scripts/desktop/weston/weston.ini");

        String content = Files.readString(westonConfig, StandardCharsets.UTF_8);
        assertFalse(content.contains("\r"), "weston.ini must use LF line endings only");
        assertTrue(content.contains("[core]"), "weston.ini must declare [core] section");
    }

    @Test
    void verifierUtilityValidatesAllDesktopConfigs() {
        int exitCode = LabwcConfigVerifier.verifyAll(getDesktopDir());
        assertEquals(0, exitCode, "LabwcConfigVerifier must validate all desktop configs successfully");
    }
}
