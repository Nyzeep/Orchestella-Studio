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
import java.util.List;

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
    private boolean isHandlingModifier = false;
    private boolean isUpdatingFromEngine = false;
    private InteractiveTerminalEngine terminalEngine;
    private TerminalHistoryStore historyStore;
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

        terminalEngine = new InteractiveTerminalEngine(
                new InteractiveTerminalEngine.PromptInfo(
                        "root",
                        instanceId != null && !instanceId.isEmpty() ? instanceId : "orchestella",
                        "~",
                        "❯",
                        true));

        try {
            historyStore = new TerminalHistoryStore(getFilesDir().toPath().resolve("terminal_history"));
            if (instanceId != null) {
                List<String> existingHistory = historyStore.loadHistory(instanceId);
                terminalEngine.loadHistory(existingHistory);
            }
        } catch (Exception e) {
            android.util.Log.w("TerminalActivity", "Failed to initialize history store", e);
        }

        terminalEngine.setByteSink(bytes -> sendRaw(bytes));
        terminalEngine.setLineCommitListener((promptInfo, cmd) -> {
            if (terminalView != null && cmd != null && !cmd.isEmpty()) {
                terminalView.appendOutput(promptInfo.toPromptString() + cmd + "\n");
            }
            if (historyStore != null && instanceId != null) {
                historyStore.saveHistoryAsync(instanceId, terminalEngine.getHistory());
            }
        });
        terminalEngine.setClearRequestListener(() -> {
            if (terminalView != null) {
                terminalView.clear();
            }
        });
        terminalEngine.setTabCompletionHandler(new CommandCompletionEngine());
        terminalEngine.setTabCompletionListener((buf, pos, candidates) -> {
            if (terminalView != null && candidates != null && !candidates.isEmpty()) {
                terminalView.appendOutput(terminalEngine.renderCompletionListing(candidates) + "\n");
            }
        });

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
        btnSwitchDesktop.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        btnSwitchDesktop.setTextColor(DraculaPalette.FOREGROUND);
        btnSwitchDesktop.setBackground(createPillBackground(0xFF44475A, 0xFF6272A4, 8f));
        btnSwitchDesktop.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
        btnSwitchDesktop.setMinHeight(dpToPx(40));
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
        auxiliaryKeyBar.setTerminalEngine(terminalEngine);
        rootLayout.addView(auxiliaryKeyBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 4. Stylized Starship/Windows Terminal interactive shell prompt bar
        LinearLayout inputBar = new LinearLayout(this);
        inputBar.setOrientation(LinearLayout.HORIZONTAL);
        inputBar.setGravity(Gravity.CENTER_VERTICAL);
        inputBar.setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(8));
        inputBar.setBackgroundColor(0xFF21222C);

        // Prompt capsule: [root@orchestella ~] ❯
        LinearLayout promptCapsule = new LinearLayout(this);
        promptCapsule.setOrientation(LinearLayout.HORIZONTAL);
        promptCapsule.setGravity(Gravity.CENTER_VERTICAL);
        promptCapsule.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
        promptCapsule.setBackground(createPillBackground(0xFF282A36, 0xFF44475A, 10f));

        TextView tvContext = new TextView(this);
        tvContext.setText("root@" + (instanceId != null && !instanceId.isEmpty() ? instanceId : "orchestella") + " ~");
        tvContext.setTextColor(0xFF8BE9FD); // Dracula Cyan
        tvContext.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvContext.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        promptCapsule.addView(tvContext);

        TextView tvSymbol = new TextView(this);
        tvSymbol.setText(" ❯ ");
        tvSymbol.setTextColor(0xFF50FA7B); // Dracula Green
        tvSymbol.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvSymbol.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        promptCapsule.addView(tvSymbol);

        inputBar.addView(promptCapsule, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final EditText input = new EditText(this);
        input.setHint("command...");
        input.setHintTextColor(DraculaPalette.COMMENT);
        input.setTextColor(DraculaPalette.FOREGROUND);
        input.setBackground(null);
        input.setTypeface(Typeface.MONOSPACE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        inputLp.setMargins(dpToPx(6), 0, dpToPx(6), 0);
        inputBar.addView(input, inputLp);

        Button btnSend = new Button(this, null, android.R.attr.buttonStyleSmall);
        btnSend.setText("↵");
        btnSend.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        btnSend.setTextColor(DraculaPalette.FOREGROUND);
        btnSend.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        btnSend.setBackground(createPillBackground(0xFF44475A, 0xFF6272A4, 10f));
        btnSend.setOnClickListener(v -> {
            if (terminalEngine != null) {
                terminalEngine.syncBuffer(input.getText().toString(), input.getSelectionStart());
                input.setText("");
                terminalEngine.handleEnter();
            } else {
                String line = input.getText().toString();
                input.setText("");
                send(line);
            }
        });
        inputBar.addView(btnSend, new LinearLayout.LayoutParams(
                dpToPx(48), dpToPx(44)));

        rootLayout.addView(inputBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        auxiliaryKeyBar.setTargetInput(input);
        setContentView(rootLayout);

        terminalEngine.setLineChangeListener((p, buf, cursor) -> {
            if (!isUpdatingFromEngine && input != null && !input.getText().toString().equals(buf)) {
                isUpdatingFromEngine = true;
                try {
                    input.setText(buf);
                    input.setSelection(Math.min(cursor, buf.length()));
                } finally {
                    isUpdatingFromEngine = false;
                }
            }
        });

        terminalView.setOnClickListener(v -> {
            input.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });

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
                        return;
                    }
                }
                if (terminalEngine != null) {
                    terminalEngine.syncBuffer(s != null ? s.toString() : "", input.getSelectionStart());
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
                if (terminalEngine != null) {
                    terminalEngine.syncBuffer(input.getText().toString(), input.getSelectionStart());
                    input.setText("");
                    terminalEngine.handleEnter();
                } else {
                    String line = input.getText().toString();
                    input.setText("");
                    send(line);
                }
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
    protected void onPause() {
        super.onPause();
        persistTerminalHistory();
    }

    private void persistTerminalHistory() {
        if (historyStore != null && terminalEngine != null && instanceId != null) {
            historyStore.saveHistoryAsync(instanceId, terminalEngine.getHistory());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null) {
            String newInstanceId = intent.getStringExtra("instanceId");
            if (newInstanceId != null && !newInstanceId.isEmpty() && !newInstanceId.equals(this.instanceId)) {
                persistTerminalHistory();
                this.instanceId = newInstanceId;
                if (historyStore != null) {
                    terminalEngine.loadHistory(historyStore.loadHistory(newInstanceId));
                }
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
        persistTerminalHistory();
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

    private static android.graphics.drawable.GradientDrawable createPillBackground(
            int fillColor, int strokeColor, float cornerRadiusDp) {
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setColor(fillColor);
        gd.setCornerRadius(cornerRadiusDp * 2.5f);
        gd.setStroke(2, strokeColor);
        return gd;
    }
}
