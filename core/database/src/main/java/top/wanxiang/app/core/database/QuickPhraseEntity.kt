package top.wanxiang.app.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import top.wanxiang.app.core.model.QuickPhrase

@Entity(tableName = "quick_phrases")
data class QuickPhraseEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val description: String = "",
    val iconName: String = "Play",
    val targetProjectType: String? = null,
    val isEnabled: Boolean = true,
    val sortOrder: Int = 0,
    val isBuiltin: Boolean = false,
) {
    fun toDomain(): QuickPhrase = QuickPhrase(
        id = id,
        title = title,
        content = content,
        description = description,
        iconName = iconName,
        targetProjectType = targetProjectType,
        isEnabled = isEnabled,
        sortOrder = sortOrder,
        isBuiltin = isBuiltin,
    )

    companion object {
        fun fromDomain(phrase: QuickPhrase): QuickPhraseEntity = QuickPhraseEntity(
            id = phrase.id,
            title = phrase.title,
            content = phrase.content,
            description = phrase.description,
            iconName = phrase.iconName,
            targetProjectType = phrase.targetProjectType,
            isEnabled = phrase.isEnabled,
            sortOrder = phrase.sortOrder,
            isBuiltin = phrase.isBuiltin,
        )
    }
}

@Dao
interface QuickPhraseDao {
    @Query("SELECT * FROM quick_phrases ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<QuickPhraseEntity>>

    @Query("SELECT * FROM quick_phrases ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<QuickPhraseEntity>

    @Query("SELECT * FROM quick_phrases WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): QuickPhraseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(phrase: QuickPhraseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(phrases: List<QuickPhraseEntity>)

    @Query("UPDATE quick_phrases SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM quick_phrases WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM quick_phrases")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM quick_phrases")
    suspend fun count(): Int
}
