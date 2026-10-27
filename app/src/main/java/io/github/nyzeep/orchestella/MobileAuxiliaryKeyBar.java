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
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import io.github.nyzeep.orchestella.StickyModifierStateMachine.Modifier;
import io.github.nyzeep.orchestella.StickyModifierStateMachine.State;
import io.github.nyzeep.orchestella.ui.theme.AppTheme;
import io.github.nyzeep.orchestella.ui.theme.ThemeColors;
import io.github.nyzeep.orchestella.ui.theme.ThemeStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Horizontal scrolling auxiliary key bar attached above the soft keyboard.
 * Provides high-frequency developer keys (ESC, TAB, CTRL, ALT, |, -, /, ~, $, \, arrows)
 * and coordinates with {@link StickyModifierStateMachine}.
 */
public class MobileAuxiliaryKeyBar extends LinearLayout {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TerminalInputSink inputSink;
    private StickyModifierStateMachine stateMachine;
    private InteractiveTerminalEngine terminalEngine;
    private ModernTerminalView terminalView;
    private Runnable commandEntryAction;
    private AppTheme theme;
    public void setCommandEntryAction(Runnable action) { commandEntryAction = action; }
    private final StickyModifierStateMachine.Listener modifierListener = (modifier, state) -> {
        if (Looper.myLooper() == Looper.getMainLooper()) updateModifierVisual(modifier, state);
        else mainHandler.post(() -> updateModifierVisual(modifier, state));
    };

    public void setTerminalView(ModernTerminalView view) { terminalView = view; }

    private Button btnCtrl;
    private Button btnAlt;

    /**
     * Dynamically calculates target bottom padding for the auxiliary key bar.
     * When the soft keyboard (IME) is visible (imeBottom > 0), the bar docks exactly
     * on top of the keyboard. When hidden, it docks cleanly above the system navigation bar.
     */
    public static int calculateTargetBottomPadding(int imeBottom, int navBottom) {
        if (imeBottom > 0) {
            return imeBottom;
        }
        return Math.max(0, navBottom);
    }

