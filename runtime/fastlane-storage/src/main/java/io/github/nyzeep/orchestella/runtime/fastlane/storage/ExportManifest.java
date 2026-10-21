package io.github.nyzeep.orchestella.runtime.fastlane.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Deterministic manifest of one archive: one entry per regular file with path,
 * size, SHA-256, and mtime (epoch seconds). Entries are sorted by path.
 */
public final class ExportManifest {
    public static final int VERSION = 1;

    public static final class Entry {
        private final String path;
        private final long size;
        private final String sha256;
        private final long modifiedTime;

        public Entry(String path, long size, String sha256, long modifiedTime) {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(sha256, "sha256");
            if (!StoragePaths.isSafeRelative(path)) {
                throw new IllegalArgumentException("manifest path is unsafe");
            }
            if (size < 0 || modifiedTime < 0) {
                throw new IllegalArgumentException("negative size or mtime");
            }
            if (sha256.length() != 64 || !sha256.chars().allMatch(character ->
                    (character >= '0' && character <= '9')
                            || (character >= 'a' && character <= 'f'))) {
                throw new IllegalArgumentException("sha256 must be 64 lowercase hex characters");
            }
            this.path = path;
            this.size = size;
            this.sha256 = sha256;
            this.modifiedTime = modifiedTime;
        }

        public String path() {
            return path;
        }

        public long size() {
            return size;
        }

        public String sha256() {
            return sha256;
        }

