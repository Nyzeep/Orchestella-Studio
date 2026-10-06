package io.github.nyzeep.droidlayer.runtime.network.download;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Utility for constructing prioritized mirror URLs for release assets and distro rootfs images,
 * adhering to spec-m4 §3.4 and ADR-0019.
 */
public final class MirrorPriorityList {

    private MirrorPriorityList() {}

    /**
     * Builds a prioritized mirror list for a GitHub Release download URL:
     * <ol>
     *   <li>Official GitHub Release URL</li>
     *   <li>Global CDN Accelerator</li>
     *   <li>Domestic Fast Proxy (ghproxy.net)</li>
     * </ol>
     *
     * @param originalUrl base GitHub release download URL
     * @return unmodifiable list of prioritized mirror URLs
     */
    public static List<String> forGitHubRelease(String originalUrl) {
        Objects.requireNonNull(originalUrl, "originalUrl cannot be null");
        List<String> mirrors = new ArrayList<>();
        mirrors.add(originalUrl);

        if (originalUrl.contains("github.com/")) {
            // Global CDN accelerator
            mirrors.add(originalUrl.replace("github.com/", "download.fastgit.org/"));
            // Domestic proxy
            mirrors.add("https://ghproxy.net/" + originalUrl);
        }
        return Collections.unmodifiableList(mirrors);
    }

    /**
     * Creates an unmodifiable list of the given non-empty mirror URLs.
     */
    public static List<String> of(String... urls) {
        List<String> list = new ArrayList<>();
        if (urls != null) {
            for (String url : urls) {
                if (url != null && !url.trim().isEmpty()) {
                    list.add(url.trim());
                }
            }
        }
        return Collections.unmodifiableList(list);
    }
}
