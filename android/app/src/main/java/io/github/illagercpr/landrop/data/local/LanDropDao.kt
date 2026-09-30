package io.github.illagercpr.landrop.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    /** 时间线（新→旧），供聊天气泡列表使用。 */
    @Query("SELECT * FROM messages ORDER BY seq DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<MessageEntity>>

    /** 增量补偿：拉取本地缺失的后续消息。 */
    @Query("SELECT * FROM messages WHERE seq > :since ORDER BY seq ASC")
    suspend fun loadSince(since: Long): List<MessageEntity>

    /** 本地游标：断线重连时作为 `since` 参数。 */
    @Query("SELECT COALESCE(MAX(seq), 0) FROM messages")
    suspend fun latestSeq(): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: MessageEntity)

    @Query("DELETE FROM messages")
    suspend fun clear()
}

@Dao
interface TransferDao {

    @Query("SELECT * FROM transfers ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE state IN (:states)")
    suspend fun loadByStates(states: List<String>): List<TransferEntity>

    @Query("SELECT * FROM transfers WHERE id = :id")
    suspend fun findById(id: String): TransferEntity?

    /** 自动接收的去重依据：同一条文件消息只允许发起一次传输（手动下载过或自动过都算）。 */
    @Query("SELECT * FROM transfers WHERE message_id = :messageId LIMIT 1")
    suspend fun findByMessageId(messageId: String): TransferEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: TransferEntity)

    @Query("DELETE FROM transfers")
    suspend fun clear()
}
