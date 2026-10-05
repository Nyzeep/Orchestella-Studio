package io.github.nyzeep.droidlayer;

import android.content.Intent;
import org.junit.Test;

public final class BootCompletedReceiverTest {
    @Test
    public void ignoresNullIntentOrContext() {
        BootCompletedReceiver receiver = new BootCompletedReceiver();
        receiver.onReceive(null, null);
        receiver.onReceive(null, new Intent(Intent.ACTION_BOOT_COMPLETED));
    }

    @Test
    public void ignoresNonBootCompletedAction() {
        BootCompletedReceiver receiver = new BootCompletedReceiver();
        receiver.onReceive(null, new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED));
    }
}
