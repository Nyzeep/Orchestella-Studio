package io.github.nyzeep.orchestella.runtime.fastlane.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Streaming ustar/PAX reader. Rejects absolute/traversal paths, symlinks,
 * hardlinks, devices, fifos, and truncated entries. A tar without the two zero
 * terminator blocks is rejected rather than treated as a short archive.
 */
public final class TarReader {
    private static final int BLOCK = 512;
    private static final long MAX_ENTRY_BYTES = 16L * 1024 * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 100_000;
    private static final int MAX_METADATA_BYTES = 1024 * 1024;

    private final InputStream input;
    private long remaining;
    private long entrySize;
    private long totalBytes;
    private int entryCount;
    private boolean finished;

    public TarReader(InputStream input) {
        this.input = input;
    }

    /** Advances to the next entry; null after the terminator. */
    public TarEntry next() throws StorageException {
        if (finished) {
            return null;
        }
        finishEntry();
        String paxPath = null;
        long paxSize = -1;
        long paxMtime = -1;
        String longName = null;
        for (;;) {
            final byte[] header = new byte[BLOCK];
            if (!readFully(header, 0, BLOCK)) {
                throw new StorageException(StorageFailure.IO_FAILED, "truncated tar header");
            }
            if (isZero(header)) {
                final byte[] second = new byte[BLOCK];
                if (!readFully(second, 0, BLOCK) || !isZero(second)) {
                    throw new StorageException(StorageFailure.IO_FAILED, "malformed tar terminator");
                }
                finished = true;
                return null;
            }
            verifyChecksum(header);
            final long size = parseOctal(header, 124, 12);
            if (size < 0 || size > MAX_ENTRY_BYTES) {
                throw new StorageException(StorageFailure.LIMIT_EXCEEDED, "tar entry too large");
            }
            final char type = (char) (header[156] & 0xFF);
            if (type == 'x' || type == 'L') {
                if (size > MAX_METADATA_BYTES) {
                    throw new StorageException(
                            StorageFailure.LIMIT_EXCEEDED, "tar metadata too large");
                }
                final byte[] data = new byte[(int) size];
                if (!readFully(data, 0, data.length)) {
                    throw new StorageException(StorageFailure.IO_FAILED, "truncated tar metadata");
                }
                skip(padding(size));
                if (type == 'x') {
                    final Map<String, String> pax = parsePax(data);
                    if (pax.containsKey("path")) {
                        paxPath = pax.get("path");
                    }
                    if (pax.containsKey("size")) {
                        try {
                            paxSize = Long.parseLong(pax.get("size"));
                        } catch (NumberFormatException failure) {
                            throw new StorageException(
                                    StorageFailure.MANIFEST_INVALID, "malformed pax size");
                        }
                    }
                    if (pax.containsKey("mtime")) {
                        try {
                            paxMtime = Long.parseLong(pax.get("mtime"));
                        } catch (NumberFormatException failure) {
                            paxMtime = -1;
                        }
                    }
                } else {
                    longName = new String(data, StandardCharsets.UTF_8);
                }
                continue;
            }

            String path = paxPath;
            if (path == null) {
                final String name = readString(header, 0, 100);
                final String prefix = readString(header, 345, 155);
                path = prefix.isEmpty() ? name : prefix + "/" + name;
            }
            if (longName != null && !longName.isEmpty()) {
                path = longName;
            }
            long effectiveSize = paxSize >= 0 ? paxSize : size;
            if (effectiveSize < 0 || effectiveSize > MAX_ENTRY_BYTES) {
                throw new StorageException(StorageFailure.LIMIT_EXCEEDED, "tar entry too large");
            }
            final boolean directory = type == '5';
            if (directory) {
                while (path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
            }
            if (!StoragePaths.isSafeRelative(path)) {
                throw new StorageException(StorageFailure.PATH_REJECTED, "unsafe tar path");
            }
            if (type == '1' || type == '2' || type == '3' || type == '4' || type == '6') {
                throw new StorageException(
                        StorageFailure.PATH_REJECTED, "links and devices are not allowed");
            }
            if (!directory && type != '0' && type != 0) {
                throw new StorageException(
                        StorageFailure.UNSUPPORTED_FORMAT, "unsupported tar entry type");
            }
            if (directory && effectiveSize != 0) {
                skip(effectiveSize + padding(effectiveSize));
                effectiveSize = 0;
            }
            remaining = effectiveSize;
            entrySize = effectiveSize;
            ++entryCount;
            if (entryCount > MAX_ENTRIES) {
                throw new StorageException(StorageFailure.LIMIT_EXCEEDED, "too many tar entries");
            }
            totalBytes += effectiveSize;
            if (totalBytes > MAX_TOTAL_BYTES) {
                throw new StorageException(StorageFailure.LIMIT_EXCEEDED, "tar is too large");
            }
            long modified = paxMtime >= 0 ? paxMtime : parseOctal(header, 136, 12);
            if (modified < 0) {
                modified = 0;
            }
            return new TarEntry(
                    path,
                    directory ? TarEntry.Type.DIRECTORY : TarEntry.Type.FILE,
                    effectiveSize,
                    modified);
        }
    }

    /** Reads the current entry's data; -1 at the end of the entry. */
    public int read(byte[] buffer, int offset, int length) throws StorageException {
        if (remaining == 0) {
            return -1;
        }
        final int chunk = (int) Math.min(remaining, length);
        final int read = readSome(buffer, offset, chunk);
        if (read < 0) {
            throw new StorageException(StorageFailure.IO_FAILED, "truncated tar entry");
        }
        remaining -= read;
        return read;
    }

    public long remaining() {
        return remaining;
    }

    private void finishEntry() throws StorageException {
        if (entrySize == 0) {
            return;
        }
        if (remaining > 0) {
            skip(remaining);
            remaining = 0;
        }
        skip(padding(entrySize));
        entrySize = 0;
    }

    private int readSome(byte[] buffer, int offset, int length) throws StorageException {
        try {
            return input.read(buffer, offset, length);
        } catch (IOException failure) {
            throw StorageException.io(failure);
        }
    }

    private boolean readFully(byte[] buffer, int offset, int length) throws StorageException {
        int position = offset;
        while (position < offset + length) {
            final int read = readSome(buffer, position, offset + length - position);
            if (read < 0) {
                return false;
            }
            position += read;
        }
        return true;
    }

    private void skip(long length) throws StorageException {
        final byte[] buffer = new byte[8192];
        long remainingBytes = length;
        while (remainingBytes > 0) {
            final int chunk = (int) Math.min(remainingBytes, buffer.length);
            if (!readFully(buffer, 0, chunk)) {
                throw new StorageException(StorageFailure.IO_FAILED, "truncated tar data");
            }
            remainingBytes -= chunk;
        }
    }

    private static long padding(long size) {
        return (BLOCK - (size % BLOCK)) % BLOCK;
    }

    private static boolean isZero(byte[] block) {
        for (byte value : block) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static void verifyChecksum(byte[] header) throws StorageException {
        final long stored = parseOctal(header, 148, 8);
        if (stored < 0) {
            throw new StorageException(StorageFailure.IO_FAILED, "malformed tar checksum");
        }
        long computed = 0;
        for (int index = 0; index < BLOCK; index++) {
            computed += (index >= 148 && index < 156) ? ' ' : (header[index] & 0xFF);
        }
        if (computed != stored) {
            throw new StorageException(StorageFailure.IO_FAILED, "tar checksum mismatch");
        }
    }

    static long parseOctal(byte[] data, int offset, int length) {
        int index = offset;
        while (index < offset + length && (data[index] == ' ' || data[index] == 0)) {
            index++;
        }
        long value = 0;
        boolean sawDigit = false;
        while (index < offset + length) {
            final byte digit = data[index++];
            if (digit == 0 || digit == ' ') {
                break;
            }
            if (digit < '0' || digit > '7') {
                return -1;
            }
            value = (value << 3) | (digit - '0');
            sawDigit = true;
        }
        return sawDigit ? value : -1;
    }

    private static String readString(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static Map<String, String> parsePax(byte[] data) throws StorageException {
        final Map<String, String> values = new HashMap<>();
        int offset = 0;
        while (offset < data.length) {
            int space = offset;
            while (space < data.length && data[space] != ' ') {
                space++;
            }
            if (space == offset || space >= data.length) {
                throw new StorageException(StorageFailure.MANIFEST_INVALID, "malformed pax header");
            }
            int length = 0;
            for (int index = offset; index < space; index++) {
                final byte digit = data[index];
                if (digit < '0' || digit > '9') {
                    throw new StorageException(
                            StorageFailure.MANIFEST_INVALID, "malformed pax length");
                }
                length = length * 10 + (digit - '0');
            }
            if (length <= 0 || offset + length > data.length) {
                throw new StorageException(StorageFailure.MANIFEST_INVALID, "malformed pax record");
            }
            int end = offset + length;
            if (end > space + 1 && data[end - 1] == '\n') {
                end--;
            }
            final String record =
                    new String(data, space + 1, end - space - 1, StandardCharsets.UTF_8);
            final int equals = record.indexOf('=');
            if (equals > 0) {
                values.put(record.substring(0, equals), record.substring(equals + 1));
            }
            offset += length;
        }
        return values;
    }
}
