package io.github.nyzeep.droidlayer.runtime.network;

import io.github.nyzeep.droidlayer.runtime.contract.PortForwardingRule;
import io.github.nyzeep.droidlayer.runtime.contract.Protocol;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * File-backed atomic persistence for port forwarding rules.
 *
 * <p>Stores rule configurations in deterministic properties format under
 * {@code <baseDirectory>/<instanceId>/port-forwarding.properties}.
 * Atomic writes ensure integrity against host process termination.
 */
public final class FilePortForwardingStore implements PortForwardingStore {
    public static final String FILE_NAME = "port-forwarding.properties";

    private final Path baseDirectory;

    public FilePortForwardingStore(Path baseDirectory) {
        this.baseDirectory = Objects.requireNonNull(baseDirectory, "baseDirectory");
    }

    @Override
    public List<PortForwardingRule> loadRules(String instanceId) throws IOException {
        Objects.requireNonNull(instanceId, "instanceId");
        Path file = resolveInstanceFile(instanceId);
        if (!Files.exists(file) || Files.size(file) == 0) {
            return Collections.emptyList();
        }

        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        }

        String countStr = properties.getProperty("rules.count", "0");
        int count = Integer.parseInt(countStr);
        List<PortForwardingRule> rules = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            String prefix = "rule." + i + ".";
            String id = properties.getProperty(prefix + "id");
            String protocolStr = properties.getProperty(prefix + "protocol");
            String hostPortStr = properties.getProperty(prefix + "hostPort");
            String guestPortStr = properties.getProperty(prefix + "guestPort");
            String bindAddress = properties.getProperty(prefix + "bindAddress");
            String lanExposedStr = properties.getProperty(prefix + "lanExposed", "false");
            String createdAtStr = properties.getProperty(prefix + "createdAtMillis", "0");
            String description = properties.getProperty(prefix + "description", "");

            if (id != null && protocolStr != null && hostPortStr != null && guestPortStr != null) {
                Protocol protocol = Protocol.fromWire(protocolStr);
                int hostPort = Integer.parseInt(hostPortStr);
                int guestPort = Integer.parseInt(guestPortStr);
                boolean lanExposed = Boolean.parseBoolean(lanExposedStr);
                long createdAt = Long.parseLong(createdAtStr);

                PortForwardingRule rule = PortForwardingRule.builder()
                        .id(id)
                        .protocol(protocol)
                        .hostPort(hostPort)
                        .guestPort(guestPort)
                        .bindAddress(bindAddress)
                        .allowLanExposure(lanExposed)
                        .createdAtMillis(createdAt)
                        .description(description)
                        .build();
                rules.add(rule);
            }
        }
        return Collections.unmodifiableList(rules);
    }

    @Override
    public void saveRules(String instanceId, List<PortForwardingRule> rules) throws IOException {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(rules, "rules");

        Path file = resolveInstanceFile(instanceId);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Path tempFile = file.resolveSibling(file.getFileName().toString() + ".tmp");

        Properties properties = new Properties();
        properties.setProperty("rules.count", Integer.toString(rules.size()));

        for (int i = 0; i < rules.size(); i++) {
            PortForwardingRule rule = rules.get(i);
            String prefix = "rule." + i + ".";
            properties.setProperty(prefix + "id", rule.id());
            properties.setProperty(prefix + "protocol", rule.protocol().wireName());
            properties.setProperty(prefix + "hostPort", Integer.toString(rule.hostPort()));
            properties.setProperty(prefix + "guestPort", Integer.toString(rule.guestPort()));
            properties.setProperty(prefix + "bindAddress", rule.bindAddress());
            properties.setProperty(prefix + "lanExposed", Boolean.toString(rule.isLanExposed()));
            properties.setProperty(prefix + "createdAtMillis", Long.toString(rule.createdAtMillis()));
            properties.setProperty(prefix + "description", rule.description());
        }

        try (OutputStream out = Files.newOutputStream(tempFile)) {
            properties.store(out, "DroidLayer Port Forwarding Rules - Instance: " + instanceId);
        }

        try {
            Files.move(tempFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void clearRules(String instanceId) throws IOException {
        Objects.requireNonNull(instanceId, "instanceId");
        Path file = resolveInstanceFile(instanceId);
        Files.deleteIfExists(file);
    }

    private Path resolveInstanceFile(String instanceId) {
        return baseDirectory.resolve(instanceId).resolve(FILE_NAME);
    }
}
