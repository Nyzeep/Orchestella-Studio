package io.github.nyzeep.droidlayer;

import android.content.Context;
import android.content.Intent;
import java.util.Objects;

/**
 * Strategy-neutral constructor and parameter container for launching {@link DesktopActivity}.
 *
 * <p>Decouples intent extra extraction and credential security validation from Android system dependencies,
 * enabling pure JVM unit testability.
 */
public final class DesktopLaunchIntent {

    public static final String ACTION_LAUNCH_DESKTOP = "io.github.nyzeep.droidlayer.action.LAUNCH_DESKTOP";
    public static final String EXTRA_INSTANCE_ID = "instanceId";
    public static final String EXTRA_BRIDGE_TOKEN = "bridgeToken";
    public static final String EXTRA_FROM_TERMINAL = "fromTerminal";
    public static final String EXTRA_GRAPHICS_STRATEGY = "graphicsStrategy";

    /** Decoupled seam for populating intent extras. */
    public interface IntentSink {
        void putExtra(String key, String value);
        void putExtra(String key, boolean value);
    }

    /** Decoupled seam for reading intent extras. */
    public interface IntentSource {
        String getStringExtra(String key);
        boolean getBooleanExtra(String key, boolean defaultValue);
    }

    private final String instanceId;
    private final String bridgeToken;
    private final boolean fromTerminal;
    private final String graphicsStrategy;

    private DesktopLaunchIntent(Builder builder) {
        if (builder.instanceId == null || builder.instanceId.trim().isEmpty()) {
            throw new IllegalArgumentException("instanceId cannot be null or blank");
        }
        if (builder.bridgeToken == null || builder.bridgeToken.trim().isEmpty()) {
            throw new IllegalArgumentException("bridgeToken cannot be null or blank");
        }
        this.instanceId = builder.instanceId.trim();
        this.bridgeToken = builder.bridgeToken.trim();
        this.fromTerminal = builder.fromTerminal;
        this.graphicsStrategy = builder.graphicsStrategy;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String instanceId;
        private String bridgeToken;
        private boolean fromTerminal = false;
        private String graphicsStrategy;

        public Builder setInstanceId(String instanceId) {
            this.instanceId = instanceId;
            return this;
        }

        public Builder setBridgeToken(String bridgeToken) {
            this.bridgeToken = bridgeToken;
            return this;
        }

        public Builder setFromTerminal(boolean fromTerminal) {
            this.fromTerminal = fromTerminal;
            return this;
        }

        public Builder setGraphicsStrategy(String graphicsStrategy) {
            this.graphicsStrategy = graphicsStrategy;
            return this;
        }

        public DesktopLaunchIntent build() {
            return new DesktopLaunchIntent(this);
        }
    }

    public String instanceId() {
        return instanceId;
    }

    public String bridgeToken() {
        return bridgeToken;
    }

    public boolean fromTerminal() {
        return fromTerminal;
    }

    public String graphicsStrategy() {
        return graphicsStrategy;
    }

    public static Class<?> targetActivityClass() {
        return DesktopActivity.class;
    }

    /**
     * Populates intent key-value pairs into a decoupled sink.
     */
    public void populate(IntentSink sink) {
        Objects.requireNonNull(sink, "sink");
        sink.putExtra(EXTRA_INSTANCE_ID, instanceId);
        sink.putExtra(EXTRA_BRIDGE_TOKEN, bridgeToken);
        sink.putExtra(EXTRA_FROM_TERMINAL, fromTerminal);
        if (graphicsStrategy != null) {
            sink.putExtra(EXTRA_GRAPHICS_STRATEGY, graphicsStrategy);
        }
    }

    /**
     * Parses a launch parameter descriptor from a decoupled source.
     */
    public static DesktopLaunchIntent fromSource(IntentSource source) {
        Objects.requireNonNull(source, "source");
        String instanceId = source.getStringExtra(EXTRA_INSTANCE_ID);
        String bridgeToken = source.getStringExtra(EXTRA_BRIDGE_TOKEN);
        boolean fromTerminal = source.getBooleanExtra(EXTRA_FROM_TERMINAL, false);
        String graphicsStrategy = source.getStringExtra(EXTRA_GRAPHICS_STRATEGY);

        return builder()
                .setInstanceId(instanceId)
                .setBridgeToken(bridgeToken)
                .setFromTerminal(fromTerminal)
                .setGraphicsStrategy(graphicsStrategy)
                .build();
    }

    /**
     * Creates a standard Android Intent targeting {@link DesktopActivity}.
     */
    public static Intent create(Context context, String instanceId, String bridgeToken) {
        return create(context, instanceId, bridgeToken, false);
    }

    /**
     * Creates a standard Android Intent targeting {@link DesktopActivity} with mode transition marker.
     */
    public static Intent create(Context context, String instanceId, String bridgeToken, boolean fromTerminal) {
        DesktopLaunchIntent launch = builder()
                .setInstanceId(instanceId)
                .setBridgeToken(bridgeToken)
                .setFromTerminal(fromTerminal)
                .build();
        return launch.toIntent(context);
    }

    /**
     * Converts this launch parameter object to an Android {@link Intent}.
     */
    public Intent toIntent(Context context) {
        Intent intent = new Intent(context, DesktopActivity.class);
        intent.setAction(ACTION_LAUNCH_DESKTOP);
        populate(new IntentSink() {
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

    /**
     * Extracts {@link DesktopLaunchIntent} from an Android {@link Intent}.
     */
    public static DesktopLaunchIntent fromIntent(Intent intent) {
        if (intent == null) {
            throw new IllegalArgumentException("intent cannot be null");
        }
        return fromSource(new IntentSource() {
            @Override
            public String getStringExtra(String key) {
                return intent.getStringExtra(key);
            }

            @Override
            public boolean getBooleanExtra(String key, boolean defaultValue) {
                return intent.getBooleanExtra(key, defaultValue);
            }
        });
    }
}
