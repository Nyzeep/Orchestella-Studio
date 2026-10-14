package io.github.nyzeep.orchestella.runtime.instance;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Immutable user credentials and hostname configuration for Full Linux instances (Ticket 03).
 *
 * <p>Enforces strict username syntax ({@code ^[a-z_][a-z0-9_-]{1,15}$}), provides the standard
 * {@link #defaultRoot()} factory, and supports serialization across instance lifecycle persistence.
 */
public final class InstanceUserConfig {

    public static final String DEFAULT_HOSTNAME = "orchestella";
    public static final String DEFAULT_ROOT_USER = "root";

    private static final SecureRandom PASSWORD_RANDOM = new SecureRandom();
    private static final char[] PASSWORD_CHARS =
            "23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();
    private static final int PASSWORD_LENGTH = 24;

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z_][a-z0-9_-]{1,15}$");
    private static final Pattern HOSTNAME_PATTERN = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");

    private final String username;
    private final String password;
    private final boolean hasSudo;
    private final String hostname;

    public InstanceUserConfig(String username, String password, boolean hasSudo, String hostname) {
        if (!isValidUsername(username)) {
            throw new IllegalArgumentException(
                    "Invalid username: '" + username + "'. Must match pattern ^[a-z_][a-z0-9_-]{1,15}$");
        }
        if (password == null || password.trim().isEmpty()) {
            throw new IllegalArgumentException("Password must not be null or blank");
        }
        String normalizedHost = normalizeHostname(hostname);
        if (!isValidHostname(normalizedHost)) {
            throw new IllegalArgumentException(
                    "Invalid hostname: '" + hostname + "'. Must match pattern ^[a-z0-9][a-z0-9-]{0,62}$");
        }

        this.username = username;
        this.password = password;
        this.hasSudo = hasSudo;
        this.hostname = normalizedHost;
    }

    /** Creates a fresh root configuration with a strong per-instance credential (ADR-0010). */
    public static InstanceUserConfig defaultRoot() {
        return new InstanceUserConfig(DEFAULT_ROOT_USER, newPassword(), true, DEFAULT_HOSTNAME);
    }

    static String newPassword() {
        char[] password = new char[PASSWORD_LENGTH];
        for (int i = 0; i < password.length; i++) {
            password[i] = PASSWORD_CHARS[PASSWORD_RANDOM.nextInt(PASSWORD_CHARS.length)];
        }
        return new String(password);
    }

    /**
     * Validates whether a username complies with Linux username specifications:
     * starts with a lowercase letter or underscore, length 2 to 16, alphanumeric plus '-' and '_'.
     */
    public static boolean isValidUsername(String username) {
        if (username == null) {
            return false;
        }
        return USERNAME_PATTERN.matcher(username).matches();
    }

    /**
     * Validates whether a hostname complies with Linux hostname rules.
     */
    public static boolean isValidHostname(String hostname) {
        if (hostname == null) {
            return false;
        }
        return HOSTNAME_PATTERN.matcher(hostname).matches();
    }

    /** Canonical identity validation is usable without inventing an unknown legacy password. */
    public static String normalizeHostname(String hostname) {
        String normalized = hostname == null || hostname.trim().isEmpty()
                ? DEFAULT_HOSTNAME : hostname.trim().toLowerCase(java.util.Locale.ROOT);
        if (!isValidHostname(normalized)) {
            throw new IllegalArgumentException("invalid guest hostname");
        }
        return normalized;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public boolean hasSudo() {
        return hasSudo;
    }

    public String hostname() {
        return hostname;
    }

    public Properties toProperties() {
        Properties properties = new Properties();
        properties.setProperty("username", username);
        properties.setProperty("password", password);
        properties.setProperty("hasSudo", Boolean.toString(hasSudo));
        properties.setProperty("hostname", hostname);
        return properties;
    }

    public static InstanceUserConfig fromProperties(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        String user = properties.getProperty("username", DEFAULT_ROOT_USER);
        if (!isValidUsername(user)) {
            throw new IllegalArgumentException("invalid stored guest username");
        }
        String pass = properties.getProperty("password");
        if (pass == null || pass.trim().isEmpty()) {
            throw new IllegalArgumentException("stored user password is unknown");
        }
        String storedSudo = properties.getProperty("hasSudo", "false");
        if (!"true".equals(storedSudo) && !"false".equals(storedSudo)) {
            throw new IllegalArgumentException("invalid stored sudo choice");
        }
        boolean sudo = Boolean.parseBoolean(storedSudo);
        String host = properties.getProperty("hostname", DEFAULT_HOSTNAME);
        return new InstanceUserConfig(user, pass, sudo, host);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        InstanceUserConfig that = (InstanceUserConfig) o;
        return hasSudo == that.hasSudo
                && Objects.equals(username, that.username)
                && Objects.equals(password, that.password)
                && Objects.equals(hostname, that.hostname);
    }

    @Override
    public int hashCode() {
        return Objects.hash(username, password, hasSudo, hostname);
    }

    @Override
    public String toString() {
        return "InstanceUserConfig{"
                + "username='" + username + '\''
                + ", hasSudo=" + hasSudo
                + ", hostname='" + hostname + '\''
                + '}';
    }
}
