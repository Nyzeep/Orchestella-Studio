package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SemVerTest {
    @Test
    public void followsSemVerSpecificationPrecedence() {
        // SemVer 2.0.0 section 11's published precedence example.
        String[] ordered = {"1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta",
                "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0"};
        for (int i = 1; i < ordered.length; i++) {
            assertTrue(ordered[i - 1] + " < " + ordered[i],
                    SemVer.parse(ordered[i - 1]).compareTo(SemVer.parse(ordered[i])) < 0);
        }
        assertTrue(SemVer.parse("0.9.9").compareTo(SemVer.parse("0.10.0")) < 0);
        assertTrue(SemVer.parse("1.99.99").compareTo(SemVer.parse("2.0.0")) < 0);
        assertEquals(0, SemVer.parse("1.2.3+build.001").compareTo(SemVer.parse("1.2.3+other")));
        assertTrue(SemVer.parse("1.0.0-BETA").compareTo(SemVer.parse("1.0.0-beta")) < 0);
    }

    @Test
    public void rejectsMissingMalformedAndNonSemVerVersions() {
        String[] invalid = {null, "", " ", "1", "1.2", "01.2.3", "1.02.3", "1.2.03",
                "1.2.3-01", "1.2.3-alpha.01", "1.2.3-", "1.2.3+", "1.2.3-a..b",
                "1.2.3+a..b", "1.2.3\n", " 1.2.3", "1.2.3 ", "1.2.3-β", "1.2.3_4", "v1.2.3"};
        for (String version : invalid) {
            assertNull(String.valueOf(version), SemVer.parse(version));
        }
        assertNotNull(SemVer.parse("0.0.0-0.a-1+001.build"));
        assertNotNull(SemVer.parseTag("v1.2.3"));
        assertNull(SemVer.parseTag("vv1.2.3"));
    }

    @Test
    public void comparesUnboundedNumericComponentsWithoutOverflow() {
        assertTrue(SemVer.parse("99999999999999999999999999999999999.0.0")
                .compareTo(SemVer.parse("9999999999999999999999999999999999.9.9")) > 0);
        assertTrue(SemVer.parse("1.0.0-9999999999999999999999999")
                .compareTo(SemVer.parse("1.0.0-999999999999999999999999")) > 0);
    }
}
