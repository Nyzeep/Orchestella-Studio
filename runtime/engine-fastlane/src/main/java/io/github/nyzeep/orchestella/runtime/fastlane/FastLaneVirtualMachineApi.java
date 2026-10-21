package io.github.nyzeep.orchestella.runtime.fastlane;

/** Testable platform boundary; the production implementation is reflection-based. */
public interface FastLaneVirtualMachineApi {
    FastLaneHandle start(FastLaneLaunchSpec spec) throws FastLaneException;

    FastLaneChannel connect(FastLaneHandle handle, int port) throws FastLaneException;
}
