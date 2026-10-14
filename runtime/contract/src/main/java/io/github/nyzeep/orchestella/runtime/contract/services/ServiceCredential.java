package io.github.nyzeep.orchestella.runtime.contract.services;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Per-instance credential; caller owns and wipes copies used in local service configuration. */
public final class ServiceCredential implements AutoCloseable {
    private char[] value;
    private ServiceCredential(char[] value) { this.value = value.clone(); }

    public static ServiceCredential generate() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        char[] hex = new char[64];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < random.length; i++) {
            hex[i * 2] = digits[(random[i] & 255) >>> 4];
            hex[i * 2 + 1] = digits[random[i] & 15];
        }
        ServiceCredential result = new ServiceCredential(hex);
        Arrays.fill(random, (byte) 0);
        Arrays.fill(hex, '\0');
        return result;
    }

    /** Conservative input floor; generated credentials remain the default. No value enters errors. */
    public static ServiceCredential fromUserInput(char[] input) {
        if (input == null || input.length < 20 || input.length > 256) {
            throw new IllegalArgumentException("Credential does not meet the strength floor");
        }
        Set<Character> unique = new HashSet<>();
        int categories = 0;
        for (char c : input) {
            if (c < 33 || c > 126) { throw new IllegalArgumentException("Invalid credential character"); }
            unique.add(c);
            categories |= Character.isLowerCase(c) ? 1 : Character.isUpperCase(c) ? 2 : Character.isDigit(c) ? 4 : 8;
        }
        if (unique.size() < 12 || Integer.bitCount(categories) < 3) {
            throw new IllegalArgumentException("Credential does not meet the strength floor");
        }
        return new ServiceCredential(input);
    }

    /** Explicit user reveal or trusted local launch configuration only; never put this in a URI. */
    public synchronized char[] reveal(boolean explicitlyRequested) {
        if (!explicitlyRequested) { throw new SecurityException("Explicit credential access required"); }
        if (value == null) { throw new IllegalStateException("Credential is closed"); }
        return value.clone();
    }

    synchronized void requireAvailable() {
        if (value == null) { throw new IllegalStateException("Credential is closed"); }
    }

    @Override public synchronized void close() {
        if (value != null) { Arrays.fill(value, '\0'); value = null; }
    }

    @Override public String toString() { return "ServiceCredential[redacted]"; }
}
