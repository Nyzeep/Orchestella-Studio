package io.github.nyzeep.droidlayer;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import io.github.nyzeep.droidlayer.runtime.engineavf.AvfVirtualMachineEngine;
import java.io.InputStream;
import java.io.OutputStream;
import android.net.LocalSocket;
import java.nio.charset.StandardCharsets;

/**
 * Minimal root-shell terminal over the instance's terminal channel (the
 * abstract guest console socket under the QEMU strategy, the vsock terminal
 * channel under the AVF strategy). Input lines are sent raw; output bytes
 * append to a monospace transcript. The channel is transport only: the Full
 * Linux contract's terminal behavior is unchanged.
 */
public class TerminalActivity extends Activity {
    private Handler ui;
    private TextView transcript;
    private LocalSocket socket;
    private AvfVirtualMachineEngine.AvfVmSession avfSession;
    private OutputStream terminalOut;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String controlSocket = getIntent().getStringExtra("controlSocket");
        String terminalSocket = getIntent().getStringExtra("terminalSocket");
        String bridgeToken = getIntent().getStringExtra("bridgeToken");
        String instanceId = getIntent().getStringExtra("instanceId");
        avfSession = AvfTerminalStreamsRegistry.get(instanceId);
        ui = new Handler(Looper.getMainLooper());

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        transcript = new TextView(this);
        transcript.setTypeface(android.graphics.Typeface.MONOSPACE);
        transcript.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(transcript);
        final EditText input = new EditText(this);
        input.setHint("shell command");
        input.setSingleLine(true);
        layout.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        layout.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(layout);

        append("DroidLayer terminal (" + instanceId + ")\n");
        new Thread(() -> {
            try {
                if (avfSession != null) {
                    terminalOut = avfSession.terminalChannel().outputStream();
                    append("[terminal connected]\n");
                    pumpTerminal(avfSession.terminalChannel().inputStream());
                } else {
                    BridgeConnector connector = new BridgeConnector();
                    socket = connector.connectTerminal(terminalSocket);
                    terminalOut = socket.getOutputStream();
                    append("[terminal connected]\n");
                    pumpTerminal(socket.getInputStream());
                }
            } catch (Exception e) {
                append("[terminal error: " + e + "]\n");
            }
        }, "terminal-io").start();
        // The bridge runs as the host initiator (HELLO/READY, PING/PONG,
        // RESIZE); the console stays clean for the shell and the session
        // milestones are echoed into the transcript for visibility.
        scroll.post(() -> {
            float charWidth = transcript.getPaint().measureText("M");
            float lineHeight = transcript.getPaint().getFontSpacing();
            int columns = (int) (scroll.getWidth() / charWidth);
            int rows = (int) (scroll.getHeight() / lineHeight);
            final int gridColumns = columns > 0 ? columns : 80;
            final int gridRows = rows > 0 ? rows : 24;
            new Thread(() -> {
                try {
                    AndroidNotificationAdapter notificationAdapter =
                            new AndroidNotificationAdapter(TerminalActivity.this);
                    AndroidUrlOpenerAdapter urlOpenerAdapter =
                            new AndroidUrlOpenerAdapter(TerminalActivity.this);
                    if (avfSession != null) {
                        new BridgeConnector().serveStreams(
                                avfSession.controlChannel().inputStream(),
                                avfSession.controlChannel().outputStream(),
                                bridgeToken, gridColumns, gridRows,
                                eventsForBridge(), notificationAdapter, urlOpenerAdapter);
                    } else {
                        new BridgeConnector().serve(controlSocket, bridgeToken, gridColumns, gridRows,
                                eventsForBridge(), notificationAdapter, urlOpenerAdapter);
                    }
                } catch (Exception e) {
                    append("[bridge ended: " + e.getClass().getSimpleName() + "]\n");
                }
            }, "bridge-serve").start();
        });

        input.setOnEditorActionListener((v, actionId, event) -> {
            String line = input.getText().toString();
            input.setText("");
            send(line + "\n");
            return true;
        });
    }

    private BridgeConnector.Events eventsForBridge() {
        return new BridgeConnector.Events() {
            @Override
            public void onReady() {
                append("[bridge ready]\n");
            }

            @Override
            public void onPong() {
                append("[bridge pong]\n");
            }

            @Override
            public void onResize(int columns, int rows) {
                append("[bridge resize " + columns + "x" + rows + "]\n");
            }

            @Override
            public void onNotification(String title, String message) {
                append("[bridge notify: " + title + " - " + message + "]\n");
            }

            @Override
            public void onOpenUrl(String url) {
                append("[bridge open-url: " + url + "]\n");
            }
        };
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        String instanceId = getIntent() != null ? getIntent().getStringExtra("instanceId") : null;
        if (avfSession != null) {
            AvfTerminalStreamsRegistry.unregister(instanceId);
            try {
                avfSession.closeChannels();
            } catch (Exception ignored) {
                // channel cleanup is best-effort at teardown
            }
        }
    }

    private void pumpTerminal(InputStream in) {
        try (InputStream stream = in) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                final String chunk = new String(buffer, 0, count, StandardCharsets.UTF_8);
                append(chunk);
            }
        } catch (Exception e) {
            append("[terminal stream ended]\n");
        }
    }

    private void send(String line) {
        OutputStream out = terminalOut;
        if (out == null) {
            return;
        }
        new Thread(() -> {
            try {
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception e) {
                append("[send failed]\n");
            }
        }, "terminal-send").start();
    }

    private void append(String text) {
        ui.post(() -> {
            transcript.append(text);
            View parent = (View) transcript.getParent();
            parent.scrollTo(0, (int) transcript.getTextSize() * transcript.getLineCount());
        });
    }
}
