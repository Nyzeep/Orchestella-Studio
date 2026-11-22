package io.github.nyzeep.orchestella.terminal;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import io.github.nyzeep.orchestella.ui.design.components.OeDialogs;
import io.github.nyzeep.orchestella.ui.design.components.OeLegacyViewStyle;
import io.github.nyzeep.orchestella.ui.theme.AppTheme;
import io.github.nyzeep.orchestella.ui.theme.SurfaceOpacity;
import io.github.nyzeep.orchestella.ui.theme.ThemeColors;
import io.github.nyzeep.orchestella.ui.theme.ThemeStore;
import java.util.ArrayList;
import java.util.List;

/**
 * Slide-out right drawer panel for managing multi-terminal sessions.
 *
 * <p>Includes:
 * <ul>
 *   <li>Top: "+ 新建终端" prominent action button, then the G-04 "桌面模式"
 *       entry (Terminal→Desktop; visibility/enabled/disabled-reason all decided
 *       by {@link io.github.nyzeep.orchestella.DesktopEntryPolicy});</li>
 *   <li>Center: RecyclerView of terminal sessions with live status lights,
 *       current active indicator, long-press rename dialog, and close "✕" button;</li>
 *   <li>Bottom: Total session count and quick collapse action button.</li>
 * </ul>
 */
public final class TerminalDrawerPanel extends LinearLayout {

    public interface DrawerCallback {
        void onSessionSelected(TerminalSessionInstance session);
        void onNewSessionRequested();
        void onSessionRenamed(TerminalSessionInstance session, String newTitle);
        void onSessionClosed(TerminalSessionInstance session);
        void onCloseDrawerRequested();
        /** G-04：桌面模式入口（Terminal→Desktop 方向），enabled 由 DesktopEntryPolicy 单源判定。 */
        void onDesktopModeRequested();
    }

    private String instanceId;

    public void setInstanceId(String id) { instanceId = id; refresh(); }
    private final DrawerCallback callback;
    private final SessionListAdapter adapter;
    private final TextView tvSessionCount;
    private final TextView tvHeader;
    private final TextView tvHeaderHint;
    private final Button btnNewTerminal;
    private final Button btnDesktopMode;
    private final TextView tvDesktopReason;
    private final Button btnCollapse;
    private AppTheme theme;
    /** G-04：实例运行判定由 TerminalActivity 注入（ExecutionSessionRegistry，与实例详情页入口同源）。 */
    private java.util.function.BooleanSupplier instanceRunningProvider;

    /** G-04：注入实例运行判定；不注入时入口按未运行处理（诚实禁用态）。 */
    public void setInstanceRunningProvider(java.util.function.BooleanSupplier provider) {
        this.instanceRunningProvider = provider;
        refreshDesktopEntry();
    }

    /** Semantic danger red shared with the audit exemption list (#EF4444). */
    private static final int DANGER_RED = 0xFFEF4444;

