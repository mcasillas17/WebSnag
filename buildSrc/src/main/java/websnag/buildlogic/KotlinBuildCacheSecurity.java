package websnag.buildlogic;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Version policy for GHSA-r937-wjx7-w2jp, fixed in Kotlin 2.4.20-Beta1. */
public final class KotlinBuildCacheSecurity {
    private static final Pattern RELEASE_VERSION = Pattern.compile(
            "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
                    + "(?:-(?:Beta[1-9][0-9]*|RC(?:[1-9][0-9]*)?))?");

    private KotlinBuildCacheSecurity() {}

    public static boolean isPatched(String version) {
        if (version == null) {
            return false;
        }
        Matcher release = RELEASE_VERSION.matcher(version);
        if (!release.matches()) {
            return false;
        }
        try {
            int major = Integer.parseInt(release.group(1));
            int minor = Integer.parseInt(release.group(2));
            int patch = Integer.parseInt(release.group(3));
            return major > 2 || (major == 2 && (minor > 4 || (minor == 4 && patch >= 20)));
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}
