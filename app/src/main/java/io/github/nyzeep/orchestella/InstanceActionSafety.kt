package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle

/**
 * Defensive safety guard for instance lifecycle operations (Ticket 52 / US-23).
 *
 * <p>Enforces circuit breakers preventing destructive actions while an instance is running,
 * such as physical deletion or mutating hardware vCPU/RAM allocations.
 */
object InstanceActionSafety {

    /**
     * Determines whether an instance in the given state can be safely deleted.
     * Only STOPPED, CREATED, and CRASHED instances can be purged.
     */
    @JvmStatic
    fun canDelete(state: InstanceLifecycle.State): Boolean {
        return state == InstanceLifecycle.State.STOPPED ||
                state == InstanceLifecycle.State.CREATED ||
                state == InstanceLifecycle.State.CRASHED
    }

    /**
     * Asserts that an instance in the given state can be safely deleted,
     * throwing {@link IllegalStateException} if it is currently running or provisioning.
     */
    @JvmStatic
    fun requireCanDelete(state: InstanceLifecycle.State) {
        if (!canDelete(state)) {
            throw IllegalStateException("Cannot delete instance while in state: $state")
        }
    }

    /**
     * Determines whether hardware configuration can be edited.
     * Only permitted when the VM is powered down (STOPPED or CREATED).
     */
    @JvmStatic
    fun canUpdateHardware(state: InstanceLifecycle.State): Boolean {
        return state == InstanceLifecycle.State.STOPPED ||
                state == InstanceLifecycle.State.CREATED
    }

    /**
     * Asserts that hardware configuration can be updated,
     * throwing {@link IllegalStateException} if running.
     */
    @JvmStatic
    fun requireCanUpdateHardware(state: InstanceLifecycle.State) {
        if (!canUpdateHardware(state)) {
            throw IllegalStateException("Cannot update hardware config while in state: $state; must stop VM first")
        }
    }
}
