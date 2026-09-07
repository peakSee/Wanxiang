package top.wanxiang.app.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HarnessSessionModelBindingTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: HarnessSessionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomHarnessSessionRepository(database.harnessSessionDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun changingModelSelectionOnlyUpdatesTargetSession() = runBlocking {
        repository.upsert(session("session-a", "profile-a", "model-a"))
        repository.upsert(session("session-b", "profile-b", "model-b"))

        repository.setModelSelection("session-a", "profile-c", "model-c", updatedAt = 30L)

        val first = requireNotNull(repository.findById("session-a"))
        val second = requireNotNull(repository.findById("session-b"))
        assertEquals("profile-c", first.modelId)
        assertEquals("model-c", first.modelVariant)
        assertEquals(30L, first.updatedAt)
        assertEquals("profile-b", second.modelId)
        assertEquals("model-b", second.modelVariant)
        assertEquals(20L, second.updatedAt)
    }

    private fun session(id: String, modelId: String, modelVariant: String) = HarnessSessionEntity(
        id = id,
        title = id,
        createdAt = 10L,
        updatedAt = 20L,
        modelId = modelId,
        modelVariant = modelVariant,
    )
}
