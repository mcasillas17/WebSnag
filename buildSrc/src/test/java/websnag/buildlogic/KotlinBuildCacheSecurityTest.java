package websnag.buildlogic;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class KotlinBuildCacheSecurityTest {
    @Test
    public void rejectsVersionsBeforeTheBuildCacheFix() {
        for (String version : List.of("1.9.25", "2.2.10", "2.4.0", "2.4.10", "2.4.19-RC99",
                "2.4.20-Alpha1", "2.4.20-Beta0")) {
            assertFalse(version, KotlinBuildCacheSecurity.isPatched(version));
        }
    }

    @Test
    public void acceptsPatchedBetasReleaseCandidatesAndStableVersions() {
        for (String version : List.of("2.4.20-Beta1", "2.4.20-Beta2", "2.4.20-RC", "2.4.20-RC2",
                "2.4.20", "2.4.21", "2.5.0-Beta1", "2.5.0", "3.0.0")) {
            assertTrue(version, KotlinBuildCacheSecurity.isPatched(version));
        }
    }

    @Test
    public void rejectsUnrecognizedVersionsRatherThanGuessingTheyArePatched() {
        assertFalse(KotlinBuildCacheSecurity.isPatched(null));
        for (String version : List.of("", "2.4", "2.4.20-Beta", "2.4.20-RC0", "2.4.20-dev-1",
                "2.4.20-SNAPSHOT", "2.4.20-RC2-dev-1", "2.4.20.1", " 2.4.20", "2.4.20 ",
                "999999999999999999999.0.0")) {
            assertFalse(version, KotlinBuildCacheSecurity.isPatched(version));
        }
    }
}
