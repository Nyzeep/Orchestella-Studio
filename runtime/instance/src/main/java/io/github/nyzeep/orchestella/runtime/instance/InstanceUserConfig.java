package io.github.nyzeep.orchestella.runtime.instance;

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
    public static final String DEFAULT_ROOT_PASSWORD = "1234";

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z_][a-z0-9_-]{1,15}$");
    private static final Pattern HOSTNAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");

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

    /**
     * Standard initial root configuration factory (Ticket 03).
     *
     * <p>Username: {@code "root"}, Password: {@code "1234"}, Hostname: {@code "orchestella"}, Sudo: {@code true}.
     */
    public static InstanceUserConfig defaultRoot() {
        return new InstanceUserConfig(DEFAULT_ROOT_USER, DEFAULT_ROOT_PASSWORD, true, DEFAULT_HOSTNAME);
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

    private static String normalizeHostname(String hostname) {
        if (hostname == null || hostname.trim().isEmpty()) {
            return DEFAULT_HOSTNAME;
        }
        return hostname.trim().toLowerCase();
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
            user = DEFAULT_ROOT_USER;
        }
        String pass = properties.getProperty("password", DEFAULT_ROOT_PASSWORD);
        if (pass == null || pass.trim().isEmpty()) {
            pass = DEFAULT_ROOT_PASSWORD;
        }
        boolean sudo = Boolean.parseBoolean(properties.getProperty("hasSudo", "true"));
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
