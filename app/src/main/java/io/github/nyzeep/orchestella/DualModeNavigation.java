package io.github.nyzeep.orchestella;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import java.util.Objects;

/**
 * Manages seamless bidirectional mode switching between {@link TerminalActivity}
 * and {@link DesktopActivity} without interrupting the background guest VM or
 * {@link OrchestellaForegroundService}.
 *
 * <p>Conforms to ADR-0011, ADR-0017, and Ticket 43.
 */
public final class DualModeNavigation {

    public static final String EXTRA_TARGET_MODE = "targetMode";
    public static final String MODE_DESKTOP = "desktop";
    public static final String MODE_TERMINAL = "terminal";

    public enum TargetMode {
        DESKTOP,
        TERMINAL
    }

    private DualModeNavigation() {}

    /**
     * Immutable navigation request container decoupling mode transition decisions
     * and parameters from Android context.
     */
    public static final class NavigationRequest {
        private final TargetMode targetMode;
        private final String instanceId;
        private final String bridgeToken;

        private NavigationRequest(TargetMode targetMode, String instanceId, String bridgeToken) {
            this.targetMode = Objects.requireNonNull(targetMode, "targetMode");
            if (instanceId == null || instanceId.trim().isEmpty()) {
                throw new IllegalArgumentException("instanceId cannot be null or blank");
            }
            if (bridgeToken == null || bridgeToken.trim().isEmpty()) {
                throw new IllegalArgumentException("bridgeToken cannot be null or blank");
            }
            this.instanceId = instanceId.trim();
            this.bridgeToken = bridgeToken.trim();
        }

        public TargetMode targetMode() {
            return targetMode;
        }

        public String instanceId() {
            return instanceId;
        }

        public String bridgeToken() {
            return bridgeToken;
        }

        public Class<?> targetActivityClass() {
            return targetMode == TargetMode.DESKTOP ? DesktopActivity.class : TerminalActivity.class;
        }

        public boolean preserveForegroundService() {
            return true;
        }

        public boolean reorderToFront() {
            return true;
        }

        public void populate(DesktopLaunchIntent.IntentSink sink) {
            Objects.requireNonNull(sink, "sink");
            sink.putExtra(DesktopLaunchIntent.EXTRA_INSTANCE_ID, instanceId);
            sink.putExtra(DesktopLaunchIntent.EXTRA_BRIDGE_TOKEN, bridgeToken);
            sink.putExtra(DesktopLaunchIntent.EXTRA_FROM_TERMINAL, targetMode == TargetMode.DESKTOP);
            sink.putExtra(EXTRA_TARGET_MODE, targetMode == TargetMode.DESKTOP ? MODE_DESKTOP : MODE_TERMINAL);
        }

        public Intent toIntent(Context context) {
            Intent intent = new Intent(context, targetActivityClass());
            if (reorderToFront()) {
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            }
            populate(new DesktopLaunchIntent.IntentSink() {
                @Override
                public void putExtra(String key, String value) {
                    intent.putExtra(key, value);
                }

                @Override
                public void putExtra(String key, boolean value) {
                    intent.putExtra(key, value);
                }
            });
            return intent;
        }
    }

    /**
     * Builds a navigation request from Terminal to Desktop.
     */
    public static NavigationRequest buildDesktopNavigation(String instanceId, String bridgeToken) {
        return new NavigationRequest(TargetMode.DESKTOP, instanceId, bridgeToken);
    }

    /**
     * Builds a navigation request from Desktop to Terminal.
     */
    public static NavigationRequest buildTerminalNavigation(String instanceId, String bridgeToken) {
        return new NavigationRequest(TargetMode.TERMINAL, instanceId, bridgeToken);
    }

    /**
     * Builds an Android Intent targeting {@link DesktopActivity}.
     */
    public static Intent createDesktopIntent(Context context, String instanceId, String bridgeToken) {
        return buildDesktopNavigation(instanceId, bridgeToken).toIntent(context);
    }

    /**
     * Builds an Android Intent targeting {@link TerminalActivity}.
     */
    public static Intent createTerminalIntent(Context context, String instanceId, String bridgeToken) {
        return buildTerminalNavigation(instanceId, bridgeToken).toIntent(context);
    }

    /**
     * Performs direct navigation to {@link DesktopActivity}.
     */
    public static void navigateToDesktop(Activity activity, String instanceId, String bridgeToken) {
        Objects.requireNonNull(activity, "activity");
        activity.startActivity(createDesktopIntent(activity, instanceId, bridgeToken));
    }

    /**
     * Performs direct navigation to {@link TerminalActivity}.
     */
    public static void navigateToTerminal(Activity activity, String instanceId, String bridgeToken) {
        Objects.requireNonNull(activity, "activity");
        activity.startActivity(createTerminalIntent(activity, instanceId, bridgeToken));
    }
}
