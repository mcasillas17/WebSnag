package org.websnag

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.websnag.core.data.DefaultNfcTagRepository
import org.websnag.core.data.DefaultProfileRepository
import org.websnag.core.data.InstalledAppsRepository
import org.websnag.core.data.LocalDataStore
import org.websnag.core.data.NfcTagRepository
import org.websnag.core.data.ProfileRepository
import org.websnag.core.enforcement.EnforcementEngine
import org.websnag.core.nfc.NfcActionResolver
import org.websnag.core.nfc.NfcManager

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

    lateinit var scheduleManager: org.websnag.core.schedule.ScheduleManager
        private set

    override fun onCreate() {
        super.onCreate()

        localDataStore = LocalDataStore(this)
        profileRepository = DefaultProfileRepository(localDataStore)
        nfcTagRepository = DefaultNfcTagRepository(localDataStore)
        installedAppsRepository = InstalledAppsRepository(this)
        nfcManager = NfcManager(this)
        nfcActionResolver = NfcActionResolver(profileRepository, nfcTagRepository)

        enforcementEngine = EnforcementEngine(profileRepository, localDataStore, applicationScope)
        EnforcementEngine.initialize(enforcementEngine)

        scheduleManager = org.websnag.core.schedule.ScheduleManager(
            localDataStore = localDataStore,
            profileRepository = profileRepository,
            enforcementEngine = enforcementEngine,
            coroutineScope = applicationScope
        )
        scheduleManager.start()

        // Preload default presets on first app startup
        applicationScope.launch {
            profileRepository.initializeDefaultProfilesIfNeeded()
        }
    }
}
