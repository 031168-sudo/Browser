package com.andrey.readerlite

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Статья в локальной базе. Блоки хранятся JSON-строкой, чтобы сохранённую статью
 * можно было открыть без сети.
 */
@Entity(tableName = "articles")
data class ArticleEntity(
    @PrimaryKey val url: String,
    val title: String,
    val siteName: String,
    val blocksJson: String,
    /** Когда статью отложили в «Сохранённые». 0 — не сохранена. */
    val savedAt: Long,
    /** Когда открывали в последний раз, по этому полю строится история. */
    val lastOpenedAt: Long,
    val saved: Boolean,
)

@Dao
interface ArticleDao {

    @Query("SELECT * FROM articles ORDER BY lastOpenedAt DESC LIMIT 300")
    fun history(): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles WHERE saved = 1 ORDER BY savedAt DESC")
    fun saved(): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles WHERE url = :url LIMIT 1")
    suspend fun byUrl(url: String): ArticleEntity?

    @Upsert
    suspend fun upsert(entity: ArticleEntity)

    @Query("DELETE FROM articles WHERE saved = 0")
    suspend fun clearHistory()

    @Query("DELETE FROM articles WHERE url = :url")
    suspend fun delete(url: String)
}

@Database(entities = [ArticleEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun articles(): ArticleDao
}

object BlockCodec {

    fun encode(blocks: List<Block>): String {
        val array = JSONArray()
        for (block in blocks) {
            val o = JSONObject()
            when (block) {
                is Block.Heading -> o.put("t", "h").put("l", block.level).put("v", block.text)
                is Block.Paragraph -> o.put("t", "p").put("v", block.text)
                is Block.Quote -> o.put("t", "q").put("v", block.text)
                is Block.ListItem -> o.put("t", "li").put("v", block.text)
                is Block.Code -> o.put("t", "c").put("v", block.text)
                is Block.Image -> o.put("t", "i").put("u", block.url).put("v", block.alt)
            }
            array.put(o)
        }
        return array.toString()
    }

    fun decode(json: String): List<Block> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            when (o.getString("t")) {
                "h" -> Block.Heading(o.getInt("l"), o.getString("v"))
                "q" -> Block.Quote(o.getString("v"))
                "li" -> Block.ListItem(o.getString("v"))
                "c" -> Block.Code(o.getString("v"))
                "i" -> Block.Image(o.getString("u"), o.optString("v"))
                else -> Block.Paragraph(o.getString("v"))
            }
        }
    }
}
