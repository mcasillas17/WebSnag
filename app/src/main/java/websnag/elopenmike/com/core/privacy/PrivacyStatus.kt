package websnag.elopenmike.com.core.privacy

data class PrivacyStatus(
    val internetPermissionDeclared: Boolean,
    val accessibilityCanReadWindowContent: Boolean = false,
    val dataCategories: List<String> = listOf(
        "Profiles and schedules",
        "NFC tag metadata",
        "Optional focus-session history",
        "Theme and retention preferences"
    )
) {
    companion object {
        fun fromDeclaredPermissions(permissions: Set<String>): PrivacyStatus {
            return PrivacyStatus(internetPermissionDeclared = "android.permission.INTERNET" in permissions)
        }
    }
}
