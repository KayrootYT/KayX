package com.kayx.manager.data.filesystem

import android.content.Context
import android.provider.MediaStore
import java.io.File

/** Most recently modified user files (any type), newest first. Reads the MediaStore index, so it is instant. */
object RecentFiles {
    data class Item(val path: String, val name: String, val size: Long, val modifiedMs: Long)

    fun query(ctx: Context, limit: Int = 6): List<Item> = runCatching {
        val SIZE = MediaStore.MediaColumns.SIZE
        val MIME_TYPE = MediaStore.MediaColumns.MIME_TYPE
        val DATA = MediaStore.MediaColumns.DATA
        val DATE_MODIFIED = MediaStore.MediaColumns.DATE_MODIFIED
        val sel = "$SIZE > 0 AND $MIME_TYPE IS NOT NULL AND $DATA NOT LIKE '%/Android/%' " +
            "AND $DATA NOT LIKE '%/.%' AND $DATA NOT LIKE '%.pending-%' AND $DATA NOT LIKE '%.trashed-%'"
        ctx.contentResolver.query(
            MediaStore.Files.getContentUri("external"), arrayOf(DATA, SIZE, DATE_MODIFIED), sel, null, "$DATE_MODIFIED DESC"
        )?.use { cur ->
            val out = ArrayList<Item>(limit)
            while (cur.moveToNext() && out.size < limit) {
                val p = cur.getString(0) ?: continue
                val f = File(p)
                if (!f.isFile) continue
                out.add(Item(p, f.name, cur.getLong(1), cur.getLong(2) * 1000L))
            }
            out
        } ?: emptyList()
    }.getOrDefault(emptyList())
}
