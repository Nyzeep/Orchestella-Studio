import io.github.nyzeep.orchestella.DistroInstanceCreator;
import io.github.nyzeep.orchestella.DistroMirrorProvisioner;
import io.github.nyzeep.orchestella.FirstBootProvisionPipeline;
import io.github.nyzeep.orchestella.GuestRuntimeProvisioner;
import io.github.nyzeep.orchestella.QemuRuntimeBootstrapper;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistry;
import io.github.nyzeep.orchestella.runtime.distro.DistroManifest;
import io.github.nyzeep.orchestella.runtime.engineqemu.RuntimeArtifactManifest;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Host-only, offline preparation. Never launches Android, QEMU, or a guest executable. */
public final class PrepareDistroSmokeInputs {
    private static final long RAW_BYTES = 8L * 1024 * 1024 * 1024;
    private static final Path APPROVED_ROOT = Path.of(
            "D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/quality-20261013/device-smoke");
    private static final List<String> MANIFEST_ARTIFACTS = List.of(
            "Image", "initramfs.cpio.gz", "QEMU-BUILD-INPUTS", "QEMU-GPL-SOURCES.md",
            "LICENSE.GPL-2.0", "NOTICE.QEMU", RuntimeArtifactManifest.QEMU_ARTIFACT_PATH);
    private static String stage = "arguments";

    public static void main(String[] args) {
        try {
            prepare(args);
        } catch (Exception | LinkageError failure) {
            // Do not print exceptions, paths, credentials, or a credential-bearing stack trace.
            String reason = failure instanceof Rejected ? failure.getMessage() : failure.getClass().getSimpleName();
            if (failure instanceof DistroManifest.InputRejectedException rejected) reason = rejected.reason();
            if (failure instanceof RuntimeArtifactManifest.IntegrityRejectedException rejected) reason = rejected.reason();
            if (failure instanceof NoClassDefFoundError && failure.getMessage() != null
                    && failure.getMessage().matches("android/[A-Za-z0-9_/$]+")) {
                reason = "android-dependency-" + failure.getMessage().replace('/', '.');
            }
            System.err.println("prepare-failed " + stage + " " + reason);
            System.exit(1);
        }
    }

