package io.github.nyzeep.orchestella.runtime.fastlane.storage;

import java.util.ArrayList;
import java.util.List;

/** Lexical path rules shared by the layout, tar reader, and import policy. */
final class StoragePaths {
    private StoragePaths() {}

    static boolean isSafeRelative(String path) {
        if (path == null || path.isEmpty() || path.length() > 4096 || path.charAt(0) == '/') {
            return false;
        }
        if (path.indexOf('\0') >= 0 || path.endsWith("/")) {
            return false;
        }
        int start = 0;
        for (;;) {
            final int slash = path.indexOf('/', start);
            final int end = slash < 0 ? path.length() : slash;
            if (end == start) {
                return false;
            }
            final String component = path.substring(start, end);
            if (".".equals(component) || "..".equals(component)) {
                return false;
            }
            if (slash < 0) {
                return true;
            }
            start = slash + 1;
        }
    }

    static List<String> components(String path) {
        final List<String> result = new ArrayList<>();
        int start = 0;
        for (;;) {
            final int slash = path.indexOf('/', start);
            if (slash < 0) {
                result.add(path.substring(start));
                return result;
            }
            result.add(path.substring(start, slash));
            start = slash + 1;
        }
    }

    static String baseName(String path) {
        final int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
