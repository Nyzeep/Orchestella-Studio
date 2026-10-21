package io.github.nyzeep.orchestella.runtime.fastlane.proxy;

import java.net.Socket;

/** Seam for tests and for future policy-aware connectors. */
public interface TargetConnector {
    Socket connect(String host, int port, int timeoutMs) throws ProxyException;
}
