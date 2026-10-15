package dev.aliberke.sesli.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Library entry. The text itself lives in files/docs/<id>.txt so huge books never hit the cursor-window limit. */
@Entity(tableName = "documents")
data class Document(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** text, web, pdf, epub, docx, html, markdown, image */
    val source: String,
    val sourceUri: String? = null,
    /** BCP-47 tag detected at import, e.g. "en" or "tr". */
    val language: String? = null,
    val length: Int,
    /** Character offset where listening stopped. */
    val progress: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val openedAt: Long = System.currentTimeMillis(),
)

fun Document.progressFraction(): Float =
    if (length == 0) 0f else (progress.toFloat() / length).coerceIn(0f, 1f)

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY openedAt DESC")
    fun observeAll(): Flow<List<Document>>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun get(id: Long): Document?

    @Insert
    suspend fun insert(document: Document): Long

    @Query("UPDATE documents SET progress = :progress WHERE id = :id")
    suspend fun updateProgress(id: Long, progress: Int)

    @Query("UPDATE documents SET openedAt = :time WHERE id = :id")
    suspend fun markOpened(id: Long, time: Long)

    @Query("UPDATE documents SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Delete
    suspend fun delete(document: Document)
}

@Database(entities = [Document::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "sesli.db").build()
    }
}
