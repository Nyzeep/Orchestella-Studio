package io.github.nyzeep.droidlayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

public final class BatteryPolicyTest {
    @Test
    public void valuesAndDescriptionsExist() {
        for (BatteryPolicy policy : BatteryPolicy.values()) {
            assertNotNull(policy.name());
            assertNotNull(policy.displayName());
            assertNotNull(policy.description());
        }
        assertEquals("均衡", BatteryPolicy.BALANCED.displayName());
        assertEquals("性能", BatteryPolicy.PERFORMANCE.displayName());
    }
}
