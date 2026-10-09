package io.github.nyzeep.orchestella;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Modern Full Linux terminal activity featuring:
 * <ul>
 *   <li>ANSI 16/256/TrueColor VT100 renderer with Dracula palette via {@link ModernTerminalView};</li>
 *   <li>Sticky modifier extra keys bar via {@link MobileAuxiliaryKeyBar};</li>
 *   <li>Soft keyboard adaptive viewport scrolling and desktop switcher navigation.</li>
 * </ul>
 * Conforms to ADR-0002, US-26, and US-27.
 */
public class TerminalActivity extends Activity {

    private Handler ui;
    private ModernTerminalView terminalView;
    private MobileAuxiliaryKeyBar auxiliaryKeyBar;
    private StickyModifierStateMachine stickyStateMachine;
    private ExecutionSession session;
    private OutputStream terminalOut;
    private boolean isHandlingModifier = false;
    private String instanceId;
    private String bridgeToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bridgeToken = getIntent().getStringExtra("bridgeToken");
        instanceId = getIntent().getStringExtra("instanceId");
        session = ExecutionSessionRegistry.get(instanceId);
        ui = new Handler(Looper.getMainLooper());
        stickyStateMachine = new StickyModifierStateMachine();

        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(DraculaPalette.BACKGROUND);

