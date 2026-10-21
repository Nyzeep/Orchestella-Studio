package io.github.nyzeep.orchestella.runtime.fastlane.storage;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Streaming ustar/PAX writer used to canonicalise exports and imports. Paths
 * that do not fit ustar's 100+155 byte split, or that are non-ASCII, get a PAX
 * path record.
 */
public final class TarWriter implements Closeable {
    private static final int BLOCK = 512;

    private final OutputStream output;
    private boolean finished;

    public TarWriter(OutputStream output) {
        this.output = output;
    }

    public void addDirectory(String path, int mode, long modifiedTime) throws StorageException {
        String directory = path;
        while (directory.endsWith("/")) {
            directory = directory.substring(0, directory.length() - 1);
        }
        if (!StoragePaths.isSafeRelative(directory)) {
            throw new StorageException(StorageFailure.PATH_REJECTED, "unsafe tar path");
        }
        emitHeader(directory + "/", '5', 0, mode, modifiedTime);
    }

    public void addFile(String path, InputStream data, long size, int mode, long modifiedTime)
            throws StorageException {
        if (!StoragePaths.isSafeRelative(path)) {
            throw new StorageException(StorageFailure.PATH_REJECTED, "unsafe tar path");
        }
        emitHeader(path, '0', size, mode, modifiedTime);
        final byte[] buffer = new byte[16 * 1024];
        long remaining = size;
        while (remaining > 0) {
            final int read;
            try {
                read = data.read(buffer, 0, (int) Math.min(remaining, buffer.length));
            } catch (StorageIOException failure) {
                throw failure.storage();
            } catch (IOException failure) {
                throw StorageException.io(failure);
            }
            if (read < 0) {
                throw new StorageException(
                        StorageFailure.IO_FAILED, "source ended before the declared size");
            }
            write(buffer, 0, read);
            remaining -= read;
        }
        writeZeros(padding(size));
    }

    public void finish() throws StorageException {
        if (finished) {
            return;
        }
        writeZeros(2L * BLOCK);
        try {
            output.flush();
        } catch (IOException failure) {
            throw StorageException.io(failure);
        }
        finished = true;
    }

    @Override
    public void close() throws IOException {
        try {
            finish();
        } catch (StorageException failure) {
            throw new IOException(failure);
        }
        output.close();
    }

    private void emitHeader(String path, char type, long size, int mode, long modifiedTime)
            throws StorageException {
        final byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
        if (needsPax(pathBytes)) {
            emitPaxPath(path, pathBytes);
            writeHeader(fallbackName(pathBytes), new byte[0], type, size, mode, modifiedTime);
            return;
        }
        final String[] split = splitUstar(pathBytes);
        writeHeader(split[1].getBytes(StandardCharsets.UTF_8),
                split[0].getBytes(StandardCharsets.UTF_8), type, size, mode, modifiedTime);
    }

    private static boolean needsPax(byte[] path) {
        if (path.length > 100) {
            return true;
        }
        for (byte value : path) {
            if ((value & 0x80) != 0) {
                return true;
            }
        }
        return false;
    }

    private void emitPaxPath(String path, byte[] pathBytes) throws StorageException {
        int length = pathBytes.length + 7;
        for (;;) {
            final int digits = Integer.toString(length).length();
            final int computed = digits + 7 + pathBytes.length;
            if (computed == length) {
                break;
            }
            length = computed;
        }
        final byte[] record = (length + " path=" + path + "\n").getBytes(StandardCharsets.UTF_8);
        final String headerName = "PaxHeaders/" + StoragePaths.baseName(path);
        final byte[] headerNameBytes = headerName.getBytes(StandardCharsets.UTF_8);
        writeHeader(headerNameBytes.length > 100
                        ? Arrays.copyOf(headerNameBytes, 100)
                        : headerNameBytes,
                new byte[0], 'x', record.length, 0644, 0);
        write(record, 0, record.length);
        writeZeros(padding(record.length));
    }

    private static String[] splitUstar(byte[] path) {
        if (path.length <= 100) {
            return new String[] {"", new String(path, StandardCharsets.UTF_8)};
        }
        for (int index = path.length - 1; index > 0; index--) {
            if (path[index] != '/') {
                continue;
            }
            final int prefixLength = index;
            final int nameLength = path.length - index - 1;
            if (prefixLength <= 155 && nameLength <= 100 && nameLength > 0) {
                return new String[] {
                    new String(path, 0, prefixLength, StandardCharsets.UTF_8),
                    new String(path, index + 1, nameLength, StandardCharsets.UTF_8)
                };
            }
        }
        return new String[] {"", new String(fallbackName(path), StandardCharsets.UTF_8)};
    }

    private static byte[] fallbackName(byte[] path) {
        if (path.length <= 100) {
            return path;
        }
        int start = path.length - 100;
        while (start < path.length && path[start] == '/') {
            start++;
        }
        return Arrays.copyOfRange(path, start, path.length);
    }

    private void writeHeader(
            byte[] name,
            byte[] prefix,
            char type,
            long size,
            int mode,
            long modifiedTime) throws StorageException {
        final byte[] header = new byte[BLOCK];
        System.arraycopy(name, 0, header, 0, Math.min(name.length, 100));
        putOctal(header, 100, 8, mode & 07777);
        putOctal(header, 108, 8, 0);
        putOctal(header, 116, 8, 0);
        putOctal(header, 124, 12, size);
        putOctal(header, 136, 12, Math.max(0, modifiedTime));
        for (int index = 148; index < 156; index++) {
            header[index] = ' ';
        }
        header[156] = (byte) type;
        header[257] = 'u';
        header[258] = 's';
        header[259] = 't';
        header[260] = 'a';
        header[261] = 'r';
        header[263] = '0';
        header[264] = '0';
        System.arraycopy(prefix, 0, header, 345, Math.min(prefix.length, 155));
        long checksum = 0;
        for (byte value : header) {
            checksum += value & 0xFF;
        }
        putOctal(header, 148, 7, checksum);
        header[155] = ' ';
        write(header, 0, header.length);
    }

    private static void putOctal(byte[] destination, int offset, int length, long value) {
        for (int index = offset + length - 2; index >= offset; index--) {
            destination[index] = (byte) ('0' + (value & 07));
            value >>= 3;
        }
        destination[offset + length - 1] = 0;
    }

    private void writeZeros(long count) throws StorageException {
        final byte[] zeros = new byte[BLOCK];
        long remaining = count;
        while (remaining > 0) {
            final int chunk = (int) Math.min(remaining, zeros.length);
            write(zeros, 0, chunk);
            remaining -= chunk;
        }
    }

    private void write(byte[] data, int offset, int length) throws StorageException {
        try {
            output.write(data, offset, length);
        } catch (IOException failure) {
            throw StorageException.io(failure);
        }
    }

    private static long padding(long size) {
        return (BLOCK - (size % BLOCK)) % BLOCK;
    }
}
