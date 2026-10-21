package android.system.virtualmachine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** In-memory vsock network: host writes are recorded, guest replies are preloaded. */
public final class FakeVsockNetwork {
    private final Map<Integer, ByteArrayOutputStream> sent = new HashMap<>();
    private final Map<Integer, byte[]> replies = new HashMap<>();
    private final Set<Integer> closedPorts = new HashSet<>();
    private int connects;

    public void reply(int port, byte[] bytes) {
        replies.put(port, bytes.clone());
    }

    public VsockConnection connect(int port) {
        connects++;
        ByteArrayOutputStream output = sent.computeIfAbsent(port, ignored -> new ByteArrayOutputStream());
        byte[] reply = replies.getOrDefault(port, new byte[0]);
        return new VsockConnection(
                new ByteArrayInputStream(reply),
                output,
                () -> closedPorts.add(port));
    }

    public byte[] sent(int port) {
        ByteArrayOutputStream output = sent.get(port);
        return output == null ? new byte[0] : output.toByteArray();
    }

    public boolean closed(int port) {
        return closedPorts.contains(port);
    }

    public int connectCalls() {
        return connects;
    }
}