    public TerminalDrawerPanel(Context context, String instanceId, DrawerCallback callback) {
        super(context);
        this.instanceId = instanceId;
        this.callback = callback;
        theme = new ThemeStore(context).resolve();
        setOrientation(VERTICAL);
        setBackgroundColor(effectiveSurface());

        int pad = dpToPx(16);
        setPadding(pad, pad, pad, pad);

        // Header Title
        tvHeader = new TextView(context);
        tvHeader.setText(context.getString(io.github.nyzeep.orchestella.R.string.terminal_drawer_title));
        tvHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        tvHeader.setTypeface(OeLegacyViewStyle.monoTypeface(context, Typeface.BOLD));
        tvHeader.setTextColor(theme.getText());
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        headerLp.setMargins(0, dpToPx(8), 0, dpToPx(2));
        addView(tvHeader, headerLp);

        // Honest labeling: each entry is an independent output view over the one guest shell.
        tvHeaderHint = new TextView(context);
        tvHeaderHint.setText(context.getString(io.github.nyzeep.orchestella.R.string.terminal_drawer_hint));
        tvHeaderHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvHeaderHint.setTextColor(theme.getTextDim());
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        hintLp.setMargins(0, 0, 0, dpToPx(10));
        addView(tvHeaderHint, hintLp);

        // Top: "+ 新建终端" prominent action button
        btnNewTerminal = new Button(context, null, android.R.attr.buttonStyleSmall);
        btnNewTerminal.setText(context.getString(io.github.nyzeep.orchestella.R.string.terminal_drawer_new));
        btnNewTerminal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        btnNewTerminal.setTypeface(OeLegacyViewStyle.monoTypeface(context, Typeface.BOLD));
        btnNewTerminal.setTextColor(ThemeColors.INSTANCE.onColor(theme.getPrimary()));
        btnNewTerminal.setBackground(createPillBackground(theme.getPrimary(), theme.getPrimary(), 10f));
        btnNewTerminal.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        btnNewTerminal.setOnClickListener(v -> {
            if (this.callback != null) {
                this.callback.onNewSessionRequested();
            }
        });
        LinearLayout.LayoutParams newBtnLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        newBtnLp.setMargins(0, 0, 0, dpToPx(4));
        addView(btnNewTerminal, newBtnLp);

        // G-04：桌面模式入口（Terminal→Desktop）。可见性/enabled/禁用原因全部走
        // DesktopEntryPolicy 单一来源（与 instances sheet、GuestFeatures 同源）；
        // 通道未就绪或实例未运行时呈现诚实禁用态 + 统一原因文案。
        btnDesktopMode = new Button(context, null, android.R.attr.buttonStyleSmall);
        btnDesktopMode.setText(context.getString(io.github.nyzeep.orchestella.R.string.terminal_drawer_desktop));
        btnDesktopMode.setContentDescription(context.getString(
                io.github.nyzeep.orchestella.R.string.terminal_drawer_desktop_content_desc));
        btnDesktopMode.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        btnDesktopMode.setTypeface(OeLegacyViewStyle.monoTypeface(context, Typeface.BOLD));
        btnDesktopMode.setTextColor(ThemeColors.INSTANCE.onColor(theme.getPrimary()));
        btnDesktopMode.setBackground(createPillBackground(
                alphaDerive(theme.getPrimary(), 0x22000000), theme.getPrimary(), 10f));
        btnDesktopMode.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        btnDesktopMode.setOnClickListener(v -> {
            if (this.callback != null && btnDesktopMode.isEnabled()) {
                this.callback.onDesktopModeRequested();
            }
        });
        addView(btnDesktopMode, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        tvDesktopReason = new TextView(context);
        tvDesktopReason.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvDesktopReason.setTextColor(theme.getTextDim());
        LinearLayout.LayoutParams reasonLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        reasonLp.setMargins(0, dpToPx(4), 0, dpToPx(12));
        addView(tvDesktopReason, reasonLp);

        // Center: Sessions RecyclerView
        RecyclerView recyclerView = new RecyclerView(context);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        adapter = new SessionListAdapter();
        recyclerView.setAdapter(adapter);
        LinearLayout.LayoutParams recyclerLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1.0f);
        addView(recyclerView, recyclerLp);

        // Bottom section: session count and collapse button
        LinearLayout bottomBar = new LinearLayout(context);
        bottomBar.setOrientation(HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(0, dpToPx(12), 0, dpToPx(8));

        tvSessionCount = new TextView(context);
        tvSessionCount.setText("共 0 个视图");
        tvSessionCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tvSessionCount.setTextColor(theme.getTextDim());
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f);
        bottomBar.addView(tvSessionCount, countLp);

        btnCollapse = new Button(context, null, android.R.attr.buttonStyleSmall);
        btnCollapse.setText("收起 ❯");
        btnCollapse.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        btnCollapse.setTypeface(OeLegacyViewStyle.monoTypeface(context));
        btnCollapse.setTextColor(theme.getAccent());
        btnCollapse.setBackground(createPillBackground(
                alphaDerive(theme.getAccent(), 0x22000000), alphaDerive(theme.getAccent(), 0x44000000), 8f));
        btnCollapse.setPadding(dpToPx(10), dpToPx(4), dpToPx(10), dpToPx(4));
        btnCollapse.setOnClickListener(v -> {
            if (this.callback != null) {
                this.callback.onCloseDrawerRequested();
            }
        });
        bottomBar.addView(btnCollapse, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        addView(bottomBar, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        refresh();
    }

    public void refresh() {
        refreshDesktopEntry();
        if (instanceId == null) {
            return;
        }
        List<TerminalSessionInstance> sessions =
                MultiTerminalSessionManager.getInstance().listSessions(instanceId);
        adapter.setSessions(sessions);
        tvSessionCount.setText(String.format(java.util.Locale.getDefault(), "共 %d 个视图", sessions.size()));
    }

    /**
     * G-04：桌面模式入口条件渲染。判定本体在 {@link io.github.nyzeep.orchestella.DesktopEntryPolicy}
     * （纯 JVM，可测）；这里只做视图映射——未绑定实例整块隐藏，禁用时展示统一原因文案，
     * 置灰口径对齐 instances sheet（alpha 0.45 ≈ sheet 的 onSurfaceVariant 0.4）。
     */
    private void refreshDesktopEntry() {
        boolean hasBoundInstance = instanceId != null;
        boolean running = instanceRunningProvider != null && instanceRunningProvider.getAsBoolean();
        io.github.nyzeep.orchestella.DesktopEntryPolicy.EntryState state =
                io.github.nyzeep.orchestella.DesktopEntryPolicy.drawerEntry(hasBoundInstance, running);
        int visibility = state.visible ? View.VISIBLE : View.GONE;
        btnDesktopMode.setVisibility(visibility);
        btnDesktopMode.setEnabled(state.enabled);
        btnDesktopMode.setAlpha(state.enabled ? 1f : 0.45f);
        if (state.reasonRes != null) {
            tvDesktopReason.setText(getContext().getString(state.reasonRes));
            tvDesktopReason.setVisibility(visibility);
        } else {
            tvDesktopReason.setVisibility(View.GONE);
        }
    }

    /**
     * Repaints the whole panel (chrome plus every bound session row) from the
     * active theme tokens. Called at build time and again on theme change.
     */
    public void applyTheme(AppTheme theme) {
        if (theme == null) return;
        this.theme = theme;
        setBackgroundColor(effectiveSurface());
        tvHeader.setTextColor(theme.getText());
        tvHeaderHint.setTextColor(theme.getTextDim());
        btnNewTerminal.setTextColor(ThemeColors.INSTANCE.onColor(theme.getPrimary()));
        btnNewTerminal.setBackground(createPillBackground(theme.getPrimary(), theme.getPrimary(), 10f));
        btnDesktopMode.setTextColor(ThemeColors.INSTANCE.onColor(theme.getPrimary()));
        btnDesktopMode.setBackground(createPillBackground(
                alphaDerive(theme.getPrimary(), 0x22000000), theme.getPrimary(), 10f));
        tvDesktopReason.setTextColor(theme.getTextDim());
        tvSessionCount.setTextColor(theme.getTextDim());
        btnCollapse.setTextColor(theme.getAccent());
        btnCollapse.setBackground(createPillBackground(
                alphaDerive(theme.getAccent(), 0x22000000), alphaDerive(theme.getAccent(), 0x44000000), 8f));
        adapter.notifyDataSetChanged();
    }

    /** Keeps the audit's alpha-capsule pattern: token RGB under a fixed alpha. */
    private static int alphaDerive(int token, int alphaBits) {
        return (token & 0x00FFFFFF) | alphaBits;
    }

    /** Surface token composited to the user's chosen UI surface opacity (W-C.2). */
    private int effectiveSurface() {
        return SurfaceOpacity.INSTANCE.applyOpacity(
                theme.getSurface(), new ThemeStore(getContext()).surfaceOpacityPercent());
    }

    private final class SessionListAdapter extends RecyclerView.Adapter<SessionViewHolder> {
        private final List<TerminalSessionInstance> items = new ArrayList<>();

        public void setSessions(List<TerminalSessionInstance> sessions) {
            items.clear();
            if (sessions != null) {
                items.addAll(sessions);
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public SessionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new SessionViewHolder(parent.getContext());
        }

        @Override
        public void onBindViewHolder(@NonNull SessionViewHolder holder, int position) {
            holder.bind(items.get(position));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private final class SessionViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvStatusDot;
        private final TextView tvTitle;
        private final TextView tvActiveBadge;
        private final Button btnClose;

        public SessionViewHolder(Context context) {
            super(new LinearLayout(context));
            LinearLayout row = (LinearLayout) itemView;
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8));
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            rowLp.setMargins(0, dpToPx(4), 0, dpToPx(4));
            row.setLayoutParams(rowLp);

            // Status indicator dot
            tvStatusDot = new TextView(context);
            tvStatusDot.setText("● ");
            tvStatusDot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            row.addView(tvStatusDot);

            // Title
            tvTitle = new TextView(context);
            tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            tvTitle.setTypeface(OeLegacyViewStyle.monoTypeface(context, Typeface.BOLD));
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f);
            row.addView(tvTitle, titleLp);

            // Active badge
            tvActiveBadge = new TextView(context);
            tvActiveBadge.setText("当前");
            tvActiveBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            tvActiveBadge.setTypeface(OeLegacyViewStyle.monoTypeface(context, Typeface.BOLD));
            tvActiveBadge.setTextColor(theme.getPrimary());
            tvActiveBadge.setBackground(createPillBackground(
                    alphaDerive(theme.getPrimary(), 0x33000000), theme.getPrimary(), 4f));
            tvActiveBadge.setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2));
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            badgeLp.setMargins(dpToPx(6), 0, dpToPx(8), 0);
            row.addView(tvActiveBadge, badgeLp);

            // Close button
            btnClose = new Button(context, null, android.R.attr.buttonStyleSmall);
            btnClose.setText("✕");
            btnClose.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            btnClose.setTypeface(OeLegacyViewStyle.monoTypeface(context, Typeface.BOLD));
            btnClose.setTextColor(DANGER_RED);
            btnClose.setBackground(createPillBackground(
                    alphaDerive(DANGER_RED, 0x22000000), alphaDerive(DANGER_RED, 0x44000000), 6f));
            btnClose.setPadding(dpToPx(8), dpToPx(2), dpToPx(8), dpToPx(2));
            LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            row.addView(btnClose, closeLp);
        }

