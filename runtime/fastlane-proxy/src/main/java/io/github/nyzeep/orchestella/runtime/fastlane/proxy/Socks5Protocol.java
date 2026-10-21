package io.github.nyzeep.orchestella.runtime.fastlane.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/**
 * Server-side SOCKS5 (RFC 1928) with no authentication and CONNECT only.
 * IPv4 and domain names are accepted; IPv6 literals, BIND, and UDP ASSOCIATE are
 * rejected with the RFC reply codes.
 */
public final class Socks5Protocol {
    private static final int VERSION = 0x05;
    private static final int METHOD_NO_AUTH = 0x00;
    private static final int METHOD_NONE = 0xFF;
    private static final int COMMAND_CONNECT = 0x01;

    private Socks5Protocol() {}

    /**
     * Reads the greeting and CONNECT request and writes the method selection.
     * On a protocol failure it writes the applicable reply before throwing.
     */
    public static ProxyTarget readRequest(InputStream input, OutputStream output)
            throws IOException, ProxyException {
        final int version = readByte(input);
        if (version != VERSION) {
            throw protocol("bad socks version");
        }
        final int methodCount = readByte(input);
        if (methodCount == 0) {
            throw protocol("empty method list");
        }
        boolean noAuth = false;
        for (int index = 0; index < methodCount; index++) {
            if (readByte(input) == METHOD_NO_AUTH) {
                noAuth = true;
            }
        }
        if (!noAuth) {
            output.write(new byte[] {(byte) VERSION, (byte) METHOD_NONE});
            output.flush();
            throw new ProxyException(
                    ProxyFailure.PROXY_PROTOCOL_FAILED, "no acceptable authentication method");
        }
        output.write(new byte[] {(byte) VERSION, (byte) METHOD_NO_AUTH});
        output.flush();

        final int requestVersion = readByte(input);
        final int command = readByte(input);
        final int reserved = readByte(input);
        final int addressType = readByte(input);
        if (requestVersion != VERSION || reserved != 0) {
            throw protocol("malformed socks request");
        }
        if (command != COMMAND_CONNECT) {
            writeReply(output, 0x07, null, 0);
            throw protocol("only CONNECT is supported");
        }
        final String host;
        switch (addressType) {
            case 0x01: {
                final byte[] address = new byte[4];
                readFully(input, address);
                host = InetAddress.getByAddress(address).getHostAddress();
                break;
            }
            case 0x03: {
                final int length = readByte(input);
                if (length == 0) {
                    throw protocol("empty domain");
                }
                final byte[] name = new byte[length];
                readFully(input, name);
                host = decodeDomain(name);
                break;
            }
            case 0x04:
                writeReply(output, 0x08, null, 0);
                throw protocol("IPv6 targets are not supported");
            default:
                writeReply(output, 0x08, null, 0);
                throw protocol("unsupported address type");
        }
        final int port = (readByte(input) << 8) | readByte(input);
        if (port == 0) {
            throw protocol("invalid port");
        }
        try {
            return new ProxyTarget(host, port);
        } catch (IllegalArgumentException failure) {
            throw protocol("invalid target");
        }
    }

    public static void writeSuccess(OutputStream output, InetAddress bound, int port)
            throws IOException {
        writeReply(output, 0x00, bound, port);
    }

    public static void writeFailure(OutputStream output, ProxyFailure failure) throws IOException {
        writeReply(output, failure.socksReply(), null, 0);
    }

    private static void writeReply(OutputStream output, int reply, InetAddress address, int port)
            throws IOException {
        final byte[] message = new byte[10];
        message[0] = (byte) VERSION;
        message[1] = (byte) reply;
        message[2] = 0;
        message[3] = 0x01;
        if (address != null) {
            final byte[] raw = address.getAddress();
            if (raw.length == 4) {
                System.arraycopy(raw, 0, message, 4, 4);
            }
        }
        message[8] = (byte) (port >> 8);
        message[9] = (byte) port;
        output.write(message);
        output.flush();
    }

    private static String decodeDomain(byte[] name) throws ProxyException {
        final String host = new String(name, StandardCharsets.US_ASCII);
        if (host.isEmpty() || host.length() > 255) {
            throw protocol("invalid domain");
        }
        for (int index = 0; index < host.length(); index++) {
            final char character = host.charAt(index);
            final boolean valid = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '.' || character == '-' || character == '_';
            if (!valid) {
                throw protocol("invalid domain");
            }
        }
        return host;
    }

    private static int readByte(InputStream input) throws IOException, ProxyException {
        final int value = input.read();
        if (value < 0) {
            throw protocol("truncated socks request");
        }
        return value;
    }

    private static void readFully(InputStream input, byte[] destination)
            throws IOException, ProxyException {
        int offset = 0;
        while (offset < destination.length) {
            final int read = input.read(destination, offset, destination.length - offset);
            if (read < 0) {
                throw protocol("truncated socks request");
            }
            offset += read;
        }
    }

    private static ProxyException protocol(String message) {
        return new ProxyException(ProxyFailure.PROXY_PROTOCOL_FAILED, message);
    }
}
