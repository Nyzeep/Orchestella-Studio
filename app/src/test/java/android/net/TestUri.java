package android.net;

import android.os.Parcel;
import java.util.Collections;
import java.util.List;

/**
 * Concrete Uri stub for JVM unit testing with mockable android.jar.
 */
public final class TestUri extends Uri {
    public static Uri create(String uriString) {
        return new TestUri(uriString);
    }

    private final String uriString;

    public TestUri(String uriString) {
        super();
        this.uriString = uriString != null ? uriString : "content://dummy";
    }

    @Override
    public boolean isHierarchical() {
        return true;
    }

    @Override
    public boolean isRelative() {
        return false;
    }

    @Override
    public String getScheme() {
        return "content";
    }

    @Override
    public String getSchemeSpecificPart() {
        return "//dummy";
    }

    @Override
    public String getEncodedSchemeSpecificPart() {
        return "//dummy";
    }

    @Override
    public String getAuthority() {
        return "dummy";
    }

    @Override
    public String getEncodedAuthority() {
        return "dummy";
    }

    @Override
    public String getUserInfo() {
        return null;
    }

    @Override
    public String getEncodedUserInfo() {
        return null;
    }

    @Override
    public String getHost() {
        return "dummy";
    }

    @Override
    public int getPort() {
        return -1;
    }

    @Override
    public String getPath() {
        return "/dummy";
    }

    @Override
    public String getEncodedPath() {
        return "/dummy";
    }

    @Override
    public String getQuery() {
        return null;
    }

    @Override
    public String getEncodedQuery() {
        return null;
    }

    @Override
    public String getFragment() {
        return null;
    }

    @Override
    public String getEncodedFragment() {
        return null;
    }

    @Override
    public List<String> getPathSegments() {
        return Collections.emptyList();
    }

    @Override
    public String getLastPathSegment() {
        return "dummy";
    }

    @Override
    public Builder buildUpon() {
        return new Builder();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
    }

    @Override
    public int compareTo(Uri other) {
        return 0;
    }

    @Override
    public String toString() {
        return uriString;
    }
}
