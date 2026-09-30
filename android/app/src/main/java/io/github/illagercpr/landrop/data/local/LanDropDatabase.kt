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
 */
@Database(
    entities = [MessageEntity::class, TransferEntity::class],
    version = 1,
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
