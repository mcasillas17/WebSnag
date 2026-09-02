package websnag.elopenmike.com

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import websnag.elopenmike.com.core.backup.BackupCodec
import websnag.elopenmike.com.core.data.AndroidKeystoreTagIdentityProtector
import websnag.elopenmike.com.core.data.DefaultNfcTagRepository
import websnag.elopenmike.com.core.data.DefaultProfileRepository
import websnag.elopenmike.com.core.data.LocalDataStore
import websnag.elopenmike.com.core.diagnostics.AndroidDiagnosticsStateSource
import websnag.elopenmike.com.core.diagnostics.DIAGNOSTICS_MAX_EXPORT_BYTES
import websnag.elopenmike.com.core.diagnostics.DIAGNOSTICS_SCHEMA_VERSION
import websnag.elopenmike.com.core.diagnostics.DiagnosticsJsonExporter
import websnag.elopenmike.com.core.diagnostics.DiagnosticsRepository
import java.security.KeyStore

/**
 * Instrumented proof that a real [DiagnosticsRepository]/[AndroidDiagnosticsStateSource] on an
 * actual device produces a schema-conformant, appropriately-sized, non-networked report -- the
 * JVM tests in [websnag.elopenmike.com.DiagnosticsRepositoryTest] (test source set) cover the
 * pure mapping/remediation logic in isolation and cannot exercise real Android APIs.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticsRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun buildRepository(): DiagnosticsRepository {
        val localDataStore = LocalDataStore(context)
        return DiagnosticsRepository(
            stateSource = AndroidDiagnosticsStateSource(context),
            profileRepository = DefaultProfileRepository(localDataStore),
            nfcTagRepository = DefaultNfcTagRepository(localDataStore),
            schedulesFlow = localDataStore.schedulesFlow,
            scheduleReconciliationFlow = localDataStore.scheduleReconciliationFlow,
            localErrorFlow = localDataStore.localErrorFlow
        )
    }

    @Test
    fun generatedReportMatchesRealDeviceAndAppIdentityAndStaysWithinTheSizeBound() = runBlocking {
        val report = buildRepository().currentReport()

        assertEquals(DIAGNOSTICS_SCHEMA_VERSION, report.schemaVersion)
        assertEquals(Build.VERSION.SDK_INT, report.deviceInfo.apiLevel)
        assertEquals(BackupCodec.VERSION, report.backupSchemaVersion)

        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val expectedVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        assertEquals(packageInfo.versionName.orEmpty(), report.appBuildInfo.versionName)
        assertEquals(expectedVersionCode, report.appBuildInfo.versionCode)

        val json = DiagnosticsJsonExporter.export(report)
        assertTrue(json.toByteArray(Charsets.UTF_8).size <= DIAGNOSTICS_MAX_EXPORT_BYTES)
    }

    @Test
    fun appDoesNotRequestInternetPermission() {
        @Suppress("DEPRECATION")
        val permissions = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.toSet().orEmpty()

        assertFalse("android.permission.INTERNET" in permissions)
    }

    @Test
    fun keystoreProbeReportsOnlyABooleanAndNeverCreatesTheKeyAsASideEffect() {
        // Two independent probe instances must observe the exact same availability: if reading the
        // probe ever created the key (instead of merely checking for it), the second read would
        // flip from false to true.
        val firstProbeReading = AndroidKeystoreTagIdentityProtector().isKeyAvailable()
        val secondProbeReading = AndroidKeystoreTagIdentityProtector().isKeyAvailable()

        assertEquals(firstProbeReading, secondProbeReading)

        // The snapshot threads the same Boolean-only signal through, deliberately never a real
        // key handle, alias, or key material.
        val snapshotReading = AndroidDiagnosticsStateSource(context).snapshot().keystoreKeyAvailable
        assertEquals(firstProbeReading, snapshotReading)
    }

    @Test
    fun keystoreProbeNeverCreatesTheKeyAsProvenByDirectAliasPresenceBeforeAndAfter() {
        // Stronger proof than keystoreProbeReportsOnlyABooleanAndNeverCreatesTheKeyAsASideEffect:
        // rather than only comparing two probe readings to each other, this reads the real
        // AndroidKeyStore alias presence directly (the same check the probe itself performs),
        // once before and once after invoking the probe. If the probe ever created the key as a
        // side effect, the alias would flip from absent to present between the two direct reads.
        // This never deletes a key or mutates any other shared device state; it only reads.
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val aliasPresentBeforeProbe = keyStore.containsAlias(AndroidKeystoreTagIdentityProtector.KEY_ALIAS)

        AndroidKeystoreTagIdentityProtector().isKeyAvailable()

        val aliasPresentAfterProbe = keyStore.containsAlias(AndroidKeystoreTagIdentityProtector.KEY_ALIAS)

        assertEquals(aliasPresentBeforeProbe, aliasPresentAfterProbe)
    }
}
