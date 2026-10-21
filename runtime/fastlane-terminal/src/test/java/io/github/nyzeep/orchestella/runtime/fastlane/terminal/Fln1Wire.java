package io.github.nyzeep.orchestella.runtime.fastlane.terminal;

import java.io.IOException;

/**
 * Two muxes connected through the real FLN1 codecs, so every state-machine
 * test also exercises opcode mapping, session-id ownership and payload layout.
 * The guest role is a test double; the production guest is the C payload.
 */
final class Fln1Wire {
    final TerminalMux host;
    final TerminalMux guest;
    final Fln1TerminalCodec hostCodec;
    final Fln1TerminalCodec guestCodec;
    final TerminalMuxTest.Recorder hostRecorder;
    final TerminalMuxTest.Recorder guestRecorder;

    private Fln1Wire(
            TerminalMux host,
            TerminalMux guest,
            Fln1TerminalCodec hostCodec,
            Fln1TerminalCodec guestCodec,
            TerminalMuxTest.Recorder hostRecorder,
            TerminalMuxTest.Recorder guestRecorder) {
        this.host = host;
        this.guest = guest;
        this.hostCodec = hostCodec;
        this.guestCodec = guestCodec;
        this.hostRecorder = hostRecorder;
        this.guestRecorder = guestRecorder;
    }

    static Fln1Wire create(int maxSessions) throws IOException {
        return create(maxSessions, null, null, true);
    }

    static Fln1Wire create(
            int maxSessions,
            TerminalMux.Listener hostListener,
            TerminalMux.Listener guestListener) throws IOException {
        return create(maxSessions, hostListener, guestListener, true);
    }

    static Fln1Wire create(
            int maxSessions,
            TerminalMux.Listener hostListener,
            TerminalMux.Listener guestListener,
            boolean handshake) throws IOException {
        Fln1TerminalCodec hostCodec = new Fln1TerminalCodec(TerminalMux.Role.HOST);
        Fln1TerminalCodec guestCodec = new Fln1TerminalCodec(TerminalMux.Role.GUEST);
        Fln1Wire[] holder = new Fln1Wire[1];

        TerminalMux.FrameSink hostSink = new TerminalMux.FrameSink() {
            @Override
            public void write(TerminalFrame frame) throws IOException {
                holder[0].guest.onFrame(holder[0].guestCodec.decode(
                        holder[0].hostCodec.encode(frame)));
            }

            @Override
            public void releaseSession(int sessionId) {
                holder[0].hostCodec.releaseSession(sessionId);
            }
        };
        TerminalMux.FrameSink guestSink = new TerminalMux.FrameSink() {
            @Override
            public void write(TerminalFrame frame) throws IOException {
                holder[0].host.onFrame(holder[0].hostCodec.decode(
                        holder[0].guestCodec.encode(frame)));
            }

            @Override
            public void releaseSession(int sessionId) {
                holder[0].guestCodec.releaseSession(sessionId);
            }
        };

        TerminalMuxTest.Recorder hostRecorder = new TerminalMuxTest.Recorder();
        TerminalMuxTest.Recorder guestRecorder = new TerminalMuxTest.Recorder();
        TerminalMux host = new TerminalMux(
                TerminalMux.Role.HOST, maxSessions, hostSink,
                hostListener == null ? hostRecorder : hostListener);
        TerminalMux guest = new TerminalMux(
                TerminalMux.Role.GUEST, maxSessions, guestSink,
                guestListener == null ? guestRecorder : guestListener);
        Fln1Wire wire = new Fln1Wire(
                host, guest, hostCodec, guestCodec, hostRecorder, guestRecorder);
        holder[0] = wire;
        if (handshake) {
            wire.handshake();
        }
        return wire;
    }

    void handshake() throws IOException {
        host.sendHello(new Fln1Payloads.Hello(1, 1, ""));
    }

    int open(int columns, int rows, String... argv) throws IOException {
        return host.openSession(columns, rows, argv);
    }

    int open(String name) throws IOException {
        return host.openSession(80, 24, "/mnt/apk/bin/sh", "-c", name);
    }

    void accept(int sessionId) throws IOException {
        guest.acceptSession(sessionId, 4242, Fln1Payloads.DEFAULT_INITIAL_WINDOW);
    }

    void accept(int sessionId, int window) throws IOException {
        guest.acceptSession(sessionId, 4242, window);
    }
}