    private static void prepare(String[] args) throws Exception {
        Map<String, String> options = new LinkedHashMap<>();
        Set<String> allowed = Set.of("--apk", "--inputs", "--output", "--distros", "--resume-plan");
        for (int i = 0; i < args.length; i += 2) {
            require(i + 1 < args.length && allowed.contains(args[i])
                    && !args[i + 1].isBlank() && options.putIfAbsent(args[i], args[i + 1]) == null,
                    "arguments-invalid");
        }
        require(options.keySet().containsAll(Set.of("--apk", "--inputs", "--output", "--distros")), "arguments-required");
        Path apk = regularFile(Path.of(options.get("--apk")));
        Path inputs = directory(Path.of(options.get("--inputs")));
        Path output = directory(Path.of(options.get("--output")));
        Path approved = directory(APPROVED_ROOT);
        require(output.startsWith(approved), "output-outside-approved-root");
        require(!output.startsWith(inputs) && !inputs.startsWith(output)
                && !apk.startsWith(output), "input-output-overlap");
        DistroCatalogRegistry catalog = DistroCatalogRegistry.createDefault();
        List<DistroManifest> selected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String id : options.get("--distros").split(",", -1)) {
            require(id.matches("[a-z][a-z0-9-]{0,31}") && catalog.contains(id)
                    && seen.add(id), "distro-manifest-unknown-or-duplicate");
            DistroManifest distro = catalog.require(id);
            Path input = regularFile(inputs.resolve(distro.rootfsFileName()));
            require(input.getParent().equals(inputs), "input-outside-root");
            distro.requireExpectedSize(Files.size(input));
            selected.add(distro);
            absent(output.resolve(id));
        }
        // Rebootstrap also publishes into a new batch root; it never changes the old batch.
        for (String name : List.of("plan.json", "private", "hostSparse")) {
            absent(output.resolve(name));
        }
        Path resumePath = options.containsKey("--resume-plan")
                ? regularFile(Path.of(options.get("--resume-plan"))) : null;
        Map<String, Object> resumePlan = null;
        Map<String, InstanceLifecycle.Instance> resumeInstances = new LinkedHashMap<>();
        Map<String, Path> resumeRawSourcePlans = new LinkedHashMap<>();
        if (resumePath != null) {
            stage = "resume-verification";
            Path oldRoot = resumePath.getParent();
            require(oldRoot.startsWith(approved) && !oldRoot.startsWith(output) && !output.startsWith(oldRoot),
                    "resume-source-scope");
            resumePlan = readJson(resumePath);
            require(number(resumePlan, "schemaVersion") == 1
                    && "private/credentials.json".equals(resumePlan.get("privateCredentials")), "resume-plan-schema");
            Map<String, Object> oldCredentials = readJson(regularFile(oldRoot.resolve("private/credentials.json")));
            require(number(oldCredentials, "schemaVersion") == 1, "resume-credentials-schema");
            List<?> oldRows = array(resumePlan.get("instances"));
            List<?> oldSecrets = array(oldCredentials.get("instances"));
            Set<String> oldIds = new HashSet<>();
            for (Object value : oldRows) require(oldIds.add(text(object(value), "distro")), "resume-duplicate-distro");
            require(oldRows.size() == oldSecrets.size(), "resume-credentials-count");
            for (DistroManifest distro : selected) {
                Map<String, Object> row = find(oldRows, "distro", distro.id());
                String id = text(row, "instanceId");
                require(java.util.UUID.fromString(id).toString().equals(id), "resume-instance-id");
                Map<String, Object> credential = find(oldSecrets, "instanceId", id);
                Path storePath = directory(oldRoot.resolve("private/" + distro.id() + "/instances"));
                InstanceLifecycle.Instance instance = new InstanceLifecycle.FileStore(storePath).load(id);
                require(instance.distro().id().equals(distro.id()) && instance.distro().version().equals(distro.version())
                        && text(row, "version").equals(distro.version())
                        && text(credential, "distro").equals(distro.id())
                        && instance.bridgeToken().equals(text(credential, "bridgeToken"))
                        && instance.guestPassword().equals(text(credential, "guestPassword"))
                        && instance.defaultUser().equals(text(credential, "username"))
                        && instance.hostname().equals(text(credential, "hostname")), "resume-instance-credentials");
                require(instance.state() == InstanceLifecycle.State.CREATED, "resume-metadata-state");
                Path rawSourcePlan = requireResumeRawSource(instance, row, approved, output);
                resumeRawSourcePlans.put(distro.id(), rawSourcePlan);
                Map<String, Object> rootfs = object(row.get("rootfs"));
                require(text(rootfs, "file").equals(distro.rootfsFileName()) && text(rootfs, "sha256").equals(distro.sha256())
                        && number(rootfs, "bytes") == distro.sizeBytes()
                        && number(row, "rawBytes") == RAW_BYTES
                        && text(row, "marker").equals(GuestRuntimeProvisioner.ROOT_VOLUME_CREATION_SIGNATURE)
                        && Boolean.FALSE.equals(row.get("pushHostRaw"))
                        && text(row, "relativePhoneDir").equals("files/quality-smoke/" + distro.id() + "-" + id.substring(0, 8)),
                        "resume-manifest-or-phone-scope");
                require(instance.hardwareConfig().cpuCores() == 2 && !instance.hardwareConfig().gpuAccelerationEnabled()
                        && instance.hardwareConfig().ramMegabytes() == number(row, "ramMegabytes")
                        && instance.hardwareConfig().diskCapacityBytes() == RAW_BYTES, "resume-hardware");
                resumeInstances.put(distro.id(), instance);
            }
        }

