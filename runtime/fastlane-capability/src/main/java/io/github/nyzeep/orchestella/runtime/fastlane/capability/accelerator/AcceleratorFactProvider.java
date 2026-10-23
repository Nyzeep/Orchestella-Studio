package io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator;

/**
 * Platform seam for accelerator facts. The policy only consumes the returned
 * immutable snapshot; tests use a fake provider and never touch Android APIs.
 */
@FunctionalInterface
public interface AcceleratorFactProvider {
    AcceleratorFacts read();
}