        public long modifiedTime() {
            return modifiedTime;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Entry)) {
                return false;
            }
            final Entry entry = (Entry) other;
            return size == entry.size
                    && modifiedTime == entry.modifiedTime
                    && path.equals(entry.path)
                    && sha256.equals(entry.sha256);
        }

        @Override
        public int hashCode() {
            return Objects.hash(path, size, sha256, modifiedTime);
        }
    }

    private final Map<String, Entry> entries = new TreeMap<>();
    private long totalBytes;

    public void add(Entry entry) throws StorageException {
        Objects.requireNonNull(entry, "entry");
        if (entries.containsKey(entry.path())) {
            throw new StorageException(StorageFailure.MANIFEST_INVALID, "duplicate manifest path");
        }
        if (entry.size() > Long.MAX_VALUE - totalBytes) {
            throw new StorageException(StorageFailure.LIMIT_EXCEEDED, "manifest size overflow");
        }
        entries.put(entry.path(), entry);
        totalBytes += entry.size();
    }

    public List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries.values()));
    }

    public long totalBytes() {
        return totalBytes;
    }

    public String toJson() {
        final StringBuilder json = new StringBuilder();
        json.append("{\"version\":").append(VERSION).append(",\"entries\":[");
        boolean first = true;
        for (Entry entry : entries.values()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"path\":");
            appendString(json, entry.path());
            json.append(",\"size\":").append(entry.size());
            json.append(",\"sha256\":");
            appendString(json, entry.sha256());
            json.append(",\"mtime\":").append(entry.modifiedTime()).append('}');
        }
        json.append("],\"totalBytes\":").append(totalBytes()).append('}');
        return json.toString();
    }

    public static ExportManifest fromJson(String json) throws StorageException {
        return new Parser(json).parse();
    }

    private static void appendString(StringBuilder json, String value) {
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            switch (character) {
                case '"':
                    json.append("\\\"");
                    break;
                case '\\':
                    json.append("\\\\");
                    break;
                case '\n':
                    json.append("\\n");
                    break;
                case '\r':
                    json.append("\\r");
                    break;
                case '\t':
                    json.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        json.append(String.format("\\u%04x", (int) character));
                    } else {
                        json.append(character);
                    }
                    break;
            }
        }
        json.append('"');
    }

    private static final class Parser {
        private final String text;
        private int index;

        Parser(String text) throws StorageException {
            if (text == null) {
                throw malformed();
            }
            this.text = text;
        }

        ExportManifest parse() throws StorageException {
            final ExportManifest manifest = new ExportManifest();
            skipWhitespace();
            expect('{');
            boolean versionSeen = false;
            boolean entriesSeen = false;
            boolean totalSeen = false;
            long declaredTotal = -1;
            while (true) {
                skipWhitespace();
                if (peek() == '}') {
                    index++;
                    break;
                }
                final String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                switch (key) {
                    case "version": {
                        final long version = parseNumber();
                        if (version != VERSION) {
                            throw malformed();
                        }
                        versionSeen = true;
                        break;
                    }
                    case "entries": {
                        parseEntries(manifest);
                        entriesSeen = true;
                        break;
                    }
                    case "totalBytes": {
                        declaredTotal = parseNumber();
                        totalSeen = true;
                        break;
                    }
                    default:
                        throw malformed();
                }
                skipWhitespace();
                if (peek() == ',') {
                    index++;
                    continue;
                }
                if (peek() == '}') {
                    index++;
                    break;
                }
                throw malformed();
            }
            skipWhitespace();
            if (index != text.length() || !versionSeen || !entriesSeen || !totalSeen) {
                throw malformed();
            }
            if (declaredTotal != manifest.totalBytes()) {
                throw malformed();
            }
            return manifest;
        }

        private void parseEntries(ExportManifest manifest) throws StorageException {
            expect('[');
            skipWhitespace();
            if (peek() == ']') {
                index++;
                return;
            }
            while (true) {
                manifest.add(parseEntry());
                skipWhitespace();
                if (peek() == ',') {
                    index++;
                    skipWhitespace();
                    continue;
                }
                expect(']');
                return;
            }
        }

        private Entry parseEntry() throws StorageException {
            expect('{');
            String path = null;
            long size = -1;
            String sha256 = null;
            long mtime = -1;
            while (true) {
                skipWhitespace();
                if (peek() == '}') {
                    index++;
                    break;
                }
                final String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                switch (key) {
                    case "path":
                        path = parseString();
                        break;
                    case "size":
                        size = parseNumber();
                        break;
                    case "sha256":
                        sha256 = parseString();
                        break;
                    case "mtime":
                        mtime = parseNumber();
                        break;
                    default:
                        throw malformed();
                }
                skipWhitespace();
                if (peek() == ',') {
                    index++;
                    continue;
                }
                if (peek() == '}') {
                    index++;
                    break;
                }
                throw malformed();
            }
            if (path == null || sha256 == null || size < 0 || mtime < 0) {
                throw malformed();
            }
            try {
                return new Entry(path, size, sha256, mtime);
            } catch (IllegalArgumentException failure) {
                throw malformed();
            }
        }

        private String parseString() throws StorageException {
            expect('"');
            final StringBuilder value = new StringBuilder();
            while (true) {
                if (index >= text.length()) {
                    throw malformed();
                }
                final char character = text.charAt(index++);
                if (character == '"') {
                    return value.toString();
                }
                if (character != '\\') {
                    value.append(character);
                    continue;
                }
                if (index >= text.length()) {
                    throw malformed();
                }
                final char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"':
                        value.append('"');
                        break;
                    case '\\':
                        value.append('\\');
                        break;
                    case '/':
                        value.append('/');
                        break;
                    case 'b':
                        value.append('\b');
                        break;
                    case 'f':
                        value.append('\f');
                        break;
                    case 'n':
                        value.append('\n');
                        break;
                    case 'r':
                        value.append('\r');
                        break;
                    case 't':
                        value.append('\t');
                        break;
                    case 'u': {
                        if (index + 4 > text.length()) {
                            throw malformed();
                        }
                        int code = 0;
                        for (int digit = 0; digit < 4; digit++) {
                            final int valueOf = Character.digit(text.charAt(index++), 16);
                            if (valueOf < 0) {
                                throw malformed();
                            }
                            code = (code << 4) | valueOf;
                        }
                        value.append((char) code);
                        break;
                    }
                    default:
                        throw malformed();
                }
            }
        }

        private long parseNumber() throws StorageException {
            final int start = index;
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            if (start == index || index - start > 18) {
                throw malformed();
            }
            try {
                return Long.parseLong(text.substring(start, index));
            } catch (NumberFormatException failure) {
                throw malformed();
            }
        }

        private char peek() throws StorageException {
            if (index >= text.length()) {
                throw malformed();
            }
            return text.charAt(index);
        }

        private void expect(char expected) throws StorageException {
            if (peek() != expected) {
                throw malformed();
            }
            index++;
        }

        private void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        private static StorageException malformed() {
            return new StorageException(StorageFailure.MANIFEST_INVALID, "malformed manifest");
        }
    }
}