    /**
     * Binds WindowInsets listener to this view to dynamically update bottom padding with IME.
     */
    public void bindWindowInsets() {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this, (v, insets) -> {
            androidx.core.graphics.Insets imeInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime());
            androidx.core.graphics.Insets navInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars());
            int targetBottomPadding = calculateTargetBottomPadding(imeInsets.bottom, navInsets.bottom);
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), targetBottomPadding);
            return insets;
        });
    }

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
        if (this.stateMachine != null) this.stateMachine.removeListener(modifierListener);
        this.stateMachine = stateMachine;
        if (stateMachine != null) {
            stateMachine.addListener(modifierListener);
            updateModifierVisual(Modifier.CTRL, stateMachine.getState(Modifier.CTRL));
            updateModifierVisual(Modifier.ALT, stateMachine.getState(Modifier.ALT));
        }
    }

    @Deprecated
    public void setTargetInput(EditText targetInput) {
        // Deprecated: Ticket 06 eliminates isolated EditText; auxiliary bar sends directly to PTY sink
    }

    public StickyModifierStateMachine getStateMachine() {
        return stateMachine;
    }

    private void init(Context context) {
        theme = new ThemeStore(context).resolve();
        setOrientation(VERTICAL);
        setBackgroundColor(theme.getSurface());
        // Only the secondary tools scroll horizontally. An outer HorizontalScrollView
        // measures its child with unbounded width and defeats the primary-row weights.
        LinearLayout rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        LinearLayout primary = new LinearLayout(context);
        primary.setPadding(dpToPx(context, 4), dpToPx(context, 4), dpToPx(context, 4), 0);
        addPrimaryKey(primary, "ESC", v -> pressEsc());
        addPrimaryKey(primary, "TAB", v -> pressTab());
        btnCtrl = addPrimaryKey(primary, "CTRL", v -> pressCtrl());
        btnAlt = addPrimaryKey(primary, "ALT", v -> pressAlt());
        Button clear = addPrimaryKey(primary, "CLEAR", v -> pressClr());
        clear.setContentDescription("清屏，保留 Shell 历史并请求当前程序重绘");
        rows.addView(primary, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        LinearLayout lower = new LinearLayout(context);
        lower.setGravity(Gravity.CENTER_VERTICAL);
        addNavigationKey(lower, "←", v -> pressLeft());
        addNavigationKey(lower, "↓", v -> pressDown());
        addNavigationKey(lower, "↑", v -> pressUp());
        addNavigationKey(lower, "→", v -> pressRight());
        HorizontalScrollView secondary = new HorizontalScrollView(context);
        secondary.setHorizontalScrollBarEnabled(true);
        secondary.setHorizontalFadingEdgeEnabled(true);
        secondary.setFadingEdgeLength(dpToPx(context, 24));
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dpToPx(context, 4), dpToPx(context, 4), dpToPx(context, 4), dpToPx(context, 4));

        addKeyButton(row, "命令", v -> { if (commandEntryAction != null) commandEntryAction.run(); });
        // ^C (Interrupt/Break)
        addKeyButton(row, "^C", v -> pressCtrlC());

        // ^Z (Suspend/Background)
        addKeyButton(row, "^Z", v -> pressCtrlZ());

        // ^D (EOF/Logout)
        addKeyButton(row, "^D", v -> pressCtrlD());

        // ^A / ^E (tmux / Line start & end)
        addKeyButton(row, "^A", v -> pressCtrlA());
        addKeyButton(row, "^E", v -> pressCtrlE());

        // CLR (Clear screen and reset line)
        addKeyButton(row, "^L", v -> pressClr());
        addKeyButton(row, "COPY", v -> { if (terminalView != null) terminalView.copySelection(); });
        addKeyButton(row, "PASTE", v -> pressPaste());

        // HOME / END (Line boundary cursor jumps)
        addKeyButton(row, "HOME", v -> pressHome());
        addKeyButton(row, "END", v -> pressEnd());

        // Symbols: `, |, ~, $, /, \, -, &, _, =, :, ;
        addKeyButton(row, "`", v -> pressSymbol("`"));
        addKeyButton(row, "|", v -> pressSymbol("|"));
        addKeyButton(row, "~", v -> pressSymbol("~"));
        addKeyButton(row, "$", v -> pressSymbol("$"));
        addKeyButton(row, "/", v -> pressSymbol("/"));
        addKeyButton(row, "\\", v -> pressSymbol("\\"));
        addKeyButton(row, "-", v -> pressSymbol("-"));
        addKeyButton(row, "_", v -> pressSymbol("_"));
        addKeyButton(row, "&", v -> pressSymbol("&"));
        addKeyButton(row, "=", v -> pressSymbol("="));
        addKeyButton(row, ":", v -> pressSymbol(":"));
        addKeyButton(row, ";", v -> pressSymbol(";"));

        addKeyButton(row, "PGUP", v -> sendKey(android.view.KeyEvent.KEYCODE_PAGE_UP));
        addKeyButton(row, "PGDN", v -> sendKey(android.view.KeyEvent.KEYCODE_PAGE_DOWN));
        for (int index = 0; index < 12; index++) {
            final int key = android.view.KeyEvent.KEYCODE_F1 + index;
            addKeyButton(row, "F" + (index + 1), v -> sendKey(key));
        }
        secondary.addView(row, new HorizontalScrollView.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        lower.addView(secondary, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        rows.addView(lower, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(rows, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        applyTheme(theme);
    }

    /**
     * Repaints the bar and every key cap from the active theme tokens. Called at
     * build time and again whenever the app theme changes, so the bar no longer
     * stays on a hardcoded Dracula palette.
     */
    public void applyTheme(AppTheme theme) {
        if (theme == null) return;
        this.theme = theme;
        setBackgroundColor(theme.getSurface());
        for (Button key : collectKeys()) {
            styleKeyCap(key);
        }
        if (stateMachine != null) {
            updateModifierVisual(Modifier.CTRL, stateMachine.getState(Modifier.CTRL));
            updateModifierVisual(Modifier.ALT, stateMachine.getState(Modifier.ALT));
        }
    }

    private void styleKeyCap(Button key) {
        key.setTextColor(theme.getText());
        key.setBackground(createKeyBackground(theme.getSurface(), theme.getGlow()));
    }

    private List<Button> collectKeys() {
        List<Button> keys = new ArrayList<>();
        collectKeys(this, keys);
        return keys;
    }

    private static void collectKeys(ViewGroup group, List<Button> out) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof Button) out.add((Button) child);
            else if (child instanceof ViewGroup) collectKeys((ViewGroup) child, out);
        }
    }

    private boolean sendKey(int keyCode) {
        if (terminalView != null) return terminalView.sendTerminalKey(keyCode);
        return inputSink != null && new TerminalInputConnection(inputSink, stateMachine)
                .sendKey(android.view.KeyEvent.ACTION_DOWN, keyCode);
    }

    private void sendControl(char value) {
        if (terminalView != null) terminalView.sendTerminalText(String.valueOf(value));
        else if (inputSink != null) new TerminalInputConnection(inputSink, stateMachine).commitText(String.valueOf(value), 1);
    }

    public void pressEsc() { sendKey(android.view.KeyEvent.KEYCODE_ESCAPE); }

    public void pressTab() { sendKey(android.view.KeyEvent.KEYCODE_TAB); }

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

    public void pressCtrlC() { sendControl('\u0003'); }
    public void pressCtrlZ() { sendControl('\u001a'); }
    public void pressCtrlD() { sendControl('\u0004'); }
    public void pressCtrlA() { sendControl('\u0001'); }
    public void pressCtrlE() { sendControl('\u0005'); }
    public void pressClr() {
        if (terminalView != null) terminalView.clearVisibleScreen();
        else sendControl('\u000c');
    }

    public void pressPaste() {
        if (terminalView != null) { terminalView.pasteFromClipboard(); return; }
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            android.content.ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).coerceToText(getContext());
                if (text != null && text.length() > 0) {
                    String str = text.toString();
                    if (inputSink != null) {
                        new TerminalInputConnection(inputSink, stateMachine).pasteText(str);
                    }
                }
            }
        }
    }

    public void pressHome() { sendKey(android.view.KeyEvent.KEYCODE_MOVE_HOME); }
    public void pressEnd() { sendKey(android.view.KeyEvent.KEYCODE_MOVE_END); }
    public void pressUp() { sendKey(android.view.KeyEvent.KEYCODE_DPAD_UP); }
    public void pressDown() { sendKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN); }
    public void pressLeft() { sendKey(android.view.KeyEvent.KEYCODE_DPAD_LEFT); }
    public void pressRight() { sendKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT); }

    public void pressSymbol(String symbol) {
        if (terminalView != null) terminalView.sendTerminalText(symbol);
        else if (inputSink != null) new TerminalInputConnection(inputSink, stateMachine).commitText(symbol, 1);
    }

    private void addNavigationKey(LinearLayout row, String label, OnClickListener listener) {
        Button button = addKeyButton(row, label, listener);
        button.setPadding(0, 0, 0, 0);
        // Keep the same 3dp horizontal margin as addKeyButton so arrow keys
        // align with the rest of the row instead of sticking together.
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dpToPx(getContext(), 48), dpToPx(getContext(), 48));
        int margin = dpToPx(getContext(), 3);
        params.setMargins(margin, 0, margin, 0);
        button.setLayoutParams(params);
    }

    private Button addPrimaryKey(LinearLayout row, String label, OnClickListener listener) {
        Button button = addKeyButton(row, label, listener);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dpToPx(getContext(), 48), 1f);
        params.setMargins(dpToPx(getContext(), 1), 0, dpToPx(getContext(), 1), 0);
        button.setLayoutParams(params);
        return button;
    }

    private Button addKeyButton(LinearLayout container, String label, OnClickListener listener) {
        Context context = getContext();
        Button btn = new Button(context, null, android.R.attr.buttonStyleSmall);
        btn.setText(label);
        btn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        btn.setTextColor(theme.getText());
        btn.setBackground(createKeyBackground(theme.getSurface(), theme.getGlow()));
        btn.setAllCaps(false);
        btn.setFocusable(false);
        btn.setFocusableInTouchMode(false);
        btn.setContentDescription(label);

        int minW = dpToPx(context, 48);
        int h = dpToPx(context, 48);
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

    private void updateModifierVisual(Modifier modifier, State state) {
        Button targetBtn = (modifier == Modifier.CTRL) ? btnCtrl : btnAlt;
        if (targetBtn == null) {
            return;
        }

        targetBtn.setSelected(state != State.OFF);
        targetBtn.setContentDescription(modifier.name() + (state == State.LATCHED ? "，下一键生效"
                : state == State.LOCKED ? "，已锁定" : "，未启用"));
        switch (state) {
            case LATCHED:
                // Highlighting active latched modifier: primary background with contrast ink
                targetBtn.setBackground(createKeyBackground(theme.getPrimary(), theme.getPrimary()));
                targetBtn.setTextColor(ThemeColors.INSTANCE.onColor(theme.getPrimary()));
                break;
            case LOCKED:
                // Locked modifier: primary background with contrast ink
                targetBtn.setBackground(createKeyBackground(theme.getPrimary(), theme.getPrimary()));
                targetBtn.setTextColor(ThemeColors.INSTANCE.onColor(theme.getPrimary()));
                break;
            case OFF:
            default:
                // Normal released modifier
                targetBtn.setBackground(createKeyBackground(theme.getSurface(), theme.getGlow()));
                targetBtn.setTextColor(theme.getText());
                break;
        }
    }

    /**
     * Press-feedback key background: pressed state lightens the fill toward the
     * theme foreground while keeping the same stroke; default state keeps the
     * original colors. Pure code StateListDrawable — no theme attribute ripple.
     */
    private android.graphics.drawable.StateListDrawable createKeyBackground(int fillColor, int strokeColor) {
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        int pressedFill = theme != null
                ? ThemeColors.INSTANCE.blend(fillColor, theme.getText(), 0.18f)
                : fillColor;
        states.addState(new int[]{android.R.attr.state_pressed}, keyShape(pressedFill, strokeColor));
        states.addState(new int[]{}, keyShape(fillColor, strokeColor));
        return states;
    }

    private static GradientDrawable keyShape(int fillColor, int strokeColor) {
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