        public void bind(TerminalSessionInstance session) {
            tvTitle.setText(session.getTitle());
            TerminalSessionInstance selected = MultiTerminalSessionManager.getInstance().getActiveSession(instanceId);
            boolean isActive = selected != null && selected.getSessionId().equals(session.getSessionId());
            boolean connected = io.github.nyzeep.orchestella.PersistentTerminalSessionPool.getInstance().hasActiveSession(instanceId);
            tvStatusDot.setTextColor(connected ? theme.getAccent() : theme.getTextDim());
            tvStatusDot.setContentDescription(connected ? "Shell 已连接" : "Shell 已离线");
            itemView.setContentDescription("切换到会话 " + session.getTitle());

            if (isActive) {
                tvActiveBadge.setVisibility(View.VISIBLE);
                // Session 19 (T-B.2): drawer rows are surfaces too — the row fill
                // goes through the same opacity token as the panel behind it.
                itemView.setBackground(createPillBackground(
                        SurfaceOpacity.INSTANCE.applyOpacity(
                                ThemeColors.INSTANCE.blend(theme.getSurface(), theme.getPrimary(), 0.20f),
                                new ThemeStore(getContext()).surfaceOpacityPercent()),
                        theme.getPrimary(), 10f));
                tvTitle.setTextColor(theme.getPrimary());
            } else {
                tvActiveBadge.setVisibility(View.GONE);
                itemView.setBackground(createPillBackground(
                        effectiveSurface(), alphaDerive(theme.getGlow(), 0x33000000), 10f));
                tvTitle.setTextColor(theme.getText());
            }

            itemView.setOnClickListener(v -> {
                if (callback != null) {
                    callback.onSessionSelected(session);
                }
            });

            itemView.setOnLongClickListener(v -> {
                showRenameDialog(session);
                return true;
            });

            btnClose.setOnClickListener(v -> {
                showCloseConfirmDialog(session);
            });
        }

