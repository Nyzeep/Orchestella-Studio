package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import io.github.nyzeep.orchestella.runtime.contract.Protocol;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Phase 4.8 Task 3: Pure JVM unit tests for {@link PortForwardingRuleValidator}.
 * Verifies port range limits (1024-65535), empty input, non-numeric parsing, and conflict detection.
 */
public final class PortForwardingRuleValidatorTest {

    @Test
    public void testValidPortNumbers() {
        assertTrue(PortForwardingRuleValidator.validateHostPort("1024").isValid());
        assertTrue(PortForwardingRuleValidator.validateHostPort("8080").isValid());
        assertTrue(PortForwardingRuleValidator.validateHostPort("65535").isValid());

        assertTrue(PortForwardingRuleValidator.validateGuestPort("1024").isValid());
        assertTrue(PortForwardingRuleValidator.validateGuestPort("2222").isValid());
        assertTrue(PortForwardingRuleValidator.validateGuestPort("65535").isValid());
    }

    @Test
    public void testRejectsPortsBelow1024() {
        PortForwardingRuleValidator.Result r1 = PortForwardingRuleValidator.validateHostPort("1023");
        assertFalse(r1.isValid());
        assertEquals(PortForwardingRuleValidator.ValidationStatus.INVALID_HOST_PORT, r1.getStatus());

        PortForwardingRuleValidator.Result r2 = PortForwardingRuleValidator.validateHostPort("80");
        assertFalse(r2.isValid());
        assertEquals(PortForwardingRuleValidator.ValidationStatus.INVALID_HOST_PORT, r2.getStatus());

        PortForwardingRuleValidator.Result r3 = PortForwardingRuleValidator.validateHostPort("0");
        assertFalse(r3.isValid());
    }

    @Test
    public void testRejectsPortsAbove65535() {
        PortForwardingRuleValidator.Result r1 = PortForwardingRuleValidator.validateHostPort("65536");
        assertFalse(r1.isValid());
        assertEquals(PortForwardingRuleValidator.ValidationStatus.INVALID_HOST_PORT, r1.getStatus());

        PortForwardingRuleValidator.Result r2 = PortForwardingRuleValidator.validateGuestPort("99999");
        assertFalse(r2.isValid());
        assertEquals(PortForwardingRuleValidator.ValidationStatus.INVALID_GUEST_PORT, r2.getStatus());
    }

    @Test
    public void testRejectsNonNumericAndEmptyInputs() {
        PortForwardingRuleValidator.Result rEmpty = PortForwardingRuleValidator.validateHostPort("");
        assertFalse(rEmpty.isValid());
        assertEquals(PortForwardingRuleValidator.ValidationStatus.EMPTY_FIELD, rEmpty.getStatus());

        PortForwardingRuleValidator.Result rNull = PortForwardingRuleValidator.validateHostPort(null);
        assertFalse(rNull.isValid());
        assertEquals(PortForwardingRuleValidator.ValidationStatus.EMPTY_FIELD, rNull.getStatus());

        PortForwardingRuleValidator.Result rText = PortForwardingRuleValidator.validateHostPort("abc");
        assertFalse(rText.isValid());
        assertEquals(PortForwardingRuleValidator.ValidationStatus.INVALID_HOST_PORT, rText.getStatus());
    }

    @Test
    public void testConflictDetectionSameProtocolSameHostPort() {
        List<PortForwardingRule> existing = new ArrayList<>();
        existing.add(PortForwardingRule.defaultRule("r1", Protocol.TCP, 8080, 80));

        // Same host port 8080 on TCP -> conflict
        PortForwardingRuleValidator.Result rConflict =
                PortForwardingRuleValidator.validateConflict(8080, Protocol.TCP, existing);
        assertFalse(rConflict.isValid());
        assertEquals(PortForwardingRuleValidator.ValidationStatus.CONFLICT_HOST_PORT, rConflict.getStatus());

        // Same host port 8080 on UDP -> allowed (different protocol)
        PortForwardingRuleValidator.Result rDiffProto =
                PortForwardingRuleValidator.validateConflict(8080, Protocol.UDP, existing);
        assertTrue(rDiffProto.isValid());

        // Different host port 9090 on TCP -> allowed
        PortForwardingRuleValidator.Result rDiffPort =
                PortForwardingRuleValidator.validateConflict(9090, Protocol.TCP, existing);
        assertTrue(rDiffPort.isValid());
    }

    @Test
    public void testFullRuleValidation() {
        List<PortForwardingRule> existing = Collections.singletonList(
                PortForwardingRule.defaultRule("r1", Protocol.TCP, 3000, 3000)
        );

        // Valid new rule
        PortForwardingRuleValidator.Result ok =
                PortForwardingRuleValidator.validateRule("8080", "8080", Protocol.TCP, existing);
        assertTrue(ok.isValid());

        // Conflicting host port
        PortForwardingRuleValidator.Result conflict =
                PortForwardingRuleValidator.validateRule("3000", "5000", Protocol.TCP, existing);
        assertFalse(conflict.isValid());
        assertEquals(PortForwardingRuleValidator.ValidationStatus.CONFLICT_HOST_PORT, conflict.getStatus());
    }
}
