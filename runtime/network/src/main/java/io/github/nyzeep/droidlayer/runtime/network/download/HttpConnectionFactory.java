package io.github.nyzeep.droidlayer.runtime.network.download;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Strategy interface for opening HTTP connections, allowing mock interception in tests.
 */
@FunctionalInterface
public interface HttpConnectionFactory {
    HttpURLConnection open(URL url) throws IOException;

    HttpConnectionFactory SYSTEM = url -> (HttpURLConnection) url.openConnection();
}
