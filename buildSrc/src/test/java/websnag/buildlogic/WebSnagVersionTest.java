package websnag.buildlogic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.Test;

public class WebSnagVersionTest {
    @Test
    public void parsesAcceptedReleaseTags() {
        Map<String, WebSnagVersion> expectedVersions = Map.of(
                "v1.0.0-alpha.2", new WebSnagVersion("1.0.0-alpha.2", 100_001_002),
                "v1.0.0-beta.1", new WebSnagVersion("1.0.0-beta.1", 100_002_001),
                "v1.0.0-rc.1", new WebSnagVersion("1.0.0-rc.1", 100_003_001),
                "v1.0.0", new WebSnagVersion("1.0.0", 100_009_000),
                "v20.99.99", new WebSnagVersion("20.99.99", 2_099_999_000));

        expectedVersions.forEach(
                (tag, expected) -> assertEquals(tag, expected, WebSnagVersion.parse(tag)));
    }

    @Test
    public void versionCodesFollowSemanticReleaseOrder() {
        List<String> orderedTags = List.of(
                "v1.0.0-alpha.1",
                "v1.0.0-alpha.999",
                "v1.0.0-beta.1",
                "v1.0.0-rc.1",
                "v1.0.0",
                "v1.0.1-alpha.1",
                "v1.1.0-alpha.1",
                "v2.0.0-alpha.1");

        List<Integer> codes =
                orderedTags.stream().map(tag -> WebSnagVersion.parse(tag).getVersionCode()).toList();

        assertEquals(codes.stream().sorted().toList(), codes);
        assertEquals(codes.size(), codes.stream().distinct().count());
    }

    @Test
    public void rejectsMalformedAndOutOfRangeTags() {
        List<String> invalidTags = List.of(
                "",
                "1.0.0",
                "v1.0",
                "v1.0.0 ",
                " v1.0.0",
                "v+1.0.0",
                "v-1.0.0",
                "v01.0.0",
                "v1.00.0",
                "v1.0.00",
                "v21.0.0",
                "v1.100.0",
                "v1.0.100",
                "v1.0.0-alpha.0",
                "v1.0.0-alpha.01",
                "v1.0.0-alpha.1000",
                "v1.0.0-preview.1",
                "v1.0.0-ALPHA.1",
                "v999999999999999999999999.0.0");

        invalidTags.forEach(tag -> {
            IllegalArgumentException error =
                    assertThrows(IllegalArgumentException.class, () -> WebSnagVersion.parse(tag));
            assertTrue(tag, error.getMessage().contains("release tag"));
        });
    }

    @Test
    public void providesDeterministicDevelopmentMetadata() {
        assertEquals(new WebSnagVersion("0.0.0-dev", 1), WebSnagVersion.development());
    }

    @Test
    public void usesDevelopmentMetadataForUntaggedLocalBuilds() {
        assertEquals(WebSnagVersion.development(), WebSnagVersion.resolve(null, false));
    }

    @Test
    public void requiresAReleaseTagForReleaseBuilds() {
        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> WebSnagVersion.resolve(null, true));

        assertTrue(error.getMessage().contains("websnagReleaseTag"));
    }

    @Test
    public void usesTheReleaseTagWheneverOneIsSupplied() {
        assertEquals(
                new WebSnagVersion("1.2.3-beta.4", 102_032_004),
                WebSnagVersion.resolve("v1.2.3-beta.4", false));
    }

    @Test
    public void doesNotEchoMalformedTagContentInErrors() {
        String malformedTag = "v1.0.0\nUNTRUSTED_TAG_CONTENT";

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> WebSnagVersion.parse(malformedTag));

        assertFalse(error.getMessage().contains(malformedTag));
    }
}
