package websnag.buildlogic;

import static org.junit.Assert.*;

import java.nio.file.Path;
import org.junit.Test;

public class ReleaseArtifactIdentityTest {
    private final WebSnagVersion version = WebSnagVersion.parse("v1.0.0-alpha.5");
    private final String manifest = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
              package="websnag.elopenmike.com" android:versionName="1.0.0-alpha.5"
              android:versionCode="100001005"><application android:debuggable="false"/></manifest>
            """;

    @Test public void acceptsMatchingNonDebuggableManifest() {
        ReleaseArtifactIdentity.verifyManifest(manifest, version);
        ReleaseArtifactIdentity.verifyManifest(manifest.replace(" android:debuggable=\"false\"", ""), version);
    }

    @Test public void rejectsMismatchedVersionPackageDebuggableOrMalformedManifest() {
        for (String xml : new String[] {
                manifest.replace("100001005", "1"),
                manifest.replace("1.0.0-alpha.5", "0.0.0-dev"),
                manifest.replace("websnag.elopenmike.com", "wrong.package"),
                manifest.replace("debuggable=\"false\"", "debuggable=\"true\""),
                manifest.replace("debuggable=\"false\"", "debuggable=\"unknown\""),
                manifest.replace("<application", "<uses-permission android:name=\"android.permission.INTERNET\"/><application"),
                manifest.replace("<application", "<uses-permission-sdk-23 android:name=\"android.permission.INTERNET\"/><application"),
                "<broken",
                "<!DOCTYPE manifest SYSTEM \"file:///PRIVATE_SENTINEL\"><manifest/>"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> ReleaseArtifactIdentity.verifyManifest(xml, version));
        }
    }

    @Test public void rejectsMissingOrInvalidBundleWithoutPrintingPath() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ReleaseArtifactIdentity.verifyBundle(Path.of("/PRIVATE_SENTINEL"), "a".repeat(64)));
        assertFalse(error.getMessage().contains("PRIVATE_SENTINEL"));
        assertNull(error.getCause());
    }
}
