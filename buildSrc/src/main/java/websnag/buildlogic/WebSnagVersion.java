package websnag.buildlogic;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WebSnagVersion {
    private static final int MAX_MAJOR_VERSION = 20;
    private static final int MAX_MINOR_OR_PATCH_VERSION = 99;
    private static final int MAX_PRERELEASE_SEQUENCE = 999;
    private static final int MAX_ANDROID_VERSION_CODE = 2_100_000_000;
    private static final Pattern RELEASE_TAG_PATTERN = Pattern.compile(
            "^v(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:-(alpha|beta|rc)\\.(0|[1-9]\\d*))?$");
    private static final WebSnagVersion DEVELOPMENT = new WebSnagVersion("0.0.0-dev", 1);

    private final String versionName;
    private final int versionCode;

    public WebSnagVersion(String versionName, int versionCode) {
        this.versionName = versionName;
        this.versionCode = versionCode;
    }

    public String getVersionName() {
        return versionName;
    }

    public int getVersionCode() {
        return versionCode;
    }

    public static WebSnagVersion development() {
        return DEVELOPMENT;
    }

    public static WebSnagVersion parse(String releaseTag) {
        if (releaseTag == null) {
            throw invalidReleaseTag();
        }
        Matcher match = RELEASE_TAG_PATTERN.matcher(releaseTag);
        if (!match.matches()) {
            throw invalidReleaseTag();
        }

        int major = parseBounded(match.group(1), "major", MAX_MAJOR_VERSION);
        int minor = parseBounded(match.group(2), "minor", MAX_MINOR_OR_PATCH_VERSION);
        int patch = parseBounded(match.group(3), "patch", MAX_MINOR_OR_PATCH_VERSION);
        String channel = match.group(4);
        String sequenceValue = match.group(5);
        int sequence = sequenceValue == null
                ? 0
                : parseBounded(
                        sequenceValue, "prerelease sequence", MAX_PRERELEASE_SEQUENCE);
        if (sequenceValue != null && sequence == 0) {
            throw invalidReleaseTag();
        }

        int channelCode;
        if (channel == null) {
            channelCode = 9;
        } else {
            channelCode = switch (channel) {
                case "alpha" -> 1;
                case "beta" -> 2;
                case "rc" -> 3;
                default -> throw invalidReleaseTag();
            };
        }

        long calculatedCode = major * 100_000_000L
                + minor * 1_000_000L
                + patch * 10_000L
                + channelCode * 1_000L
                + sequence;
        if (calculatedCode < 1 || calculatedCode > MAX_ANDROID_VERSION_CODE) {
            throw invalidReleaseTag();
        }

        return new WebSnagVersion(releaseTag.substring(1), (int) calculatedCode);
    }

    public static WebSnagVersion resolve(String releaseTag, boolean releaseBuildRequested) {
        if (releaseTag != null) {
            return parse(releaseTag);
        }
        if (releaseBuildRequested) {
            throw new IllegalStateException(
                    "Release builds require "
                            + "-PwebsnagReleaseTag=vMAJOR.MINOR.PATCH[-CHANNEL.N].");
        }
        return development();
    }

    private static int parseBounded(String rawValue, String componentName, int maximum) {
        final int value;
        try {
            value = Integer.parseInt(rawValue);
        } catch (NumberFormatException error) {
            throw invalidReleaseTag();
        }
        if (value > maximum) {
            throw new IllegalArgumentException(
                    "Invalid WebSnag release tag: "
                            + componentName
                            + " must be at most "
                            + maximum
                            + ".");
        }
        return value;
    }

    private static IllegalArgumentException invalidReleaseTag() {
        return new IllegalArgumentException("Invalid WebSnag release tag.");
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WebSnagVersion that)) {
            return false;
        }
        return versionCode == that.versionCode && versionName.equals(that.versionName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(versionName, versionCode);
    }

    @Override
    public String toString() {
        return "WebSnagVersion{versionName='" + versionName + "', versionCode=" + versionCode + "}";
    }
}
