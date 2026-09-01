package websnag.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSnagVersionTest {
    @Test
    fun `parses accepted release tags`() {
        val expectedVersions = mapOf(
            "v1.0.0-alpha.2" to WebSnagVersion("1.0.0-alpha.2", 100_001_002),
            "v1.0.0-beta.1" to WebSnagVersion("1.0.0-beta.1", 100_002_001),
            "v1.0.0-rc.1" to WebSnagVersion("1.0.0-rc.1", 100_003_001),
            "v1.0.0" to WebSnagVersion("1.0.0", 100_009_000),
            "v20.99.99" to WebSnagVersion("20.99.99", 2_099_999_000),
        )

        expectedVersions.forEach { (tag, expected) ->
            assertEquals(tag, expected, WebSnagVersion.parse(tag))
        }
    }

    @Test
    fun `version codes follow semantic release order`() {
        val orderedTags = listOf(
            "v1.0.0-alpha.1",
            "v1.0.0-alpha.999",
            "v1.0.0-beta.1",
            "v1.0.0-rc.1",
            "v1.0.0",
            "v1.0.1-alpha.1",
            "v1.1.0-alpha.1",
            "v2.0.0-alpha.1",
        )

        val codes = orderedTags.map { WebSnagVersion.parse(it).versionCode }

        assertEquals(codes.sorted(), codes)
        assertEquals(codes.size, codes.distinct().size)
    }

    @Test
    fun `rejects malformed and out of range tags`() {
        val invalidTags = listOf(
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
            "v999999999999999999999999.0.0",
        )

        invalidTags.forEach { tag ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                WebSnagVersion.parse(tag)
            }
            assertTrue(tag, error.message.orEmpty().contains("release tag"))
        }
    }

    @Test
    fun `provides deterministic development metadata`() {
        assertEquals(WebSnagVersion("0.0.0-dev", 1), WebSnagVersion.development)
    }

    @Test
    fun `uses development metadata for untagged local builds`() {
        assertEquals(
            WebSnagVersion.development,
            WebSnagVersion.resolve(releaseTag = null, releaseBuildRequested = false),
        )
    }

    @Test
    fun `requires a release tag for release builds`() {
        val error = assertThrows(IllegalStateException::class.java) {
            WebSnagVersion.resolve(releaseTag = null, releaseBuildRequested = true)
        }

        assertTrue(error.message.orEmpty().contains("websnagReleaseTag"))
    }

    @Test
    fun `uses the release tag whenever one is supplied`() {
        assertEquals(
            WebSnagVersion("1.2.3-beta.4", 102_032_004),
            WebSnagVersion.resolve(
                releaseTag = "v1.2.3-beta.4",
                releaseBuildRequested = false,
            ),
        )
    }

    @Test
    fun `does not echo malformed tag content in errors`() {
        val malformedTag = "v1.0.0\nUNTRUSTED_TAG_CONTENT"

        val error = assertThrows(IllegalArgumentException::class.java) {
            WebSnagVersion.parse(malformedTag)
        }

        assertFalse(error.message.orEmpty().contains(malformedTag))
    }
}
