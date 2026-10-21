package io.github.nyzeep.orchestella.runtime.fastlane.proxy;

/** Typed proxy failures; the message never contains a target, URL, or credential. */
public enum ProxyFailure {
    PROXY_PROTOCOL_FAILED(0x01, 400, "Bad Request"),
    PROXY_CONNECT_FAILED(0x05, 502, "Bad Gateway"),
    PROXY_DNS_FAILED(0x04, 502, "Bad Gateway"),
    PROXY_TIMEOUT(0x06, 504, "Gateway Timeout"),
    PROXY_LIMIT_EXCEEDED(0x01, 503, "Service Unavailable"),
    PROXY_CANCELLED(0x01, 503, "Service Unavailable");

    private final int socksReply;
    private final int httpStatus;
    private final String httpReason;

    ProxyFailure(int socksReply, int httpStatus, String httpReason) {
        this.socksReply = socksReply;
        this.httpStatus = httpStatus;
        this.httpReason = httpReason;
    }

    public int socksReply() {
        return socksReply;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String httpReason() {
        return httpReason;
    }
}
