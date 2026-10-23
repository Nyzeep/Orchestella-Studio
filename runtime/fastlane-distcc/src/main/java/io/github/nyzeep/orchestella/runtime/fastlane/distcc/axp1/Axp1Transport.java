package io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1;

import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/** Byte transport seam shared by vsock, TCP and in-memory tests. */
public interface Axp1Transport extends Closeable {
    InputStream input();

    OutputStream output();

    static Axp1Transport of(FastLaneChannel channel) {
        Objects.requireNonNull(channel, "channel");
        return new Axp1Transport() {
            @Override
            public InputStream input() {
                return channel.inputStream();
            }

            @Override
            public OutputStream output() {
                return channel.outputStream();
            }

            @Override
            public void close() throws IOException {
                channel.close();
            }
        };
    }
}
