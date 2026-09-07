package top.wanxiang.app.core.database

import androidx.room.Entity

/** Privileged, locally cached state for one Android package. */
@Entity(tableName = "android_apps", primaryKeys = ["packageName"])
data class AndroidAppEntity(
    val packageName: String,
    val label: String,
    val uid: Int,
    val apkPath: String,
    val isSystemApp: Boolean,
    val isEnabled: Boolean,
    val isSuspended: Boolean,
    val isNetworkRestricted: Boolean,
    val lastSyncedAt: Long,
)
