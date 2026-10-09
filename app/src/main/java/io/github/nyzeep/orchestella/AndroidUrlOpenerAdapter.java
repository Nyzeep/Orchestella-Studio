package io.github.nyzeep.orchestella;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import io.github.nyzeep.orchestella.runtime.contract.BridgeControl;
import java.net.URI;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Android adapter for guest-initiated Bridge URL open commands.
 * Strictly verifies HTTP/HTTPS schemes against security allowlists (ADR-0010)
 * before dispatching Intent.ACTION_VIEW with NEW_TASK flag to default browser.
 */
public final class AndroidUrlOpenerAdapter implements BridgeControl.UrlOpener {
    private static final int MAX_URL_LENGTH = 2048;
    private static final Pattern SAFE_URL_PATTERN = Pattern.compile("^[hH][tT][tT][pP][sS]?://.+");

    /** Seam for actual activity launch. */
    public interface ActivityStarter {
        void startActivity(String url) throws ActivityNotFoundException, SecurityException;
    }

    private final ActivityStarter starter;

    public AndroidUrlOpenerAdapter(Context context) {
        Objects.requireNonNull(context, "context");
        this.starter = url -> {
            Intent viewIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(viewIntent);
        };
    }

    public AndroidUrlOpenerAdapter(ActivityStarter starter) {
        this.starter = Objects.requireNonNull(starter, "starter");
    }

    @Override
    public boolean openUrl(String url) {
        if (!isSafeUrl(url)) {
            return false;
        }
        try {
            URI parsedUri = URI.create(url);
            String scheme = parsedUri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                return false;
            }
            if (parsedUri.getHost() == null || parsedUri.getHost().isEmpty()) {
                return false;
            }
            starter.startActivity(url);
            return true;
        } catch (ActivityNotFoundException | SecurityException exception) {
            return false;
        } catch (Exception unexpected) {
            return false;
        }
    }

    public static boolean isSafeUrl(String url) {
        if (url == null || url.length() > MAX_URL_LENGTH) {
            return false;
        }
        return SAFE_URL_PATTERN.matcher(url).matches();
    }
}
