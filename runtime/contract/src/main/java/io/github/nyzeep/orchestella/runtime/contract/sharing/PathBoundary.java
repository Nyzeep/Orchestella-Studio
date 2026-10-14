package io.github.nyzeep.orchestella.runtime.contract.sharing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.Locale;

/** Internal fail-closed boundaries. Walks ancestor components, never enumerates a source tree. */
final class PathBoundary {
    private PathBoundary() { }

    static Path directory(Path path) throws IOException {
        if (path == null || !path.isAbsolute() || !path.equals(path.normalize())) {
            throw new SecurityException("An absolute normalized owned directory is required");
        }
        Path cursor = path.getRoot();
        for (Path part : path) {
            cursor = cursor.resolve(part);
            BasicFileAttributes attributes = Files.readAttributes(cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || Files.isSymbolicLink(cursor)
                    || !cursor.toRealPath().equals(cursor)) {
                throw new SecurityException("Linked or non-directory path component");
            }
        }
        return path;
    }

    static void regularFile(Path file) throws IOException {
        directory(file.getParent());
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || Files.isSymbolicLink(file)
                || !file.toRealPath().equals(file)) {
            throw new SecurityException("Only a regular unlinked file is allowed");
        }
    }

    static String name(String name, int maxBytes) {
        if (name == null || name.isEmpty() || !Normalizer.isNormalized(name, Normalizer.Form.NFC)
                || name.getBytes(StandardCharsets.UTF_8).length > maxBytes
                || name.startsWith(".") || name.endsWith(".") || name.endsWith(" ")
                || name.matches(".*[\\\\/:%<>\"|?*].*")
                || name.codePoints().anyMatch(c -> Character.isISOControl(c)
                    || Character.getType(c) == Character.FORMAT || Character.getType(c) == Character.SURROGATE)) {
            throw new SecurityException("Unsafe selected file name");
        }
        String stem = name.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        if (stem.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) {
            throw new SecurityException("Reserved selected file name");
        }
        return name;
    }

    static String decode(String raw, boolean allowEncodedSlash) {
        if (raw == null) { throw new SecurityException("URI path is required"); }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < raw.length();) {
            if (raw.charAt(i) != '%') { result.append(raw.charAt(i++)); continue; }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (i < raw.length() && raw.charAt(i) == '%') {
                if (i + 2 >= raw.length()) { throw new SecurityException("Malformed URI encoding"); }
                final int value;
                try { value = Integer.parseInt(raw.substring(i + 1, i + 3), 16); }
                catch (NumberFormatException malformed) { throw new SecurityException("Malformed URI encoding"); }
                if (value == '%' || value == '\\' || (!allowEncodedSlash && value == '/')) {
                    throw new SecurityException("Encoded URI escape");
                }
                bytes.write(value);
                i += 3;
            }
            try {
                result.append(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())));
            } catch (CharacterCodingException invalid) { throw new SecurityException("Invalid UTF-8 URI"); }
        }
        String decoded = result.toString();
        if (decoded.indexOf('\\') >= 0 || decoded.indexOf('%') >= 0
                || decoded.codePoints().anyMatch(c -> Character.isISOControl(c)
                    || Character.getType(c) == Character.FORMAT || Character.getType(c) == Character.SURROGATE)) {
            throw new SecurityException("Unsafe URI path");
        }
        for (String part : decoded.split("/", -1)) {
            if (".".equals(part) || "..".equals(part)) { throw new SecurityException("URI traversal"); }
        }
        return decoded;
    }
}
