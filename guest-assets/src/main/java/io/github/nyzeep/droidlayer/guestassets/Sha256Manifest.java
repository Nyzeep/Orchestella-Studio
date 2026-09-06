package io.github.nyzeep.droidlayer.guestassets;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class Sha256Manifest {
    private Sha256Manifest() {}

    public static void generate(Path baseDirectory, List<Path> artifacts, Path manifest)
            throws IOException {
        Path normalizedBase = baseDirectory.toAbsolutePath().normalize();
        Map<String, Path> artifactsByName = new TreeMap<>();
        for (Path artifact : artifacts) {
            Path normalizedArtifact = artifact.toAbsolutePath().normalize();
            if (!normalizedArtifact.startsWith(normalizedBase)) {
                throw new IllegalArgumentException("Artifact is outside the base directory: " + artifact);
            }
            String name = normalizedBase.relativize(normalizedArtifact).toString().replace('\\', '/');
            artifactsByName.put(name, normalizedArtifact);
        }

        StringBuilder contents = new StringBuilder();
        for (Map.Entry<String, Path> artifact : artifactsByName.entrySet()) {
            contents.append(sha256(artifact.getValue()))
                    .append("  ")
                    .append(artifact.getKey())
                    .append('\n');
        }
        Files.writeString(manifest, contents, StandardCharsets.UTF_8);
    }

    public static int verify(Path baseDirectory, Path manifest, PrintWriter diagnostics)
            throws IOException {
        Path normalizedBase = baseDirectory.toAbsolutePath().normalize();
        boolean matches = true;
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            if (line.length() < 67 || !line.substring(64, 66).equals("  ")) {
                throw new IOException("Malformed SHA256 manifest line: " + line);
            }
            String expectedDigest = line.substring(0, 64);
            String name = line.substring(66);
            Path artifact = normalizedBase.resolve(name).normalize();
            if (!artifact.startsWith(normalizedBase)) {
                throw new IOException("Manifest path escapes the base directory: " + name);
            }
            if (!Files.isRegularFile(artifact)) {
                diagnostics.printf("%s: missing%n", name);
                matches = false;
                continue;
            }
            if (!expectedDigest.equals(sha256(artifact))) {
                diagnostics.printf("%s: checksum mismatch%n", name);
                matches = false;
            }
        }
        return matches ? 0 : 1;
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("The JVM does not provide SHA-256", impossible);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }
}
