package websnag.elopenmike.com.core.data

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID

/** Each test owns one file and joins the old scope before reopening that file. */
internal class MigrationStoreHarness {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "synthetic-migration-${UUID.randomUUID()}").apply { check(mkdirs()) }
    private val file = File(directory, "fixture.preferences_pb")
    private var scope: CoroutineScope? = null
    lateinit var store: DataStore<Preferences>
        private set
    val local get() = LocalDataStore(store)

    fun load(name: String): Preferences {
        val fixture = Json.parseToJsonElement(context.assets.open("migrations/v1/$name.json").bufferedReader().use { it.readText() }).jsonObject
        check(fixture.getValue("fixtureVersion").jsonPrimitive.int == 1)
        return mutablePreferencesOf().apply {
            fixture.getValue("preferences").jsonObject.forEach { (key, value) ->
                when {
                    key == "history_retention_days" -> this[intPreferencesKey(key)] = value.jsonPrimitive.int
                    key.endsWith("_json") -> this[stringPreferencesKey(key)] = value.toString()
                    else -> this[stringPreferencesKey(key)] = value.jsonPrimitive.content
                }
            }
            fixture["rawOverrides"]?.jsonObject?.forEach { (key, value) ->
                this[stringPreferencesKey(key)] = value.jsonPrimitive.content
            }
        }
    }

    suspend fun open(migrations: List<DataMigration<Preferences>> = emptyList()) {
        scope?.coroutineContext?.get(Job)?.cancelAndJoin()
        val next = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = next
        store = PreferenceDataStoreFactory.create(migrations = migrations, scope = next) { file }
    }

    suspend fun seed(name: String) {
        open()
        store.updateData { load(name) }
    }
    suspend fun raw(): Preferences = store.data.first()
    suspend fun close() {
        scope?.coroutineContext?.get(Job)?.cancelAndJoin()
        check(directory.deleteRecursively())
    }
}
