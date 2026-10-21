package io.github.nyzeep.orchestella.runtime.fastlane;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;

/** One bidirectional vsock channel owned by a Fast Lane session. */
public interface FastLaneChannel extends Closeable {
    InputStream inputStream();

    OutputStream outputStream();
}