        private void showRenameDialog(TerminalSessionInstance session) {
            Context ctx = itemView.getContext();
            OeDialogs.input(
                    ctx,
                    ctx.getString(io.github.nyzeep.orchestella.R.string.terminal_drawer_rename_title),
                    null,
                    session.getTitle(),
                    "确定",
                    "取消",
                    false,
                    false,
                    raw -> {
                        String newTitle = raw.trim();
                        if (!newTitle.isEmpty() && callback != null) {
                            callback.onSessionRenamed(session, newTitle);
                        }
                    });
        }

        private void showCloseConfirmDialog(TerminalSessionInstance session) {
            Context ctx = itemView.getContext();
            OeDialogs.confirm(
                    ctx,
                    ctx.getString(io.github.nyzeep.orchestella.R.string.terminal_drawer_close_title),
                    "关闭 \"" + session.getTitle() + "\"？Shell 会话继续运行。",
                    "关闭",
                    "取消",
                    true,
                    () -> {
                        if (callback != null) {
                            callback.onSessionClosed(session);
                        }
                    });
        }
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    private GradientDrawable createPillBackground(
            int fillColor, int strokeColor, float cornerRadiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(fillColor);
        // The cornerRadiusDp parameter is legacy: the global token now decides the rounding.
        float radiusDp = new ThemeStore(getContext()).cornerRadiusDp();
        gd.setCornerRadius(radiusDp * getResources().getDisplayMetrics().density);
        gd.setStroke(2, strokeColor);
        return gd;
    }
}