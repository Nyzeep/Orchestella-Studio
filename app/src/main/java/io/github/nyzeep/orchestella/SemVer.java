package io.github.nyzeep.orchestella;

/** SemVer 2.0.0 validation and precedence, independent of Android and numeric overflow. */
public final class SemVer implements Comparable<SemVer> {
    private final String[] core;
    private final String[] prerelease;

    private SemVer(String[] core, String[] prerelease) {
        this.core = core;
        this.prerelease = prerelease;
    }

    /** Returns null for an invalid version; whitespace and tag prefixes are not SemVer. */
    public static SemVer parse(String value) {
        if (value == null || value.isEmpty()) return null;
        String[] build = value.split("\\+", -1);
        if (build.length > 2 || (build.length == 2 && !validIdentifiers(build[1], false))) return null;
        int dash = build[0].indexOf('-');
        String coreText = dash < 0 ? build[0] : build[0].substring(0, dash);
        String[] core = coreText.split("\\.", -1);
        if (core.length != 3) return null;
        for (String number : core) {
            if (!isNumeric(number) || hasLeadingZero(number)) return null;
        }
        String[] prerelease = new String[0];
        if (dash >= 0) {
            String text = build[0].substring(dash + 1);
            if (!validIdentifiers(text, true)) return null;
            prerelease = text.split("\\.");
        }
        return new SemVer(core, prerelease);
    }

    /** Git tags may add the project's single lowercase v prefix. */
    public static SemVer parseTag(String tag) {
        return parse(tag != null && tag.startsWith("v") ? tag.substring(1) : tag);
    }

    public boolean isPrerelease() {
        return prerelease.length != 0;
    }

    @Override
    public int compareTo(SemVer other) {
        for (int i = 0; i < core.length; i++) {
            int order = compareNumeric(core[i], other.core[i]);
            if (order != 0) return order;
        }
        if (!isPrerelease()) return other.isPrerelease() ? 1 : 0;
        if (!other.isPrerelease()) return -1;
        for (int i = 0; i < Math.min(prerelease.length, other.prerelease.length); i++) {
            String left = prerelease[i];
            String right = other.prerelease[i];
            boolean leftNumeric = isNumeric(left);
            boolean rightNumeric = isNumeric(right);
            int order = leftNumeric && rightNumeric ? compareNumeric(left, right)
                    : leftNumeric != rightNumeric ? (leftNumeric ? -1 : 1) : left.compareTo(right);
            if (order != 0) return order;
        }
        return Integer.compare(prerelease.length, other.prerelease.length);
    }

    private static int compareNumeric(String left, String right) {
        int lengthOrder = Integer.compare(left.length(), right.length());
        return lengthOrder != 0 ? lengthOrder : left.compareTo(right);
    }

    private static boolean validIdentifiers(String text, boolean rejectLeadingZeros) {
        for (String id : text.split("\\.", -1)) {
            if (id.isEmpty()) return false;
            for (int i = 0; i < id.length(); i++) {
                char c = id.charAt(i);
                if (!(c >= '0' && c <= '9') && !(c >= 'A' && c <= 'Z')
                        && !(c >= 'a' && c <= 'z') && c != '-') return false;
            }
            if (rejectLeadingZeros && isNumeric(id) && hasLeadingZero(id)) return false;
        }
        return true;
    }

    private static boolean hasLeadingZero(String number) {
        return number.length() > 1 && number.charAt(0) == '0';
    }

    private static boolean isNumeric(String text) {
        if (text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') return false;
        }
        return true;
    }
}
