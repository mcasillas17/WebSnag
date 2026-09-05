package websnag.elopenmike.com.core.data

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FixtureCatalogTest {
    @Test fun jvmAndDeviceInputsAreIdenticalVersionedFixtures() {
        val directory = listOf(File("src/androidTest/assets/migrations/v1"), File("app/src/androidTest/assets/migrations/v1"))
            .single { it.isDirectory }
        val names = setOf("alpha1", "alpha2-current", "mixed", "dormant", "malformed")
        assertEquals(names.map { "$it.json" }.toSet(), directory.listFiles()!!.filter { it.extension == "json" }.map { it.name }.toSet())
        names.forEach { name ->
            MigrationFixtures.load(name) // Checks the fixture format version and preference representation.
            val jvm = checkNotNull(javaClass.getResourceAsStream("/migrations/v1/$name.json")).use { it.readBytes() }
            assertTrue("fixture $name must match on JVM and device", jvm.contentEquals(File(directory, "$name.json").readBytes()))
        }
    }
}
