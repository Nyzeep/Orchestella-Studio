package io.github.nyzeep.orchestella.researchauth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Monotonic side-effect observations. Failure changes no earlier success fact. */
public final class ProbeStageLedger {
    private final Map<String, String> facts = new LinkedHashMap<>();
    public void stage(String stage) { facts.put("lastStage", stage); }
    public void happened(String effect) { facts.put(effect, "OBSERVED"); }
    public void failed(String reason) { facts.put("failureKind", reason); }
    public Map<String, String> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(facts));
    }
}
