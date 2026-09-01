package websnag.buildlogic

private const val MAX_MAJOR_VERSION = 20
private const val MAX_MINOR_OR_PATCH_VERSION = 99
private const val MAX_PRERELEASE_SEQUENCE = 999
private const val MAX_ANDROID_VERSION_CODE = 2_100_000_000

data class WebSnagVersion(
    val versionName: String,
    val versionCode: Int,
) {
    companion object {
        val development = WebSnagVersion(
            versionName = "0.0.0-dev",
            versionCode = 1,
        )

        private val releaseTagPattern = Regex(
            """^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-(alpha|beta|rc)\.(0|[1-9]\d*))?$""",
        )

        fun parse(releaseTag: String): WebSnagVersion {
            val match = releaseTagPattern.matchEntire(releaseTag)
                ?: invalidReleaseTag()
            val major = match.groupValues[1].toBoundedInt("major", MAX_MAJOR_VERSION)
            val minor = match.groupValues[2].toBoundedInt(
                "minor",
                MAX_MINOR_OR_PATCH_VERSION,
            )
            val patch = match.groupValues[3].toBoundedInt(
                "patch",
                MAX_MINOR_OR_PATCH_VERSION,
            )
            val channel = match.groupValues[4]
            val sequence = match.groupValues[5].let { value ->
                if (value.isEmpty()) {
                    0
                } else {
                    value.toBoundedInt("prerelease sequence", MAX_PRERELEASE_SEQUENCE)
                        .also {
                            if (it == 0) {
                                invalidReleaseTag()
                            }
                        }
                }
            }
            val channelCode = when (channel) {
                "alpha" -> 1
                "beta" -> 2
                "rc" -> 3
                "" -> 9
                else -> invalidReleaseTag()
            }
            val calculatedCode =
                major.toLong() * 100_000_000L +
                    minor.toLong() * 1_000_000L +
                    patch.toLong() * 10_000L +
                    channelCode * 1_000L +
                    sequence
            if (calculatedCode !in 1..MAX_ANDROID_VERSION_CODE.toLong()) {
                invalidReleaseTag()
            }

            return WebSnagVersion(
                versionName = releaseTag.removePrefix("v"),
                versionCode = calculatedCode.toInt(),
            )
        }

        fun resolve(
            releaseTag: String?,
            releaseBuildRequested: Boolean,
        ): WebSnagVersion {
            if (releaseTag != null) {
                return parse(releaseTag)
            }
            check(!releaseBuildRequested) {
                "Release builds require -PwebsnagReleaseTag=vMAJOR.MINOR.PATCH[-CHANNEL.N]."
            }
            return development
        }

        private fun String.toBoundedInt(
            componentName: String,
            maximum: Int,
        ): Int {
            val value = toIntOrNull()
                ?: invalidReleaseTag()
            if (value > maximum) {
                throw IllegalArgumentException(
                    "Invalid WebSnag release tag: $componentName must be at most $maximum.",
                )
            }
            return value
        }

        private fun invalidReleaseTag(): Nothing =
            throw IllegalArgumentException("Invalid WebSnag release tag.")
    }
}
