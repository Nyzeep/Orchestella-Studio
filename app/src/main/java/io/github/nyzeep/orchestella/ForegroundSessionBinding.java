package io.github.nyzeep.orchestella;

import java.util.Objects;

/** Process-local identity carried by foreground START/STOP intents, never a VM owner. */
public final class ForegroundSessionBinding {
    private String instanceId;
    private String generation;

    public synchronized boolean start(String instanceId, String generation) {
        this.instanceId = instanceId;
        this.generation = generation;
        return true;
    }

    public synchronized boolean stop(String instanceId, String generation) {
        if (!isActive()) return false;
        if (this.generation != null) {
            if (!Objects.equals(this.instanceId, instanceId)
                    || !Objects.equals(this.generation, generation)) return false;
        } else if (generation != null || (instanceId != null && !Objects.equals(this.instanceId, instanceId))) {
            return false;
        }
        this.instanceId = null;
        this.generation = null;
        return true;
    }

    public synchronized boolean isActive() { return instanceId != null; }
}
