package io.github.nyzeep.orchestella.terminal;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import io.github.nyzeep.orchestella.DraculaPalette;
import java.util.ArrayList;
import java.util.List;

/**
 * Slide-out right drawer panel for managing multi-terminal sessions.
 *
 * <p>Includes:
 * <ul>
 *   <li>Top: "+ 新建终端" prominent action button;</li>
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
    }

    private String instanceId;

    public void setInstanceId(String id) { instanceId = id; refresh(); }
    private final DrawerCallback callback;
    private final SessionListAdapter adapter;
    private final TextView tvSessionCount;

    public TerminalDrawerPanel(Context context, String instanceId, DrawerCallback callback) {
        super(context);
        this.instanceId = instanceId;
        this.callback = callback;
        setOrientation(VERTICAL);
        setBackgroundColor(0xFF21222C);

        int pad = dpToPx(16);
        setPadding(pad, pad, pad, pad);

        // Header Title
        TextView tvHeader = new TextView(context);
        tvHeader.setText("终端输出视图");
        tvHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        tvHeader.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvHeader.setTextColor(DraculaPalette.FOREGROUND);
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        headerLp.setMargins(0, dpToPx(8), 0, dpToPx(2));
        addView(tvHeader, headerLp);

        // Honest labeling: each entry is an independent output view over the one guest shell.
        TextView tvHeaderHint = new TextView(context);
        tvHeaderHint.setText("输出视图共享同一 Shell 会话");
        tvHeaderHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvHeaderHint.setTextColor(DraculaPalette.COMMENT);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        hintLp.setMargins(0, 0, 0, dpToPx(10));
        addView(tvHeaderHint, hintLp);

        // Top: "+ 新建输出视图" prominent action button
        Button btnNewTerminal = new Button(context, null, android.R.attr.buttonStyleSmall);
        btnNewTerminal.setText("+ 新建输出视图");
        btnNewTerminal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        btnNewTerminal.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        btnNewTerminal.setTextColor(0xFF282A36);
        btnNewTerminal.setBackground(createPillBackground(DraculaPalette.COLOR_GREEN, 0xFF50FA7B, 10f));
        btnNewTerminal.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        btnNewTerminal.setOnClickListener(v -> {
            if (this.callback != null) {
                this.callback.onNewSessionRequested();
            }
        });
        LinearLayout.LayoutParams newBtnLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        newBtnLp.setMargins(0, 0, 0, dpToPx(12));
        addView(btnNewTerminal, newBtnLp);

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
        tvSessionCount.setTextColor(DraculaPalette.COMMENT);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f);
        bottomBar.addView(tvSessionCount, countLp);

        Button btnCollapse = new Button(context, null, android.R.attr.buttonStyleSmall);
        btnCollapse.setText("收起 ❯");
        btnCollapse.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        btnCollapse.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        btnCollapse.setTextColor(DraculaPalette.COLOR_CYAN);
        btnCollapse.setBackground(createPillBackground(0x228BE9FD, 0x448BE9FD, 8f));
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
        if (instanceId == null) {
            return;
        }
        List<TerminalSessionInstance> sessions =
                MultiTerminalSessionManager.getInstance().listSessions(instanceId);
        adapter.setSessions(sessions);
        tvSessionCount.setText(String.format(java.util.Locale.getDefault(), "共 %d 个视图", sessions.size()));
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
            tvTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f);
            row.addView(tvTitle, titleLp);

            // Active badge
            tvActiveBadge = new TextView(context);
            tvActiveBadge.setText("当前");
            tvActiveBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            tvActiveBadge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            tvActiveBadge.setTextColor(DraculaPalette.COLOR_BLUE);
            tvActiveBadge.setBackground(createPillBackground(0x33BD93F9, 0xFFBD93F9, 4f));
            tvActiveBadge.setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2));
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            badgeLp.setMargins(dpToPx(6), 0, dpToPx(8), 0);
            row.addView(tvActiveBadge, badgeLp);

            // Close button
            btnClose = new Button(context, null, android.R.attr.buttonStyleSmall);
            btnClose.setText("✕");
            btnClose.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            btnClose.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            btnClose.setTextColor(DraculaPalette.COLOR_RED);
            btnClose.setBackground(createPillBackground(0x22FF5555, 0x44FF5555, 6f));
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
            tvStatusDot.setTextColor(connected ? DraculaPalette.COLOR_GREEN : DraculaPalette.COMMENT);
            tvStatusDot.setContentDescription(connected ? "Shell 已连接" : "Shell 已离线");

            if (isActive) {
                tvActiveBadge.setVisibility(View.VISIBLE);
                itemView.setBackground(createPillBackground(0xFF383A59, DraculaPalette.COLOR_BLUE, 10f));
                tvTitle.setTextColor(DraculaPalette.COLOR_MAGENTA);
            } else {
                tvActiveBadge.setVisibility(View.GONE);
                itemView.setBackground(createPillBackground(0xFF282A36, 0x336272A4, 10f));
                tvTitle.setTextColor(DraculaPalette.FOREGROUND);
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
            EditText input = new EditText(ctx);
            input.setText(session.getTitle());
            input.setSelection(session.getTitle().length());
            input.setTextColor(DraculaPalette.FOREGROUND);

            new AlertDialog.Builder(ctx)
                    .setTitle("重命名输出视图")
                    .setView(input)
                    .setPositiveButton("确定", (dialog, which) -> {
                        String newTitle = input.getText().toString().trim();
                        if (!newTitle.isEmpty() && callback != null) {
                            callback.onSessionRenamed(session, newTitle);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }

        private void showCloseConfirmDialog(TerminalSessionInstance session) {
            Context ctx = itemView.getContext();
            new AlertDialog.Builder(ctx)
                    .setTitle("关闭输出视图")
                    .setMessage("关闭 \"" + session.getTitle() + "\"？Shell 会话继续运行。")
                    .setPositiveButton("关闭", (dialog, which) -> {
                        if (callback != null) {
                            callback.onSessionClosed(session);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    private static GradientDrawable createPillBackground(
            int fillColor, int strokeColor, float cornerRadiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(fillColor);
        gd.setCornerRadius(cornerRadiusDp * 2.5f);
        gd.setStroke(2, strokeColor);
        return gd;
    }
}