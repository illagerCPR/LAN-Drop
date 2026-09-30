package io.github.illagercpr.landrop.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 客户端本地缓存：会话消息 + 传输记录。
 *
 * 这是纯缓存（服务端才是权威账本），因此允许破坏性迁移——
 * 需要长期保留的数据是「已下载到本地的文件」，它们不在库里。
 *
 * v2：transfers 增加 `remote_file_id`（下载方向断点续传要靠它重新发起 Range 请求）。
 * 破坏性迁移会丢掉传输记录，代价只是「升级 App 时正在传的任务无法续传」，
 * 源文件仍在手机上，重新发起即可。
 */
@Database(
    entities = [MessageEntity::class, TransferEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class LanDropDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao

    abstract fun transferDao(): TransferDao

    companion object {
        private const val DB_NAME = "lan-drop.db"

        @Volatile
        private var instance: LanDropDatabase? = null

        fun get(context: Context): LanDropDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): LanDropDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                LanDropDatabase::class.java,
                DB_NAME,
            )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
