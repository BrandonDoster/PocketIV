package cc.doster.pocketiv

import android.content.Context
import java.io.File

/** One scanned Pokemon. [ivs] = "a/d/s" once exact, else blank. [moves] = move types, comma separated. */
data class Record(
    val species: String, val cp: Int, val hp: Int, val ivLo: Int, val ivHi: Int, val ivs: String,
    val level: String, val moves: String, val seen: Long,
) {
    val key get() = "$species|$cp|$hp" // same key as ScanMemory
    val iv get() = if (ivLo == ivHi) "$ivLo%" else "$ivLo–$ivHi%"

    fun tsv() = listOf(species, cp, hp, ivLo, ivHi, ivs, level, moves, seen).joinToString("\t")

    companion object {
        fun from(s: Scan, now: Long = System.currentTimeMillis()): Record {
            val (lo, hi) = IvMath.ivRange(s.hits)
            val combos = s.hits.map { Triple(it.a, it.d, it.s) }.distinct()
            val ivs = combos.singleOrNull()?.let { "${it.first}/${it.second}/${it.third}" }.orEmpty()
            val lv = s.hits.map { it.level }.distinct().sorted()
            val fmt = { l: Double -> if (l % 1 == 0.0) "${l.toInt()}" else "$l" }
            val level = if (lv.size == 1) fmt(lv[0]) else "${fmt(lv.first())}-${fmt(lv.last())}"
            return Record(s.species.name, s.cp, s.hp, lo, hi, ivs, level, s.moveTypes.joinToString(","), now)
        }

        fun parse(line: String): Record? {
            val c = line.split('\t')
            if (c.size < 9) return null
            return Record(c[0], c[1].toInt(), c[2].toInt(), c[3].toInt(), c[4].toInt(), c[5], c[6], c[7], c[8].toLong())
        }
    }
}

/**
 * Every Pokemon the overlay has read, in files/history.tsv. One shared instance so the service and the
 * settings screen never overwrite each other.
 * ponytail: whole file rewritten on each change; fine for a few thousand rows, move to SQLite if it grows past that.
 */
class History private constructor(private val file: File) {
    private val rows = LinkedHashMap<String, Record>()

    init {
        if (file.exists()) file.readLines().mapNotNull(Record::parse).forEach { rows[it.key] = it }
    }

    val all: List<Record> @Synchronized get() = rows.values.toList()

    /** Insert or update. [replaces]: key of the same Pokemon before a power-up/evolution, dropped so it isn't counted twice. */
    @Synchronized
    fun put(r: Record, replaces: String? = null) {
        if (replaces == null && rows[r.key]?.copy(seen = r.seen) == r) return // nothing new; skip the write
        replaces?.let(rows::remove)
        rows[r.key] = r
        save()
    }

    @Synchronized
    fun clear() {
        rows.clear()
        save()
    }

    private fun save() = file.writeText(rows.values.joinToString("\n") { it.tsv() })

    fun csv(): String {
        val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        return "species,cp,hp,iv_min,iv_max,atk/def/sta,level,move_types,last_seen\n" + all.joinToString("\n") {
            listOf(it.species, it.cp, it.hp, it.ivLo, it.ivHi, it.ivs, it.level, it.moves.replace(',', ' '), date.format(it.seen))
                .joinToString(",") { v -> "\"$v\"" }
        }
    }

    companion object {
        @Volatile private var instance: History? = null

        fun get(ctx: Context) = instance ?: synchronized(this) {
            instance ?: History(File(ctx.applicationContext.filesDir, "history.tsv")).also { instance = it }
        }
    }
}