        // 1. Top bar with instance title and desktop shuttle button
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10));
        topBar.setBackgroundColor(0xFF21222C);

        TextView titleView = new TextView(this);
        titleView.setText("Orchestella Terminal (" + instanceId + ")");
        titleView.setTextColor(DraculaPalette.FOREGROUND);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        titleView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        topBar.addView(titleView, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button btnSwitchDesktop = new Button(this, null, android.R.attr.buttonStyleSmall);
        btnSwitchDesktop.setId(R.id.btnSwitchDesktop);
        btnSwitchDesktop.setText(R.string.terminal_open_desktop);
        btnSwitchDesktop.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        btnSwitchDesktop.setOnClickListener(v -> {
            DualModeNavigation.navigateToDesktop(TerminalActivity.this, instanceId, bridgeToken);
        });
        topBar.addView(btnSwitchDesktop, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        rootLayout.addView(topBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 2. Modern ANSI terminal transcript view
        terminalView = new ModernTerminalView(this);
        rootLayout.addView(terminalView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 3. Mobile auxiliary key bar
        auxiliaryKeyBar = new MobileAuxiliaryKeyBar(this);
        TerminalInputSink inputSink = new TerminalInputSink() {
            @Override
            public void sendBytes(byte[] bytes) {
                sendRaw(bytes);
            }
        };
        auxiliaryKeyBar.setInputSink(inputSink);
        auxiliaryKeyBar.setStateMachine(stickyStateMachine);
        rootLayout.addView(auxiliaryKeyBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 4. Input row with monospace EditText and submit button
        LinearLayout inputBar = new LinearLayout(this);
        inputBar.setOrientation(LinearLayout.HORIZONTAL);
        inputBar.setGravity(Gravity.CENTER_VERTICAL);
        inputBar.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(8));
        inputBar.setBackgroundColor(0xFF21222C);

        final EditText input = new EditText(this);
        input.setHint("shell command...");
        input.setHintTextColor(DraculaPalette.COMMENT);
        input.setTextColor(DraculaPalette.FOREGROUND);
        input.setBackground(null);
        input.setTypeface(Typeface.MONOSPACE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        inputBar.addView(input, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button btnSend = new Button(this, null, android.R.attr.buttonStyleSmall);
        btnSend.setText("↵");
        btnSend.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        btnSend.setTextColor(DraculaPalette.FOREGROUND);
        btnSend.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        btnSend.setOnClickListener(v -> {
            String line = input.getText().toString();
            input.setText("");
            send(line);
        });
        inputBar.addView(btnSend, new LinearLayout.LayoutParams(
                dpToPx(42), dpToPx(36)));

        rootLayout.addView(inputBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        auxiliaryKeyBar.setTargetInput(input);
        setContentView(rootLayout);

        // Sticky modifier interception for typed characters
        input.addTextChangedListener(new TextWatcher() {
            private int changeStart;
            private int changeCount;

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                changeStart = start;
                changeCount = count;
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (isHandlingModifier) {
                    return;
                }
                if (changeCount == 1 && s != null && s.length() >= changeStart + 1) {
                    char typed = s.charAt(changeStart);
                    if (stickyStateMachine.isCtrlActive()) {
                        isHandlingModifier = true;
                        try {
                            s.delete(changeStart, changeStart + 1);
                            byte ctrlCode = StickyModifierStateMachine.toControlCode(typed);
                            sendRaw(new byte[]{ctrlCode});
                            stickyStateMachine.consumeLatched();
                        } finally {
                            isHandlingModifier = false;
                        }
                    } else if (stickyStateMachine.isAltActive()) {
                        isHandlingModifier = true;
                        try {
                            s.delete(changeStart, changeStart + 1);
                            byte[] altSeq = StickyModifierStateMachine.toAltSequence(typed);
                            sendRaw(altSeq);
                            stickyStateMachine.consumeLatched();
                        } finally {
                            isHandlingModifier = false;
                        }
                    }
                }
            }
        });

        input.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (stickyStateMachine.isCtrlActive()) {
                    char ch = (char) event.getUnicodeChar();
                    if (ch != 0) {
                        byte ctrlCode = StickyModifierStateMachine.toControlCode(ch);
                        sendRaw(new byte[]{ctrlCode});
                        stickyStateMachine.consumeLatched();
                        return true;
                    }
                } else if (stickyStateMachine.isAltActive()) {
                    char ch = (char) event.getUnicodeChar();
                    if (ch != 0) {
                        sendRaw(StickyModifierStateMachine.toAltSequence(ch));
                        stickyStateMachine.consumeLatched();
                        return true;
                    }
                }
            }
            return false;
        });

        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                String line = input.getText().toString();
                input.setText("");
                send(line);
                return true;
            }
            return false;
        });

        // WindowInsets handling for Android 15 Edge-to-Edge & Soft Keyboard (IME)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, insets) -> {
            Insets imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime());
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            int bottomInset = Math.max(imeInsets.bottom, systemBars.bottom);
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, bottomInset);
            if (imeInsets.bottom > 0) {
                terminalView.post(() -> terminalView.scrollToBottom());
            }
            return insets;
        });

        append("Orchestella Studio terminal (" + instanceId + ")\n");
        ExecutionSessionRegistry.LiveSession liveSession = ExecutionSessionRegistry.getLiveSession(instanceId);
        if (session == null && liveSession == null) {
            append("[no live session for instance " + instanceId + "]\n");
            return;
        }

        if (liveSession != null) {
            append("[terminal connected]\n");
            String history = liveSession.getTranscript();
            if (!history.isEmpty()) {
                append(history);
                terminalView.post(() -> terminalView.scrollToBottom());
            }
            liveSession.setOutputListener(chunk -> {
                if (terminalView != null) {
                    terminalView.appendOutput(chunk);
                }
            });
        }

        terminalView.post(() -> {
            float charWidth = terminalView.getTranscriptView().getPaint().measureText("M");
            float lineHeight = terminalView.getTranscriptView().getPaint().getFontSpacing();
            int columns = (int) (terminalView.getWidth() / (charWidth > 0 ? charWidth : 1));
            int rows = (int) (terminalView.getHeight() / (lineHeight > 0 ? lineHeight : 1));
            final int gridColumns = columns > 0 ? columns : 80;
            final int gridRows = rows > 0 ? rows : 24;
            if (session != null && session.markBridgeServing()) {
                new Thread(() -> {
                    try {
                        AndroidNotificationAdapter notificationAdapter =
                                new AndroidNotificationAdapter(TerminalActivity.this);
                        AndroidUrlOpenerAdapter urlOpenerAdapter =
                                new AndroidUrlOpenerAdapter(TerminalActivity.this);
                        new BridgeConnector().serveStreams(
                                session.controlOutput(), session.controlInput(), bridgeToken,
                                gridColumns, gridRows, eventsForBridge(), notificationAdapter,
                                urlOpenerAdapter,
                                new io.github.nyzeep.orchestella.runtime.contract.BridgeControl.Clipboard() {
                                    @Override
                                    public boolean onClipboardPut(String text) {
                                        io.github.nyzeep.orchestella.runtime.contract.BridgeControl.Clipboard cb =
                                                session != null ? session.clipboardBridge() : null;
                                        return cb != null && cb.onClipboardPut(text);
                                    }

                                    @Override
                                    public String onClipboardGet() {
                                        io.github.nyzeep.orchestella.runtime.contract.BridgeControl.Clipboard cb =
                                                session != null ? session.clipboardBridge() : null;
                                        return cb != null ? cb.onClipboardGet() : null;
                                    }
                                });
                    } catch (Exception e) {
                        append("[bridge ended: " + e.getClass().getSimpleName() + "]\n");
                    }
                }, "bridge-serve").start();
            }
        });

        handleCommandExtra(getIntent());
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
    protected void onResume() {
        super.onResume();
        if (terminalView != null) {
            terminalView.post(() -> terminalView.scrollToBottom());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null) {
            String newInstanceId = intent.getStringExtra("instanceId");
            if (newInstanceId != null && !newInstanceId.isEmpty()) {
                this.instanceId = newInstanceId;
            }
            String newBridgeToken = intent.getStringExtra("bridgeToken");
            if (newBridgeToken != null && !newBridgeToken.isEmpty()) {
                this.bridgeToken = newBridgeToken;
            }
        }
        handleCommandExtra(intent);
    }

    private void handleCommandExtra(Intent intent) {
        if (intent != null && intent.hasExtra("command")) {
            String cmd = intent.getStringExtra("command");
            if (cmd != null && !cmd.isEmpty()) {
                if (terminalView != null) {
                    terminalView.postDelayed(() -> send(cmd), 300);
                } else {
                    send(cmd);
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ExecutionSessionRegistry.LiveSession liveSession = ExecutionSessionRegistry.getLiveSession(this.instanceId);
        if (liveSession != null) {
            liveSession.setOutputListener(null);
        }
    }

    private void send(String line) {
        if (line != null) {
            String toSend = line.endsWith("\n")
                    ? line.substring(0, line.length() - 1) + "\r"
                    : line + "\r";
            sendRaw(toSend.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void sendRaw(byte[] bytes) {
        ExecutionSessionRegistry.LiveSession liveSession = ExecutionSessionRegistry.getLiveSession(this.instanceId);
        if (liveSession == null || bytes == null || bytes.length == 0) {
            return;
        }
        new Thread(() -> {
            try {
                liveSession.sendBytes(bytes);
            } catch (Exception e) {
                append("[send failed: " + e.getMessage() + "]\n");
            }
        }, "terminal-send").start();
    }

    private void append(String text) {
        if (terminalView != null) {
            terminalView.appendOutput(text);
        }
    }

    private boolean isHeadlessInstance(String instanceId) {
        return false;
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }
}
