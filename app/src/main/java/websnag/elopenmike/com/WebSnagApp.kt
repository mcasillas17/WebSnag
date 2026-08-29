package websnag.elopenmike.com

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import websnag.elopenmike.com.core.data.DefaultNfcTagRepository
import websnag.elopenmike.com.core.data.DefaultProfileRepository
import websnag.elopenmike.com.core.data.InstalledAppsRepository
import websnag.elopenmike.com.core.data.LocalDataStore
import websnag.elopenmike.com.core.data.NfcTagRepository
import websnag.elopenmike.com.core.data.ProfileRepository
import websnag.elopenmike.com.core.backup.BackupRepository
import websnag.elopenmike.com.core.enforcement.EnforcementEngine
import websnag.elopenmike.com.core.nfc.NfcActionResolver
import websnag.elopenmike.com.core.nfc.NfcManager

class WebSnagApp : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var localDataStore: LocalDataStore
        private set

    lateinit var profileRepository: ProfileRepository
        private set

    lateinit var nfcTagRepository: NfcTagRepository
        private set

    lateinit var installedAppsRepository: InstalledAppsRepository
        private set

    lateinit var nfcManager: NfcManager
        private set

    lateinit var nfcActionResolver: NfcActionResolver
        private set

    lateinit var enforcementEngine: EnforcementEngine
        private set

    lateinit var networkMonitor: websnag.elopenmike.com.core.network.NetworkMonitor
        private set

    lateinit var scheduleManager: websnag.elopenmike.com.core.schedule.ScheduleManager
        private set
    lateinit var backupRepository: BackupRepository
        private set

    override fun onCreate() {
        super.onCreate()

        localDataStore = LocalDataStore(this)
        profileRepository = DefaultProfileRepository(localDataStore)
        nfcTagRepository = DefaultNfcTagRepository(localDataStore)
        backupRepository = BackupRepository(localDataStore, profileRepository)
        installedAppsRepository = InstalledAppsRepository(this)
        nfcManager = NfcManager(this)
        nfcActionResolver = NfcActionResolver(profileRepository, nfcTagRepository)
        networkMonitor = websnag.elopenmike.com.core.network.AndroidNetworkMonitor(this, applicationScope)

        enforcementEngine = EnforcementEngine(profileRepository, localDataStore, applicationScope)
        EnforcementEngine.initialize(enforcementEngine)

        scheduleManager = websnag.elopenmike.com.core.schedule.ScheduleManager(
            localDataStore = localDataStore,
            profileRepository = profileRepository,
            enforcementEngine = enforcementEngine,
            coroutineScope = applicationScope,
            networkMonitor = networkMonitor
        )
        scheduleManager.start()

        // Preload default presets on first app startup
        applicationScope.launch {
            profileRepository.initializeDefaultProfilesIfNeeded()
        }
    }
}
