package io.github.nyzeep.orchestella;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import io.github.nyzeep.orchestella.StickyModifierStateMachine.Modifier;
import io.github.nyzeep.orchestella.StickyModifierStateMachine.State;
import java.nio.charset.StandardCharsets;

/**
 * Horizontal scrolling auxiliary key bar attached above the soft keyboard.
 * Provides high-frequency developer keys (ESC, TAB, CTRL, ALT, |, -, /, ~, $, \, arrows)
 * and coordinates with {@link StickyModifierStateMachine}.
 */
public class MobileAuxiliaryKeyBar extends HorizontalScrollView {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TerminalInputSink inputSink;
    private StickyModifierStateMachine stateMachine;
    private EditText targetInput;
    private InteractiveTerminalEngine terminalEngine;

    private Button btnCtrl;
    private Button btnAlt;

    public void setTerminalEngine(InteractiveTerminalEngine terminalEngine) {
        this.terminalEngine = terminalEngine;
    }

    public InteractiveTerminalEngine getTerminalEngine() {
        return terminalEngine;
    }

    public MobileAuxiliaryKeyBar(Context context) {
        super(context);
        init(context);
    }

    public MobileAuxiliaryKeyBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public MobileAuxiliaryKeyBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    public void setInputSink(TerminalInputSink inputSink) {
        this.inputSink = inputSink;
    }