        stage = "apk-verification";
        String apkHash = hash(apk);
        List<Map<String, Object>> publicRows = new ArrayList<>();
        List<Map<String, Object>> privateRows = new ArrayList<>();
        Map<String, Object> packaged = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            Map<String, ZipEntry> entries = new LinkedHashMap<>();
            Set<String> wanted = new HashSet<>();
            wanted.add("assets/" + RuntimeArtifactManifest.SCHEMA_FILE_NAME);
            for (String name : MANIFEST_ARTIFACTS) wanted.add(apkPath(name));
            wanted.add("guest_init");
            wanted.add("proxy_relay");
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                if (wanted.contains(entry.getName())) {
                    require(entries.putIfAbsent(entry.getName(), entry) == null,
                            "apk-required-entry-duplicate");
                }
            }
            for (String name : wanted) {
                ZipEntry entry = entries.get(name);
                require(entry != null && !entry.isDirectory() && entry.getSize() > 0
                        && entry.getSize() <= 1024L * 1024 * 1024, "apk-required-entry-invalid");
            }
            require(entries.get("assets/" + RuntimeArtifactManifest.SCHEMA_FILE_NAME).getSize() <= 1024 * 1024
                    && entries.get("guest_init").getSize() <= 8 * 1024 * 1024
                    && entries.get("proxy_relay").getSize() <= 8 * 1024 * 1024,
                    "apk-resource-too-large");
            RuntimeArtifactManifest manifest;
            try (InputStream in = zip.getInputStream(entries.get("assets/" + RuntimeArtifactManifest.SCHEMA_FILE_NAME))) {
                manifest = RuntimeArtifactManifest.parse(in);
            }
            for (String name : MANIFEST_ARTIFACTS) {
                try (InputStream in = zip.getInputStream(entries.get(apkPath(name)))) {
                    manifest.requireVerifiedPackagedAsset(name, in);
                }
            }
            for (String name : List.of("assets/" + RuntimeArtifactManifest.SCHEMA_FILE_NAME,
                    "assets/Image", "initramfs.cpio.gz", "guest_init", "proxy_relay")) {
                try (InputStream in = zip.getInputStream(entries.get(name))) {
                    packaged.put(name, Map.of("bytes", entries.get(name).getSize(), "sha256", hash(in)));
                }
            }

            privateDirectory(output.resolve("private"));
            privateDirectory(output.resolve("hostSparse"));
            Set<String> tokens = new HashSet<>();
            Set<String> passwords = new HashSet<>();
            for (DistroManifest distro : selected) {
                stage = distro.id() + (resumePath == null ? "-create" : "-rebootstrap");
                System.out.println(distro.id() + (resumePath == null ? " verify-create " : " rebootstrap ") + distro.sizeBytes());
                Path distroDir = privateDirectory(output.resolve(distro.id()));
                Path runtime = privateDirectory(distroDir.resolve("runtime"));
                Path sparse = privateDirectory(output.resolve("hostSparse").resolve(distro.id()));
                Path secretDir = privateDirectory(output.resolve("private").resolve(distro.id()));
                Path storeDir = privateDirectory(secretDir.resolve("instances"));
                InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
                int ramMegabytes = distro.id().equals("arch")
                        ? Math.max(3072, ((memoryMinimum(distro.sizeBytes() + 8L * 1024 * 1024) + 1023) / 1024) * 1024)
                        : 1024;
                InstanceLifecycle.Instance instance;
                if (resumePath != null) {
                    instance = resumeInstances.get(distro.id());
                    ramMegabytes = instance.hardwareConfig().ramMegabytes();
                    store.save(instance); // Only the new output's metadata is written.
                } else {
                    InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
                    VmHardwareConfig hardware = new VmHardwareConfig(2, ramMegabytes, RAW_BYTES, false, null);
                    // Obtain the API-created marker, then extend a CREATE_NEW SPARSE host raw.
                    DistroInstanceCreator creator = new DistroInstanceCreator(distro, controller, sparse, 4096);
                    try (InputStream in = Files.newInputStream(inputs.resolve(distro.rootfsFileName()))) {
                        instance = creator.create(in, distro.sizeBytes(), hardware, InstanceUserConfig.defaultRoot());
                    }
                    expandFreshMarker(instance.rootVolume());
                }
                require(tokens.add(instance.bridgeToken()) && passwords.add(instance.guestPassword()),
                        "credential-collision");
                ownerOnlyTree(storeDir);
                FirstBootProvisionPipeline.ProvisionPayload payload = FirstBootProvisionPipeline.create(
                        distro.id(), distro.version(), DistroMirrorProvisioner.MirrorSource.OFFICIAL, false);
                require(payload.hasMirrorConfig() && payload.hasFirstBootScript(), "provision-payload-incomplete");
                stage = distro.id() + "-bootstrap";
                System.out.println(distro.id() + " bootstrap " + RAW_BYTES);
                QemuRuntimeBootstrapper bootstrapper = new QemuRuntimeBootstrapper(manifest,
                        name -> zipStream(zip, entries, "assets/" + name),
                        name -> zipStream(zip, entries, name), runtime,
                        resumePath == null ? sparse.resolve("distro-inputs") : inputs, catalog.listAll());
                QemuRuntimeBootstrapper.RuntimePaths paths = bootstrapper.bootstrap(
                        distro.rootfsFileName(), instance.bridgeToken(), instance.guestPassword(),
                        instance.rootVolume(), payload.mirrorConfigFileName(), payload.mirrorConfigFileContent(),
                        payload.firstBootScript());
                ownerOnlyTree(runtime); // The embedded archive carries synthetic credentials.
                int memoryMinimum = memoryMinimum(Files.size(paths.initramfs));
                require(ramMegabytes >= memoryMinimum, "qa-ram-too-small-reprepare-required");
                String shortId = instance.id().substring(0, 8);
                String socketSuffix = distro.id() + "-" + shortId;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("distro", distro.id());
                row.put("version", distro.version());
                row.put("instanceId", instance.id());
                row.put("relativePhoneDir", "files/quality-smoke/" + socketSuffix);
                row.put("cpuCores", 2);
                row.put("ramMegabytes", ramMegabytes);
                row.put("memoryMinimumMegabytes", memoryMinimum);
                row.put("exclusiveScheduling", distro.id().equals("arch"));
                row.put("gpuAccelerationEnabled", false);
                row.put("baselineMandatory", true);
                row.put("mirrorSource", "OFFICIAL");
                row.put("autoUpdate", false);
                row.put("rawBytes", RAW_BYTES);
                row.put("marker", GuestRuntimeProvisioner.ROOT_VOLUME_CREATION_SIGNATURE);
                row.put("phoneRootVolume", "root-volume.raw");
                Path rawSourcePlan = resumeRawSourcePlans.get(distro.id());
                row.put("hostSparseRoot", resumePath == null ? relative(output, instance.rootVolume())
                        : relative(rawSourcePlan.getParent(), instance.rootVolume()));
                if (resumePath != null) {
                    row.put("hostSparseRootBase", "resume-source-plan");
                    row.put("hostSparseSourcePlan", Map.of("path", relative(approved, rawSourcePlan), "sha256", hash(rawSourcePlan)));
                }
                row.put("pushHostRaw", false);
                row.put("rootfs", Map.of("file", distro.rootfsFileName(),
                        "bytes", distro.sizeBytes(), "sha256", distro.sha256()));
                row.put("kernel", fileInfo(output, paths.kernel));
                row.put("initramfs", fileInfo(output, paths.initramfs));
                row.put("baseInitramfs", fileInfo(output, runtime.resolve("initramfs.cpio.gz")));
                row.put("controlSocket", "quality-smoke-control-" + socketSuffix);
                row.put("terminalSocket", "quality-smoke-terminal-" + socketSuffix);
                row.put("gatewayId", "quality-smoke-gateway-" + socketSuffix);
                row.put("gatewayPort", 0); // Driver must allocate a separate ephemeral listener per gateway.
                publicRows.add(row);
                Map<String, Object> credential = new LinkedHashMap<>();
                credential.put("distro", distro.id());
                credential.put("instanceId", instance.id());
                credential.put("bridgeToken", instance.bridgeToken());
                credential.put("guestPassword", instance.guestPassword());
                credential.put("username", instance.defaultUser());
                credential.put("hostname", instance.hostname());
                privateRows.add(credential);
                System.out.println(distro.id() + " prepared " + Files.size(paths.initramfs));
            }
        }
        stage = "publish";
        require(apkHash.equals(hash(apk)), "apk-changed-during-preparation");
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("schemaVersion", 1);
        credentials.put("instances", privateRows);
        writeJson(output.resolve("private/credentials.json"), credentials, true);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("schemaVersion", 1);
        plan.put("apk", Map.of("bytes", Files.size(apk), "sha256", apkHash));
        plan.put("packagedEntries", packaged);
        plan.put("privateCredentials", "private/credentials.json");
        if (resumePath != null) {
            plan.put("resumeFromPlanSha256", hash(resumePath));
            plan.put("resumeFromApkSha256", text(object(resumePlan.get("apk")), "sha256"));
        }
        plan.put("instances", publicRows);
        // Last file published is the ready signal. Failed/partial batches have no plan.json.
        writeJson(output.resolve("plan.json"), plan, false);
        System.out.println("prepared " + publicRows.size() + " " + RAW_BYTES);
    }

    /** Resolve the original QA sparse raw, including plans produced by an earlier rebootstrap. */
    private static Path requireResumeRawSource(InstanceLifecycle.Instance instance,
            Map<String, Object> row, Path approved, Path output) throws Exception {
        Path candidate = instance.rootVolume().toAbsolutePath().normalize();
        require(candidate.startsWith(approved), "resume-host-raw-scope");
        Path raw = regularFile(candidate);
        require(raw.startsWith(approved) && Files.size(raw) == RAW_BYTES
                && raw.getFileName().toString().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.raw"),
                "resume-host-raw-scope");
        Path volumes = raw.getParent();
        require(volumes.getFileName().toString().equals("root-volumes")
                && volumes.getParent().getFileName().toString().equals(instance.distro().id())
                && volumes.getParent().getParent().getFileName().toString().equals("hostSparse"), "resume-host-raw-layout");
        Path sourceRoot = volumes.getParent().getParent().getParent();
        require(sourceRoot.startsWith(approved) && !sourceRoot.startsWith(output) && !output.startsWith(sourceRoot),
                "resume-host-raw-source-scope");
        Path sourcePlan = regularFile(sourceRoot.resolve("plan.json"));
        if (row.containsKey("hostSparseSourcePlan")) {
            Map<String, Object> pointer = object(row.get("hostSparseSourcePlan"));
            require(text(pointer, "path").equals(relative(approved, sourcePlan))
                    && text(pointer, "sha256").equals(hash(sourcePlan)), "resume-host-raw-source-plan-mismatch");
        }
        Map<String, Object> source = readJson(sourcePlan);
        require(number(source, "schemaVersion") == 1 && !source.containsKey("resumeFromPlanSha256")
                && "private/credentials.json".equals(source.get("privateCredentials")), "resume-host-raw-original-plan");
        Map<String, Object> original = find(array(source.get("instances")), "distro", instance.distro().id());
        require(text(original, "hostSparseRoot").equals(relative(sourceRoot, raw))
                && text(row, "hostSparseRoot").equals(text(original, "hostSparseRoot")), "resume-host-raw-original-path");
        for (String field : List.of("distro", "version", "instanceId", "relativePhoneDir", "rootfs", "rawBytes", "marker",
                "phoneRootVolume", "pushHostRaw", "cpuCores", "ramMegabytes", "gpuAccelerationEnabled")) {
            require(java.util.Objects.equals(original.get(field), row.get(field)), "resume-host-raw-original-identity");
        }
        Path metadataDirectory = directory(sourceRoot.resolve("private/" + instance.distro().id() + "/instances"));
        regularFile(metadataDirectory.resolve(instance.id() + ".properties"));
        InstanceLifecycle.Instance originalInstance = new InstanceLifecycle.FileStore(metadataDirectory).load(instance.id());
        require(originalInstance.id().equals(instance.id()) && originalInstance.distro().id().equals(instance.distro().id())
                && originalInstance.distro().version().equals(instance.distro().version())
                && originalInstance.rootVolume().toAbsolutePath().normalize().equals(raw)
                && originalInstance.bridgeToken().equals(instance.bridgeToken())
                && originalInstance.guestPassword().equals(instance.guestPassword())
                && originalInstance.defaultUser().equals(instance.defaultUser()) && originalInstance.hostname().equals(instance.hostname())
                && originalInstance.hardwareConfig().equals(instance.hardwareConfig())
                && originalInstance.state() == InstanceLifecycle.State.CREATED, "resume-host-raw-original-metadata");
        Map<String, Object> credentials = readJson(regularFile(sourceRoot.resolve("private/credentials.json")));
        require(number(credentials, "schemaVersion") == 1, "resume-host-raw-original-credentials");
        Map<String, Object> credential = find(array(credentials.get("instances")), "instanceId", instance.id());
        require(text(credential, "distro").equals(instance.distro().id())
                && text(credential, "bridgeToken").equals(instance.bridgeToken())
                && text(credential, "guestPassword").equals(instance.guestPassword())
                && text(credential, "username").equals(instance.defaultUser()) && text(credential, "hostname").equals(instance.hostname()),
                "resume-host-raw-original-credentials");
        byte[] marker = GuestRuntimeProvisioner.ROOT_VOLUME_CREATION_SIGNATURE.getBytes(StandardCharsets.US_ASCII);
        try (InputStream input = Files.newInputStream(raw)) {
            require(java.util.Arrays.equals(input.readNBytes(marker.length), marker), "resume-host-raw-marker");
        }
        Path seed = regularFile(raw.resolveSibling(raw.getFileName() + ".creator-marker"));
        require(Files.size(seed) == 4096, "resume-host-raw-seed");
        byte[] seedBytes = Files.readAllBytes(seed);
        for (int i = 0; i < seedBytes.length; i++) {
            require(seedBytes[i] == (i < marker.length ? marker[i] : 0), "resume-host-raw-seed");
        }
        return sourcePlan;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) throws IOException {
        require(value instanceof Map, "resume-json-object-required");
        return (Map<String, Object>) value;
    }

    private static List<?> array(Object value) throws IOException {
        require(value instanceof List, "resume-json-array-required");
        return (List<?>) value;
    }

    private static String text(Map<String, Object> value, String key) throws IOException {
        require(value.get(key) instanceof String, "resume-json-string-required");
        return (String) value.get(key);
    }

    private static long number(Map<String, Object> value, String key) throws IOException {
        require(value.get(key) instanceof Long, "resume-json-integer-required");
        return (Long) value.get(key);
    }

    private static Map<String, Object> find(List<?> rows, String key, String expected) throws IOException {
        Map<String, Object> found = null;
        for (Object value : rows) {
            Map<String, Object> row = object(value);
            if (expected.equals(text(row, key))) {
                require(found == null, "resume-json-duplicate-identity");
                found = row;
            }
        }
        require(found != null, "resume-json-identity-missing");
        return found;
    }

    private static Map<String, Object> readJson(Path path) throws IOException {
        require(Files.size(path) <= 2 * 1024 * 1024, "resume-json-too-large");
        JsonReader reader = new JsonReader(Files.readString(path, StandardCharsets.UTF_8));
        Map<String, Object> result = object(reader.value(0));
        reader.space();
        require(reader.at == reader.source.length(), "resume-json-trailing-content");
        return result;
    }

    /** Small strict reader for this tool's integer/string/boolean plan schema; no Android JSON. */
    private static final class JsonReader {
        final String source;
        int at;
        JsonReader(String source) { this.source = source; }
        void space() { while (at < source.length() && " \r\n\t".indexOf(source.charAt(at)) >= 0) at++; }
        boolean take(char c) { space(); if (at < source.length() && source.charAt(at) == c) { at++; return true; } return false; }
        Object value(int depth) throws IOException {
            require(depth <= 16, "resume-json-depth");
            space();
            require(at < source.length(), "resume-json-truncated");
            if (source.charAt(at) == '"') return string();
            if (take('{')) {
                Map<String, Object> fields = new LinkedHashMap<>();
                if (take('}')) return fields;
                do {
                    String key = string();
                    require(take(':') && !fields.containsKey(key), "resume-json-key");
                    fields.put(key, value(depth + 1));
                } while (take(','));
                require(take('}'), "resume-json-object");
                return fields;
            }
            if (take('[')) {
                List<Object> items = new ArrayList<>();
                if (take(']')) return items;
                do { items.add(value(depth + 1)); } while (take(','));
                require(take(']'), "resume-json-array");
                return items;
            }
            for (String literal : List.of("true", "false")) {
                if (source.startsWith(literal, at)) { at += literal.length(); return Boolean.valueOf(literal); }
            }
            int start = at;
            while (at < source.length() && "0123456789-".indexOf(source.charAt(at)) >= 0) at++;
            String digits = source.substring(start, at);
            require(digits.matches("-?(0|[1-9][0-9]*)"), "resume-json-value");
            try { return Long.valueOf(digits); } catch (NumberFormatException invalid) { throw new Rejected("resume-json-integer"); }
        }
        String string() throws IOException {
            require(take('"'), "resume-json-string");
            StringBuilder text = new StringBuilder();
            while (at < source.length()) {
                char c = source.charAt(at++);
                if (c == '"') return text.toString();
                require(c >= 32, "resume-json-control");
                if (c == '\\') {
                    require(at < source.length(), "resume-json-escape");
                    char escaped = source.charAt(at++);
                    if (escaped == 'u') {
                        require(at + 4 <= source.length() && source.substring(at, at + 4).matches("[0-9A-Fa-f]{4}"), "resume-json-unicode");
                        c = (char) Integer.parseInt(source.substring(at, at + 4), 16); at += 4;
                    } else {
                        int index = "\"\\/bfnrt".indexOf(escaped);
                        require(index >= 0, "resume-json-escape");
                        c = new char[] {'"', '\\', '/', '\b', '\f', '\n', '\r', '\t'}[index];
                    }
                }
                text.append(c);
            }
            throw new Rejected("resume-json-string-truncated");
        }
    }

    private static int memoryMinimum(long initramfsBytes) {
        return (int) Math.max(1024, (2 * initramfsBytes + 1024 * 1024 - 1) / (1024 * 1024) + 512);
    }

    private static void expandFreshMarker(Path raw) throws IOException {
        byte[] seed = Files.readAllBytes(regularFile(raw));
        byte[] marker = GuestRuntimeProvisioner.ROOT_VOLUME_CREATION_SIGNATURE.getBytes(StandardCharsets.US_ASCII);
        require(seed.length == 4096, "fresh-marker-size-invalid");
        for (int i = 0; i < seed.length; i++) {
            require(seed[i] == (i < marker.length ? marker[i] : 0), "fresh-marker-invalid");
        }
        Path seedPath = raw.resolveSibling(raw.getFileName() + ".creator-marker");
        absent(seedPath);
        Files.move(raw, seedPath); // Retain API-created seed; do not replace any file.
        try (SeekableByteChannel channel = Files.newByteChannel(raw, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
            ByteBuffer bytes = ByteBuffer.wrap(seed);
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.position(RAW_BYTES - 1);
            channel.write(ByteBuffer.wrap(new byte[] {0}));
        }
        require(Files.size(raw) == RAW_BYTES, "host-raw-size-invalid");
        ownerOnly(raw);
        ownerOnly(seedPath);
    }

    private static String apkPath(String name) {
        return name.equals("initramfs.cpio.gz") || name.startsWith("lib/") ? name : "assets/" + name;
    }

    private static InputStream zipStream(ZipFile zip, Map<String, ZipEntry> entries, String name) {
        try {
            ZipEntry entry = entries.get(name);
            require(entry != null, "apk-resource-unavailable");
            return zip.getInputStream(entry);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Path regularFile(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        noLinks(absolute);
        require(Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS), "input-file-invalid");
        return absolute.toRealPath();
    }

    private static Path directory(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        noLinks(absolute);
        require(Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS), "directory-missing-or-invalid");
        return absolute.toRealPath();
    }

    private static void noLinks(Path path) throws IOException {
        for (Path cursor = path; cursor != null; cursor = cursor.getParent()) {
            require(!Files.isSymbolicLink(cursor), "symbolic-link-rejected");
        }
    }

    private static void absent(Path path) throws IOException {
        require(!Files.exists(path, LinkOption.NOFOLLOW_LINKS), "output-already-exists");
    }

    private static Path privateDirectory(Path path) throws IOException {
        Files.createDirectory(path);
        ownerOnly(path);
        return path;
    }

    private static void ownerOnlyTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) ownerOnly(path);
        }
    }

    private static void ownerOnly(Path path) throws IOException {
        boolean dir = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            var mode = PosixFilePermissions.fromString(dir ? "rwx------" : "rw-------");
            posix.setPermissions(mode);
            require(posix.readAttributes().permissions().equals(mode), "owner-permissions-unverified");
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        require(acl != null, "owner-permissions-unsupported");
        var owner = acl.getOwner();
        var builder = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (dir) builder.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
        acl.setAcl(List.of(builder.build()));
        require(!acl.getAcl().isEmpty() && acl.getAcl().stream().allMatch(entry ->
                entry.type() == AclEntryType.ALLOW && entry.principal().equals(owner)),
                "owner-permissions-unverified");
    }

    private static Map<String, Object> fileInfo(Path root, Path file) throws Exception {
        return Map.of("path", relative(root, file), "bytes", Files.size(file), "sha256", hash(file));
    }

    private static String relative(Path root, Path path) throws IOException {
        require(path.toAbsolutePath().normalize().startsWith(root), "generated-path-outside-output");
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static String hash(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) { return hash(in); }
    }

    private static String hash(InputStream in) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        int count;
        while ((count = in.read(buffer)) != -1) if (count > 0) digest.update(buffer, 0, count);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void writeJson(Path file, Object value, boolean privateFile) throws IOException {
        Path partial = file.resolveSibling(file.getFileName() + ".partial");
        Files.createFile(partial);
        if (privateFile) ownerOnly(partial);
        Files.writeString(partial, json(value) + "\n", StandardCharsets.UTF_8, StandardOpenOption.WRITE);
        // Moving without REPLACE_EXISTING refuses an already-published plan/credential file.
        Files.move(partial, file);
    }

    private static String json(Object value) {
        if (value instanceof String text) {
            StringBuilder out = new StringBuilder("\"");
            for (char c : text.toCharArray()) {
                switch (c) {
                    case '"': out.append("\\\""); break;
                    case '\\': out.append("\\\\"); break;
                    case '\n': out.append("\\n"); break;
                    case '\r': out.append("\\r"); break;
                    case '\t': out.append("\\t"); break;
                    default:
                        if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                        else out.append(c);
                }
            }
            return out.append('"').toString();
        }
        if (value instanceof Map<?, ?> map) {
            List<String> fields = new ArrayList<>();
            map.forEach((key, item) -> fields.add(json(key.toString()) + ":" + json(item)));
            return "{" + String.join(",", fields) + "}";
        }
        if (value instanceof List<?> list) return "[" + String.join(",", list.stream().map(PrepareDistroSmokeInputs::json).toList()) + "]";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        throw new IllegalArgumentException("unsupported-json-value");
    }

    private static void require(boolean condition, String reason) throws IOException {
        if (!condition) throw new Rejected(reason);
    }

    private static final class Rejected extends IOException {
        Rejected(String reason) { super(reason); }
    }
}
