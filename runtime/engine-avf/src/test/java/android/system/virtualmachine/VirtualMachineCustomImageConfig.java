package android.system.virtualmachine;

import java.util.ArrayList;
import java.util.List;

/** Official custom image shape; it does not inherit VirtualMachineConfig. */
public final class VirtualMachineCustomImageConfig {
    private final String name;
    private final String kernelPath;
    private final String initrdPath;
    private final List<String> params;
    private final List<Disk> disks;
    private final boolean network;
    private final String bootloaderPath;

    private VirtualMachineCustomImageConfig(Builder builder) {
        name = builder.name;
        kernelPath = builder.kernelPath;
        initrdPath = builder.initrdPath;
        params = List.copyOf(builder.params);
        disks = List.copyOf(builder.disks);
        network = builder.network;
        bootloaderPath = builder.bootloaderPath;
    }

    public String getName() { return name; }
    public String getKernelPath() { return kernelPath; }
    public String getInitrdPath() { return initrdPath; }
    public String[] getParams() { return params.toArray(String[]::new); }
    public Disk[] getDisks() { return disks.toArray(Disk[]::new); }
    public boolean useNetwork() { return network; }
    public String getBootloaderPath() { return bootloaderPath; }
    public SharedPath[] getSharedPaths() { return new SharedPath[0]; }
    public AudioConfig getAudioConfig() { return null; }
    public DisplayConfig getDisplayConfig() { return null; }
    public GpuConfig getGpuConfig() { return null; }
    public UsbConfig getUsbConfig() { return null; }
    public boolean useTouch() { return false; }
    public boolean useKeyboard() { return false; }
    public boolean useMouse() { return false; }
    public boolean useSwitches() { return false; }
    public boolean useTrackpad() { return false; }
    public boolean useAutoMemoryBalloon() { return false; }
    // Names match official return types; no backend behavior is simulated here.
    public static final class SharedPath { }
    public static final class AudioConfig { }
    public static final class DisplayConfig { }
    public static final class GpuConfig { }
    public static final class UsbConfig { }

    // Fixture observation helpers; none is called by the production adapter.
    public String kernelPath() { return kernelPath; }
    public String initrdPath() { return initrdPath; }
    public String cmdline() { return String.join(" ", params); }
    public String kernelCmdline() { return cmdline(); }
    public List<Disk> disks() { return disks; }
    public boolean isNetworkSupported() { return network; }

    public static final class Disk {
        private final String imagePath;
        private final boolean writable;
        private final List<Partition> partitions = new ArrayList<>();
        private Disk(String path, boolean writable) {
            imagePath = path;
            this.writable = writable;
        }
        public static Disk RWDisk(String path) { return new Disk(path, true); }
        public static Disk RODisk(String path) { return new Disk(path, false); }
        public String getImagePath() { return imagePath; }
        public boolean isWritable() { return writable; }
        public List<Partition> getPartitions() { return partitions; }
        public Disk addPartition(Partition partition) { partitions.add(partition); return this; }
        public String path() { return imagePath; }
        public boolean writable() { return writable; }
    }

    public static final class Partition {
        public final String name;
        public final String imagePath;
        public final boolean writable;
        public final String guid;
        public Partition(String name, String imagePath, boolean writable, String guid) {
            this.name = name;
            this.imagePath = imagePath;
            this.writable = writable;
            this.guid = guid;
        }
    }

    public static final class Builder {
        private String name;
        private String bootloaderPath;
        private String kernelPath;
        private String initrdPath;
        private final List<String> params = new ArrayList<>();
        private final List<Disk> disks = new ArrayList<>();
        private boolean network;
        public Builder setName(String value) { name = value; return this; }
        public Builder setBootloaderPath(String value) { bootloaderPath = value; return this; }
        public Builder setKernelPath(String value) { kernelPath = value; return this; }
        public Builder setInitrdPath(String value) { initrdPath = value; return this; }
        public Builder addParam(String value) { params.add(value); return this; }
        public Builder addDisk(Disk disk) { disks.add(disk); return this; }
        public Builder useNetwork(boolean value) { network = value; return this; }
        public VirtualMachineCustomImageConfig build() {
            return new VirtualMachineCustomImageConfig(this);
        }
    }
}
