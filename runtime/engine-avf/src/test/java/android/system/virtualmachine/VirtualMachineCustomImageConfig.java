package android.system.virtualmachine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Test-only stand-in for the hidden Custom VM config. The Builder records
 * every reflective call the production adapter makes so tests can assert the
 * exact VM assembly (kernel, initramfs, raw root disk, memory, non-protected).
 */
public class VirtualMachineCustomImageConfig extends VirtualMachineConfig {
    private final String kernelPath;
    private final String initrdPath;
    private final List<Disk> disks;
    private final int memoryMbs;
    private final long memoryBytes;
    private final boolean protectedVm;
    private final boolean networkSupported;

    private VirtualMachineCustomImageConfig(Builder builder) {
        this.kernelPath = builder.kernelPath;
        this.initrdPath = builder.initrdPath;
        this.disks = Collections.unmodifiableList(new ArrayList<>(builder.disks));
        this.memoryMbs = builder.memoryMbs;
        this.memoryBytes = builder.memoryBytes;
        this.protectedVm = builder.protectedVm;
        this.networkSupported = builder.networkSupported;
    }

    public String kernelPath() {
        return kernelPath;
    }

    public String initrdPath() {
        return initrdPath;
    }

    public List<Disk> disks() {
        return disks;
    }

    public int memoryMbs() {
        return memoryMbs;
    }

    public long memoryBytes() {
        return memoryBytes;
    }

    public boolean isProtectedVm() {
        return protectedVm;
    }

    public boolean isNetworkSupported() {
        return networkSupported;
    }

    /** One recorded disk attachment. */
    public static final class Disk {
        private final String path;
        private final boolean writable;

        public Disk(String path, boolean writable) {
            this.path = path;
            this.writable = writable;
        }

        public String path() {
            return path;
        }

        public boolean writable() {
            return writable;
        }
    }

    /** Mirror of the hidden builder surface the reflective adapter drives. */
    public static final class Builder {
        private String kernelPath;
        private String initrdPath;
        private final List<Disk> disks = new ArrayList<>();
        private int memoryMbs = -1;
        private long memoryBytes = -1;
        private boolean protectedVm;
        private boolean networkSupported = true;

        public Builder setKernelPath(String path) {
            this.kernelPath = path;
            return this;
        }

        public Builder setInitrdPath(String path) {
            this.initrdPath = path;
            return this;
        }

        public Builder addDisk(String path, boolean writable) {
            this.disks.add(new Disk(path, writable));
            return this;
        }

        public Builder setMemoryMbs(int memoryMbs) {
            this.memoryMbs = memoryMbs;
            return this;
        }

        public Builder setMemoryBytes(long memoryBytes) {
            this.memoryBytes = memoryBytes;
            return this;
        }

        public Builder setProtected(boolean protectedVm) {
            this.protectedVm = protectedVm;
            return this;
        }

        public Builder setNetworkSupported(boolean networkSupported) {
            this.networkSupported = networkSupported;
            return this;
        }

        public VirtualMachineCustomImageConfig build() {
            return new VirtualMachineCustomImageConfig(this);
        }
    }
}
