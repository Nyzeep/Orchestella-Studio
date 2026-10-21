package io.github.nyzeep.orchestella.runtime.fastlane.storage;

import java.util.List;
import java.util.Locale;

/** Conservative deny list for names that must never leave the encrypted store. */
public final class ExportPolicy {
    private static final List<String> EXACT = List.of(
            "id_rsa", "id_ed25519", "id_ecdsa", "id_dsa", "token",
            ".fastlane-token", ".netrc", ".git-credentials");
    private static final List<String> PREFIXES = List.of("id_rsa", "id_ed25519");
    private static final List<String> SUFFIXES = List.of(
            ".sock", ".pid", ".pem", ".key", ".p12", ".jks", ".keystore", ".token");

    private static final ExportPolicy STANDARD = new ExportPolicy();

    public static ExportPolicy standard() {
        return STANDARD;
    }

    public boolean isDenied(String path) {
        final String name = StoragePaths.baseName(path).toLowerCase(Locale.ROOT);
        if (EXACT.contains(name)) {
            return true;
        }
        for (String prefix : PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        for (String suffix : SUFFIXES) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
