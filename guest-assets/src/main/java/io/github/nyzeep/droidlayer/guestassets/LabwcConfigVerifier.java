package io.github.nyzeep.droidlayer.guestassets;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/**
 * Verifier for desktop configurations: labwc, sfwbar, foot, and weston.
 */
public final class LabwcConfigVerifier {

    private LabwcConfigVerifier() {}

    public static int verifyAll(Path desktopDir) {
        return verifyAll(desktopDir, new PrintWriter(System.err, true));
    }

    public static int verifyAll(Path desktopDir, PrintWriter errorOutput) {
        if (desktopDir == null || !Files.isDirectory(desktopDir)) {
            errorOutput.println("Desktop configuration directory does not exist: " + desktopDir);
            return 1;
        }

        try {
            // 1. Verify labwc/rc.xml
            Path rcXml = desktopDir.resolve("labwc").resolve("rc.xml");
            if (!Files.exists(rcXml)) {
                errorOutput.println("Missing required config: " + rcXml);
                return 1;
            }
            byte[] rcBytes = Files.readAllBytes(rcXml);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document rcDoc = builder.parse(new ByteArrayInputStream(rcBytes));

            NodeList actions = rcDoc.getElementsByTagName("action");
            boolean hasClose = false;
            boolean hasMaximize = false;
            boolean hasIconify = false;
            for (int i = 0; i < actions.getLength(); i++) {
                String name = actions.item(i).getAttributes().getNamedItem("name") != null
                        ? actions.item(i).getAttributes().getNamedItem("name").getNodeValue()
                        : "";
                if ("Close".equalsIgnoreCase(name)) {
                    hasClose = true;
                } else if ("ToggleMaximize".equalsIgnoreCase(name) || "Maximize".equalsIgnoreCase(name)) {
                    hasMaximize = true;
                } else if ("Iconify".equalsIgnoreCase(name) || "Minimize".equalsIgnoreCase(name)) {
                    hasIconify = true;
                }
            }
            if (!hasClose || !hasMaximize || !hasIconify) {
                errorOutput.println("rc.xml is missing essential window control actions (Close, Maximize, Iconify)");
                return 1;
            }

            // 2. Verify labwc/menu.xml
            Path menuXml = desktopDir.resolve("labwc").resolve("menu.xml");
            if (!Files.exists(menuXml)) {
                errorOutput.println("Missing required config: " + menuXml);
                return 1;
            }
            byte[] menuBytes = Files.readAllBytes(menuXml);
            Document menuDoc = builder.parse(new ByteArrayInputStream(menuBytes));
            if (menuDoc.getElementsByTagName("menu").getLength() == 0) {
                errorOutput.println("menu.xml must declare at least one menu");
                return 1;
            }

            // 3. Verify labwc/autostart
            Path autostart = desktopDir.resolve("labwc").resolve("autostart");
            if (!Files.exists(autostart)) {
                errorOutput.println("Missing required autostart: " + autostart);
                return 1;
            }

            // 4. Verify sfwbar/sfwbar.config
            Path sfwbar = desktopDir.resolve("sfwbar").resolve("sfwbar.config");
            if (!Files.exists(sfwbar)) {
                errorOutput.println("Missing required sfwbar config: " + sfwbar);
                return 1;
            }

            // 5. Verify foot/foot.ini
            Path foot = desktopDir.resolve("foot").resolve("foot.ini");
            if (!Files.exists(foot)) {
                errorOutput.println("Missing required foot config: " + foot);
                return 1;
            }

            // 6. Verify weston/weston.ini
            Path weston = desktopDir.resolve("weston").resolve("weston.ini");
            if (!Files.exists(weston)) {
                errorOutput.println("Missing required weston config: " + weston);
                return 1;
            }

            return 0;
        } catch (Exception ex) {
            errorOutput.println("Error verifying desktop configurations: " + ex.getMessage());
            return 1;
        }
    }
}
