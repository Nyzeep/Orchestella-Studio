package io.github.nyzeep.orchestella;

import java.net.InetSocketAddress;
import java.net.ServerSocket;

/** Test fixture: binds a fixed loopback port and waits to be killed. */
public final class PortHolder {
    public static void main(String[] args) throws Exception {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])));
            System.out.println("BOUND");
            System.out.flush();
            Thread.sleep(30_000L);
        }
    }
}
