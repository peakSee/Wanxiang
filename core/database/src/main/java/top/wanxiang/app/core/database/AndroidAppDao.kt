package top.wanxiang.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AndroidAppDao {
    @Query("SELECT * FROM android_apps ORDER BY isSystemApp ASC, label COLLATE NOCASE, packageName")
    fun observeAll(): Flow<List<AndroidAppEntity>>

    @Query("SELECT * FROM android_apps WHERE packageName = :packageName LIMIT 1")
    suspend fun findByPackageName(packageName: String): AndroidAppEntity?

    @Query("SELECT * FROM android_apps WHERE packageName LIKE '%' || :query || '%' OR label LIKE '%' || :query || '%' ORDER BY isSystemApp ASC, label COLLATE NOCASE LIMIT :limit")
    suspend fun search(query: String, limit: Int): List<AndroidAppEntity>

    @Query("SELECT COUNT(*) FROM android_apps")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(apps: List<AndroidAppEntity>)

    @Query("DELETE FROM android_apps WHERE packageName NOT IN (:packageNames)")
    suspend fun deleteMissing(packageNames: List<String>)

    @Transaction
    suspend fun reconcile(apps: List<AndroidAppEntity>) {
        upsertAll(apps)
        deleteMissing(apps.map { it.packageName })
    }
}