    public void setStateMachine(StickyModifierStateMachine stateMachine) {
        this.stateMachine = stateMachine;
        if (stateMachine != null) {
            stateMachine.addListener((modifier, state) -> {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    updateModifierVisual(modifier, state);
                } else {
                    mainHandler.post(() -> updateModifierVisual(modifier, state));
                }
            });
            updateModifierVisual(Modifier.CTRL, stateMachine.getState(Modifier.CTRL));
            updateModifierVisual(Modifier.ALT, stateMachine.getState(Modifier.ALT));
        }
    }

    public void setTargetInput(EditText targetInput) {
        this.targetInput = targetInput;
    }

    public StickyModifierStateMachine getStateMachine() {
        return stateMachine;
    }

    private void init(Context context) {
        setBackgroundColor(0xFF21222C); // Dracula dark bar background
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dpToPx(context, 8);
        int padV = dpToPx(context, 4);
        row.setPadding(padH, padV, padH, padV);

        // ESC
        addKeyButton(row, "ESC", v -> pressEsc());

        // TAB
        addKeyButton(row, "TAB", v -> pressTab());

        // CTRL
        btnCtrl = addKeyButton(row, "CTRL", v -> pressCtrl());

        // ALT
        btnAlt = addKeyButton(row, "ALT", v -> pressAlt());

        // ^C (Interrupt/Break)
        addKeyButton(row, "^C", v -> pressCtrlC());

        // CLR (Clear screen and reset line)
        addKeyButton(row, "CLR", v -> pressClr());

        // PASTE (Clipboard quick paste)
        addKeyButton(row, "PASTE", v -> pressPaste());

        // HOME / END (Line boundary cursor jumps)
        addKeyButton(row, "HOME", v -> pressHome());
        addKeyButton(row, "END", v -> pressEnd());

        // Symbols: `, |, ~, $, /, \, -
        addKeyButton(row, "`", v -> pressSymbol("`"));
        addKeyButton(row, "|", v -> pressSymbol("|"));
        addKeyButton(row, "~", v -> pressSymbol("~"));
        addKeyButton(row, "$", v -> pressSymbol("$"));
        addKeyButton(row, "/", v -> pressSymbol("/"));
        addKeyButton(row, "\\", v -> pressSymbol("\\"));
        addKeyButton(row, "-", v -> pressSymbol("-"));

        // Arrows: ←, ↑, ↓, →
        addKeyButton(row, "←", v -> pressLeft());
        addKeyButton(row, "↑", v -> pressUp());
        addKeyButton(row, "↓", v -> pressDown());
        addKeyButton(row, "→", v -> pressRight());

        addView(row, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    public void pressEsc() {
        if (inputSink != null) {
            inputSink.sendBytes(new byte[]{0x1B});
        }
    }

    public void pressTab() {
        if (terminalEngine != null) {
            terminalEngine.handleTab();
        } else if (targetInput != null && targetInput.getText().length() > 0) {
            String current = targetInput.getText().toString();
            targetInput.setText("");
            if (inputSink != null) {
                inputSink.sendText(current + "\t");
            }
        } else if (inputSink != null) {
            inputSink.sendBytes(new byte[]{'\t'});
        }
    }

    public void pressCtrl() {
        if (stateMachine != null) {
            stateMachine.onModifierClicked(Modifier.CTRL);
        }
    }

    public void pressAlt() {
        if (stateMachine != null) {
            stateMachine.onModifierClicked(Modifier.ALT);
        }
    }

    public void pressCtrlC() {
        if (terminalEngine != null) {
            terminalEngine.handleCtrlC();
        } else if (inputSink != null) {
            inputSink.sendBytes(new byte[]{0x03});
        }
    }

    public void pressClr() {
        if (terminalEngine != null) {
            terminalEngine.handleClearScreenAndResetLine();
        } else if (targetInput != null) {
            targetInput.setText("");
            if (inputSink != null) {
                inputSink.sendBytes(new byte[]{0x0C});
            }
        } else if (inputSink != null) {
            inputSink.sendBytes(new byte[]{0x0C});
        }
    }

    public void pressPaste() {
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            android.content.ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).coerceToText(getContext());
                if (text != null && text.length() > 0) {
                    String str = text.toString();
                    if (terminalEngine != null) {
                        terminalEngine.handleTextInserted(str);
                    } else if (targetInput != null) {
                        insertTextIntoInput(str);
                    } else if (inputSink != null) {
                        inputSink.sendText(str);
                    }
                }
            }
        }
    }

    public void pressHome() {
        if (terminalEngine != null) {
            terminalEngine.handleHome();
        } else if (targetInput != null) {
            targetInput.setSelection(0);
        } else {
            sendArrowSequence("\u001B[H");
        }
    }

    public void pressEnd() {
        if (terminalEngine != null) {
            terminalEngine.handleEnd();
        } else if (targetInput != null) {
            targetInput.setSelection(targetInput.getText().length());
        } else {
            sendArrowSequence("\u001B[F");
        }
    }

    public void pressUp() {
        if (terminalEngine != null) {
            terminalEngine.handleArrowUp();
        } else {
            sendArrowSequence("\u001B[A");
        }
    }

    public void pressDown() {
        if (terminalEngine != null) {
            terminalEngine.handleArrowDown();
        } else {
            sendArrowSequence("\u001B[B");
        }
    }

    public void pressLeft() {
        if (terminalEngine != null) {
            terminalEngine.handleArrowLeft();
        } else {
            sendArrowSequence("\u001B[D");
        }
    }

    public void pressRight() {
        if (terminalEngine != null) {
            terminalEngine.handleArrowRight();
        } else {
            sendArrowSequence("\u001B[C");
        }
    }

    public void pressSymbol(String symbol) {
        if (targetInput != null) {
            insertTextIntoInput(symbol);
        } else if (terminalEngine != null) {
            terminalEngine.handleTextInserted(symbol);
        } else if (inputSink != null) {
            inputSink.sendText(symbol);
        }
    }

    private Button addKeyButton(LinearLayout container, String label, OnClickListener listener) {
        Context context = getContext();
        Button btn = new Button(context, null, android.R.attr.buttonStyleSmall);
        btn.setText(label);
        btn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        btn.setTextColor(DraculaPalette.FOREGROUND);
        btn.setBackground(createKeyBackground(0xFF343746, 0xFF44475A));
        btn.setAllCaps(false);

        int minW = dpToPx(context, 42);
        int h = dpToPx(context, 44);
        btn.setMinimumWidth(minW);
        btn.setMinWidth(minW);
        btn.setHeight(h);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, h);
        int margin = dpToPx(context, 3);
        lp.setMargins(margin, 0, margin, 0);

        btn.setOnClickListener(listener);
        container.addView(btn, lp);
        return btn;
    }

    private void insertTextIntoInput(String symbol) {
        if (targetInput != null) {
            int start = targetInput.getSelectionStart();
            int end = targetInput.getSelectionEnd();
            if (start >= 0 && end >= 0) {
                targetInput.getText().replace(Math.min(start, end), Math.max(start, end), symbol);
            } else {
                targetInput.append(symbol);
            }
            targetInput.requestFocus();
        } else if (inputSink != null) {
            inputSink.sendText(symbol);
        }
    }

    private void sendArrowSequence(String seq) {
        if (inputSink != null) {
            inputSink.sendBytes(seq.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void updateModifierVisual(Modifier modifier, State state) {
        Button targetBtn = (modifier == Modifier.CTRL) ? btnCtrl : btnAlt;
        if (targetBtn == null) {
            return;
        }

        switch (state) {
            case LATCHED:
                // Highlighting active latched modifier: Dracula Purple background with dark text
                targetBtn.setBackground(createKeyBackground(0xFFBD93F9, 0xFFBD93F9));
                targetBtn.setTextColor(0xFF282A36);
                break;
            case LOCKED:
                // Locked modifier: Dracula Green background with dark text
                targetBtn.setBackground(createKeyBackground(0xFF50FA7B, 0xFF50FA7B));
                targetBtn.setTextColor(0xFF282A36);
                break;
            case OFF:
            default:
                // Normal released modifier
                targetBtn.setBackground(createKeyBackground(0xFF343746, 0xFF44475A));
                targetBtn.setTextColor(DraculaPalette.FOREGROUND);
                break;
        }
    }

    private static GradientDrawable createKeyBackground(int fillColor, int strokeColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(fillColor);
        gd.setCornerRadius(10f);
        gd.setStroke(2, strokeColor);
        return gd;
    }

    private static int dpToPx(Context context, int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics());
    }
}
