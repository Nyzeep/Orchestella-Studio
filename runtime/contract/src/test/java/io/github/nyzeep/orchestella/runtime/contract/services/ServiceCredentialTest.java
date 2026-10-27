package io.github.nyzeep.orchestella.runtime.contract.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/**
 * Contract tests for {@link ServiceCredential}: the conservative input floor, the explicit
 * reveal gate, and the wipe-on-close lifecycle. Only behaviour present in the implementation
 * is asserted here.
 */
public final class ServiceCredentialTest {
    /** Twenty printable ASCII characters, twenty distinct, four character categories. */
    private static final String STRONG = "A1b2C3d4E5f6G7h8!9xy";

    @Test
    public void generatedCredentialIsSixtyFourLowercaseHexCharacters() {
        ServiceCredential credential = ServiceCredential.generate();
        try {
            char[] value = credential.reveal(true);
            assertEquals(64, value.length);
            for (char c : value) {
                boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
                assertTrue("generated credential must be lowercase hex only: " + c, hex);
            }
        } finally {
            credential.close();
        }
    }

    @Test
    public void generatedCredentialCoversTheHexAlphabetAndDiffersPerCall() {
        Set<Character> observed = new HashSet<>();
        String previous = null;
        for (int i = 0; i < 16; i++) {
            ServiceCredential credential = ServiceCredential.generate();
            try {
                String value = new String(credential.reveal(true));
                if (previous != null) {
                    assertNotEquals("two generated credentials must differ", previous, value);
                }
                previous = value;
                for (char c : value.toCharArray()) {
                    observed.add(c);
                }
            } finally {
                credential.close();
            }
        }
        assertEquals("1024 random hex digits must cover the whole alphabet", 16, observed.size());
    }

    @Test
    public void revealRequiresTheExplicitRequestFlag() {
        ServiceCredential credential = ServiceCredential.generate();
        try {
            assertThrows(SecurityException.class, () -> credential.reveal(false));
        } finally {
            credential.close();
        }
        ServiceCredential input = ServiceCredential.fromUserInput(STRONG.toCharArray());
        try {
            assertThrows(SecurityException.class, () -> input.reveal(false));
            assertEquals(STRONG, new String(input.reveal(true)));
        } finally {
            input.close();
        }
    }

    @Test
    public void revealReturnsADefensiveCopyNotTheStoredBuffer() {
        ServiceCredential credential = ServiceCredential.fromUserInput(STRONG.toCharArray());
        try {
            char[] first = credential.reveal(true);
            Arrays.fill(first, 'z');
            assertEquals(STRONG, new String(credential.reveal(true)));
        } finally {
            credential.close();
        }
    }

    @Test
    public void fromUserInputClonesTheCallerBuffer() {
        char[] caller = STRONG.toCharArray();
        ServiceCredential credential = ServiceCredential.fromUserInput(caller);
        try {
            Arrays.fill(caller, 'q');
            assertEquals(STRONG, new String(credential.reveal(true)));
        } finally {
            credential.close();
        }
    }

    @Test
    public void closeWipesTheBufferAndEveryLaterAccessIsRefused() {
        ServiceCredential credential = ServiceCredential.fromUserInput(STRONG.toCharArray());
        credential.close();
        assertThrows(IllegalStateException.class, () -> credential.reveal(true));
        assertThrows(IllegalStateException.class, credential::requireAvailable);
        // Closing twice is idempotent; a double wipe must not throw.
        credential.close();
    }

    @Test
    public void theAuthorizationGateIsCheckedBeforeTheClosedState() {
        ServiceCredential credential = ServiceCredential.generate();
        credential.close();
        // reveal(false) stays an authorization failure, not a lifecycle failure.
        assertThrows(SecurityException.class, () -> credential.reveal(false));
    }

    @Test
    public void requireAvailableAcceptsAnOpenCredential() {
        ServiceCredential credential = ServiceCredential.generate();
        try {
            credential.requireAvailable();
        } finally {
            credential.close();
        }
    }

