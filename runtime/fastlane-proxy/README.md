# runtime/fastlane-proxy — design seam (P05)

**Topology.** The guest has no native network. A guest tool is pointed at a local
SOCKS5/HTTP-CONNECT endpoint; the payload (P02) is a byte-transparent bridge that
pairs that local connection with a host-opened vsock channel. The host
`FastLaneProxyServer` speaks the proxy protocol *over that channel*, resolves DNS on
the host, connects to the target, and splices bytes. The payload never parses the
proxy protocol. Host-local clients (tests, tooling) use the same handler through a
127.0.0.1 listener.

**Ownership.** One `ProxyTunnel` per connection owns both streams and both worker
threads; `FastLaneProxyServer` owns the listener, the active set, the idle watchdog,
and the connection counter. Closing the server closes the listener, every tunnel,
and the watchdog. A tunnel closes when both directions have reached EOF, when idle
timeout fires, or when the server closes.

**Handshake.** SOCKS5 (RFC 1928): greeting, method 0x00 only, CONNECT with ATYP 1
(IPv4) or 3 (domain); ATYP 4 (IPv6) and BIND/UDP are rejected with the RFC reply
codes. HTTP: `CONNECT host:port HTTP/1.x` with bounded headers, 200 on success,
400/405/501/502/504 on failure. DNS is resolved by the host; a domain name is never
resolved in the guest.

**Limits.** Bind address must be loopback (constructor rejects anything else).
`maxConnections` slots are reserved with a semaphore immediately after `accept()`, so
the limit covers the handshake as well as the tunnel; over-limit connections are
closed before a handshake, never queued unboundedly. On the loopback socket path,
connect, idle, and handshake timeouts are bounded; a guest channel's blocking read
has no socket timeout and is bounded only by the caller closing/cancelling it.
`bufferBytes` configures each pump. DNS runs on a fixed four-thread daemon pool with
a 16-entry queue and is bounded by the connect timeout because
`InetAddress.getAllByName` has no timeout of its own.

**Shutdown.** `close()` closes the listener, sockets/channels still in handshake, every
tunnel, and the watchdog. A slot is released exactly once, by the early-return path or
by the tunnel's `onClose`.

**Failure taxonomy.** `PROXY_CONNECT_FAILED`, `PROXY_DNS_FAILED`, `PROXY_TIMEOUT`,
`PROXY_PROTOCOL_FAILED`, `PROXY_LIMIT_EXCEEDED`, `PROXY_CANCELLED`. Reply codes are
derived from the failure, never from a string match.

**Privacy.** Counters only: accepted, active, bytes, failure code. No URL, path,
hostname, credential, or payload byte is logged.
