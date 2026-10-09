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

    private Button btnCtrl;
    private Button btnAlt;

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
        addKeyButton(row, "^C", v -> {
            if (inputSink != null) {
                inputSink.sendBytes(new byte[]{0x03});
            }
        });

        // Symbols: |, -, /, ~, $, \
        addKeyButton(row, "|", v -> pressSymbol("|"));
        addKeyButton(row, "-", v -> pressSymbol("-"));
        addKeyButton(row, "/", v -> pressSymbol("/"));
        addKeyButton(row, "~", v -> pressSymbol("~"));
        addKeyButton(row, "$", v -> pressSymbol("$"));
        addKeyButton(row, "\\", v -> pressSymbol("\\"));

        // Arrows: ↑, ↓, ←, →
        addKeyButton(row, "↑", v -> pressUp());
        addKeyButton(row, "↓", v -> pressDown());
        addKeyButton(row, "←", v -> pressLeft());
        addKeyButton(row, "→", v -> pressRight());

        addView(row, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    public void pressEsc() {
        if (inputSink != null) {
            inputSink.sendBytes(new byte[]{0x1B});
        }
    }

    public void pressTab() {
        if (targetInput != null && targetInput.getText().length() > 0) {
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

    public void pressUp() {
        sendArrowSequence("\u001B[A");
    }

    public void pressDown() {
        sendArrowSequence("\u001B[B");
    }

    public void pressLeft() {
        sendArrowSequence("\u001B[D");
    }

    public void pressRight() {
        sendArrowSequence("\u001B[C");
    }

    public void pressSymbol(String symbol) {
        insertTextIntoInput(symbol);
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

        int minW = dpToPx(context, 38);
        int h = dpToPx(context, 36);
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
