package io.github.nyzeep.orchestella.runtime.fastlane.storage;

import java.util.Objects;

/** One validated tar entry. */
public final class TarEntry {
    public enum Type {
        FILE,
        DIRECTORY
    }

    private final String path;
    private final Type type;
    private final long size;
    private final long modifiedTime;

    TarEntry(String path, Type type, long size, long modifiedTime) {
        this.path = Objects.requireNonNull(path, "path");
        this.type = Objects.requireNonNull(type, "type");
        this.size = size;
        this.modifiedTime = modifiedTime;
    }

    public String path() {
        return path;
    }

    public Type type() {
        return type;
    }

    public long size() {
        return size;
    }

    public long modifiedTime() {
        return modifiedTime;
    }
}