    @Test
    public void userInputFloorRejectsLengthsOutsideTwentyToTwoHundredFiftySix() {
        assertEquals(20, STRONG.length());
        // Exactly twenty is admitted; nineteen is not.
        ServiceCredential boundary = ServiceCredential.fromUserInput(STRONG.toCharArray());
        boundary.close();
        assertThrows(IllegalArgumentException.class,
                () -> ServiceCredential.fromUserInput(STRONG.substring(0, 19).toCharArray()));

        char[] maximal = (STRONG + "a".repeat(236)).toCharArray();
        assertEquals(256, maximal.length);
        ServiceCredential upper = ServiceCredential.fromUserInput(maximal);
        upper.close();
        assertThrows(IllegalArgumentException.class,
                () -> ServiceCredential.fromUserInput((STRONG + "a".repeat(237)).toCharArray()));

        assertThrows(IllegalArgumentException.class, () -> ServiceCredential.fromUserInput(null));
        assertThrows(IllegalArgumentException.class, () -> ServiceCredential.fromUserInput(new char[0]));
    }

    @Test
    public void userInputFloorRejectsFewerThanTwelveDistinctCharacters() {
        // Twenty characters, three categories, but only three distinct characters.
        assertThrows(IllegalArgumentException.class,
                () -> ServiceCredential.fromUserInput(("Aa1Aa1Aa1" + "Aa1Aa1Aa1" + "Aa1A").toCharArray()));
        // Twenty distinct characters is comfortably above the floor.
        ServiceCredential accepted = ServiceCredential.fromUserInput(("Aa1Bb2Cc3Dd4" + "Ee5Ff6Gh").toCharArray());
        accepted.close();
    }

    @Test
    public void userInputFloorRequiresThreeCharacterCategories() {
        // Twenty distinct lowercase/digit characters: two categories only.
        assertThrows(IllegalArgumentException.class,
                () -> ServiceCredential.fromUserInput("a1b2c3d4e5f6g7h8i9j0".toCharArray()));
        // Symbols count as a category of their own.
        ServiceCredential symbols = ServiceCredential.fromUserInput("ab1cd2ef3gh4ij5kl6!m".toCharArray());
        symbols.close();
        // Lowercase plus uppercase plus digits is a third admissible combination.
        ServiceCredential lettersAndDigits = ServiceCredential.fromUserInput("A1b2C3d4E5f6G7h8I9j0".toCharArray());
        lettersAndDigits.close();
    }

    @Test
    public void userInputRejectsNonPrintableAsciiWithoutEchoingTheValue() {
        String withSpace = STRONG.substring(0, 19) + " ";
        String withDelete = STRONG.substring(0, 19) + "\u007f";
        String withControl = STRONG.substring(0, 19) + "\n";
        for (String candidate : new String[] {withSpace, withDelete, withControl}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> ServiceCredential.fromUserInput(candidate.toCharArray()));
            assertEquals("Invalid credential character", error.getMessage());
            assertFalse("the rejected value must not appear in the error",
                    error.getMessage().contains(candidate.substring(0, 19)));
        }
    }

    @Test
    public void strengthFloorFailuresCarryTheFloorMessageOnly() {
        IllegalArgumentException shortInput = assertThrows(IllegalArgumentException.class,
                () -> ServiceCredential.fromUserInput("Aa1!Aa1!".toCharArray()));
        assertEquals("Credential does not meet the strength floor", shortInput.getMessage());
        IllegalArgumentException weak = assertThrows(IllegalArgumentException.class,
                () -> ServiceCredential.fromUserInput("a1b2c3d4e5f6g7h8i9j0".toCharArray()));
        assertEquals("Credential does not meet the strength floor", weak.getMessage());
    }

    @Test
    public void toStringNeverExposesTheSecret() {
        ServiceCredential credential = ServiceCredential.fromUserInput(STRONG.toCharArray());
        try {
            String rendered = credential.toString();
            assertEquals("ServiceCredential[redacted]", rendered);
            assertFalse(rendered.contains(STRONG.substring(0, 8)));
        } finally {
            credential.close();
        }
    }
}
