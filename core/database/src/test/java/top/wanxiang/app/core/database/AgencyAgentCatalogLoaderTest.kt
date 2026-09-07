package top.wanxiang.app.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.wanxiang.app.core.model.AgentDepartments

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgencyAgentCatalogLoaderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val loader = AgencyAgentCatalogLoader(
        context = context,
        json = Json { ignoreUnknownKeys = true },
    )

    @Test
    fun curatedCatalogContainsOnlySoftwareDepartments() = runBlocking {
        val catalog = loader.load()

        assertEquals(136, catalog.profiles.size)
        assertEquals(
            AgentDepartments.agency.map { it.id }.toSet(),
            catalog.profiles.map { it.departmentId }.toSet(),
        )
        assertFalse(catalog.profiles.any { it.departmentId == "marketing" })
        assertFalse(catalog.profiles.any { it.departmentId == "finance" })
    }

    @Test
    fun promptsAreLoadedWithoutYamlFrontmatter() = runBlocking {
        val catalog = loader.load()
        val frontend = catalog.profiles.single { it.id == "agency_engineering_frontend_developer" }

        assertEquals("Frontend Developer", frontend.name)
        assertTrue(frontend.systemPrompt.startsWith("# Frontend Developer Agent Personality"))
        assertFalse(frontend.systemPrompt.startsWith("---"))
        assertTrue(frontend.systemPrompt.length > 1_000)
    }

    @Test
    fun catalogRevisionMatchesVendoredSource() = runBlocking {
        assertEquals("3c9588880b7cafaec325a104899fd8bbe27e7d72", loader.sourceRevision())
    }

    @Test
    fun catalogSyncReplacesLegacyBuiltinsAndPreservesCustomProfiles() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.agentSubagentDao()
            dao.upsert(
                AgentSubagentEntity(
                    id = "researcher",
                    name = "Legacy Researcher",
                    description = "legacy",
                    systemPrompt = "legacy",
                    departmentId = "custom",
                    isEnabled = true,
                    isBuiltin = true,
                    sortOrder = 0,
                ),
            )
            dao.upsert(
                AgentSubagentEntity(
                    id = "my_custom_agent",
                    name = "My Agent",
                    description = "custom",
                    systemPrompt = "custom prompt",
                    departmentId = "custom",
                    isEnabled = true,
                    isBuiltin = false,
                    sortOrder = 10_000,
                ),
            )
            dao.upsert(
                AgentSubagentEntity(
                    id = "agency_engineering_frontend_developer",
                    name = "Old Frontend Developer",
                    description = "old catalog entry",
                    systemPrompt = "old prompt",
                    defaultModelId = "profile-coder",
                    defaultModelVariant = "coder-v2",
                    departmentId = "engineering",
                    isEnabled = true,
                    isBuiltin = true,
                    sortOrder = 1,
                ),
            )
            dao.upsertSettings(AgentSubagentSettingsEntity())

            val repository = AgentSubagentRepository(dao, loader)
            repository.ensureInitialized()
            val profiles = repository.profiles.first()
            val routingIndex = repository.enabledIndex()
            val departmentCounts = repository.enabledDepartmentCounts()
                .associate { it.departmentId to it.enabledCount }

            assertEquals(137, profiles.size)
            assertFalse(profiles.any { it.id == "researcher" })
            assertTrue(profiles.any { it.id == "my_custom_agent" && !it.isBuiltin })
            assertEquals(136, profiles.count { it.isBuiltin })
            assertTrue(
                profiles.any {
                    it.id == "agency_engineering_frontend_developer" &&
                        it.isEnabled &&
                        it.defaultModelId == "profile-coder" &&
                        it.defaultModelVariant == "coder-v2" &&
                        it.systemPrompt.startsWith("# Frontend Developer Agent Personality")
                },
            )
            assertEquals(137, routingIndex.size)
            assertTrue(routingIndex.all { it.description.isNotBlank() })
            assertEquals(59, departmentCounts["engineering"])
            assertEquals(9, departmentCounts["testing"])
            assertEquals(1, departmentCounts[AgentDepartments.CUSTOM_ID])
            assertTrue(
                repository.findEnabledProfile("agency_engineering_frontend_developer")
                    ?.systemPrompt
                    ?.startsWith("# Frontend Developer Agent Personality") == true,
            )
        } finally {
            database.close()
        }
    }
}
