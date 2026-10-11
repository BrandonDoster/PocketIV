package cc.doster.pocketiv

import android.content.Context
import kotlin.math.floor
import kotlin.math.sqrt

data class Species(
    val name: String, val dex: Int, val family: String, val atk: Int, val def: Int, val sta: Int,
    val types: List<String> = emptyList(),
) {
    /** Name as GO shows it, minus form. OCR drops Nidoran's ♀/♂, so both match "Nidoran"; stats pick the right one. */
    val baseName get() = name.substringBefore(" (").removeSuffix(" Female").removeSuffix(" Male")

    companion object {
        fun load(ctx: Context) = parse(ctx.assets.open("species.tsv").bufferedReader().readText())

        fun parse(tsv: String) = tsv.lines().filter { it.isNotBlank() }.map {
            val c = it.split('\t')
            Species(c[0], c[1].toInt(), c[2], c[3].toInt(), c[4].toInt(), c[5].toInt(), c.getOrNull(6)?.split(',').orEmpty())
        }
    }
}

/** Move name -> type, from assets/moves.tsv. */
object Moves {
    fun load(ctx: Context) = parse(ctx.assets.open("moves.tsv").bufferedReader().readText())
    fun parse(tsv: String) = tsv.lines().filter { it.isNotBlank() }.associate { it.substringBefore('\t') to it.substringAfter('\t') }
}

data class Hit(val level: Double, val a: Int, val d: Int, val s: Int)

/** Same math as Calcy IV 3.5 (calc/C11402v.java, gamestats/C11562o.java). See CALCY_NOTES.md. */
object IvMath {
    /** CP multiplier, level 1..55 in 0.5 steps; index = level*2-2. */
    val CPM = doubleArrayOf(
        0.0939999968, 0.1351374388, 0.1663978696, 0.1926509142, 0.2157324702, 0.2365726531, 0.2557200491, 0.2735303938,
        0.2902498841, 0.3060573936, 0.3210875988, 0.3354450464, 0.3492126763, 0.3624577522, 0.3752355874, 0.3875924051,
        0.3995672762, 0.4111935496, 0.4225000143, 0.4329264164, 0.4431075454, 0.4530599415, 0.4627983868, 0.4723360837,
        0.481684953, 0.490855813, 0.499858439, 0.5087017417, 0.5173939466, 0.5259425044, 0.5343543291, 0.5426357388,
        0.5507926941, 0.5588306189, 0.5667545199, 0.5745691657, 0.5822789073, 0.589887917, 0.5974000096, 0.6048236489,
        0.6121572852, 0.619404108, 0.6265671253, 0.6336491704, 0.6406529546, 0.6475809813, 0.6544356346, 0.6612192392,
        0.6679340005, 0.6745818853, 0.6811649203, 0.6876848936, 0.6941436529, 0.7005429268, 0.7068842053, 0.7131690979,
        0.7193990946, 0.7255756259, 0.7317000031, 0.7347410321, 0.7377694845, 0.7407855988, 0.7437894344, 0.74678123,
        0.749761045, 0.7527291179, 0.7556855083, 0.7586303949, 0.7615638375, 0.7644860744, 0.7673971653, 0.7702972889,
        0.7731865048, 0.7760649323, 0.7789327502, 0.7817900777, 0.7846369743, 0.7874735594, 0.7903000116, 0.7928039432,
        0.7953000069, 0.7978039384, 0.8003000021, 0.8028038923, 0.8052999973, 0.8078038692, 0.8102999926, 0.8128038645,
        0.8152999878, 0.8178038001, 0.820299983, 0.8228037953, 0.8252999783, 0.827803731, 0.8302999735, 0.8328037262,
        0.8353000283, 0.8378037214, 0.8403000236, 0.8428037167, 0.8453000188, 0.8478037119, 0.850300014, 0.8528036475,
        0.8553000093, 0.8578036427, 0.8603000045, 0.862803638, 0.8652999997,
    )

    /** Stardust per power-up for whole level 1..49 (covers L and L+0.5). Calcy serverdata/PowerUpSettings.java. */
    private val DUST = intArrayOf(
        200, 200, 400, 400, 600, 600, 800, 800, 1000, 1000, 1300, 1300, 1600, 1600, 1900, 1900, 2200, 2200,
        2500, 2500, 3000, 3000, 3500, 3500, 4000, 4000, 4500, 4500, 5000, 5000, 6000, 6000, 7000, 7000, 8000, 8000,
        9000, 9000, 10000, 10000, 11000, 11000, 12000, 12000, 13000, 13000, 14000, 14000, 15000,
    )
    private val DUST_MULT = doubleArrayOf(1.0, 1.2, 0.9, 0.5) // normal, shadow, purified, lucky

    val ALL_LEVELS = List(CPM.size) { it / 2.0 + 1 }

    fun isDustCost(n: Int) = DUST.any { d -> DUST_MULT.any { Math.round(d * it).toInt() == n } }

    /** Half-levels whose power-up costs [dust]. Lucky/shadow prices can collide, so this over-includes; solve() trims. */
    fun levelsForDust(dust: Int?): List<Double> {
        if (dust == null) return ALL_LEVELS
        return DUST.indices.filter { i -> DUST_MULT.any { Math.round(DUST[i] * it).toInt() == dust } }
            .flatMap { listOf(it + 1.0, it + 1.5) }
    }

    fun cpm(level: Double) = CPM[(level * 2 - 2).toInt()]

    fun cp(sp: Species, a: Int, d: Int, s: Int, level: Double): Int {
        val m = cpm(level)
        return maxOf(10, floor((sp.atk + a) * sqrt((sp.def + d).toDouble() * (sp.sta + s)) * m * m / 10).toInt())
    }

    fun hp(sp: Species, s: Int, level: Double) = maxOf(10, floor((sp.sta + s) * cpm(level)).toInt())

    fun solve(sp: Species, cp: Int, hp: Int, levels: List<Double> = ALL_LEVELS): List<Hit> = buildList {
        for (lv in levels) for (s in 0..15) if (hp(sp, s, lv) == hp)
            for (a in 0..15) for (d in 0..15) if (cp(sp, a, d, s, lv) == cp) add(Hit(lv, a, d, s))
    }

    private fun white(i: Int) = if (i == 0) "⓪" else (0x2460 + i - 1).toChar().toString()
    private fun black(i: Int) = when {
        i == 0 -> "⓿"
        i <= 10 -> (0x2776 + i - 1).toChar().toString()
        else -> (0x24EB + i - 11).toChar().toString()
    }

    private fun pct(sum: Double) = (sum / 45 * 100 + 0.5).toInt() // Java %.0f is HALF_UP

    /** Lowest and highest IV% across [hits]. */
    fun ivRange(hits: List<Hit>) = pct(hits.minOf { it.a + it.d + it.s }.toDouble()) to pct(hits.maxOf { it.a + it.d + it.s }.toDouble())

    /** Calcy's alternate type symbol set (renaming/C11541r.java), the one used in existing names (㊋ fire, ☯ psychic). */
    val TYPE_SYMBOL = mapOf(
        "normal" to "○", "fighting" to "⚠", "flying" to "☁", "poison" to "⛽", "ground" to "㊏", "rock" to "☗",
        "bug" to "᯼", "ghost" to "ᗝ", "steel" to "⚓", "fire" to "㊋", "water" to "☂", "grass" to "⸙",
        "electric" to "⚡", "psychic" to "☯", "ice" to "❄", "dragon" to "☬", "dark" to "♠", "fairy" to "❤",
    )

    /** Supported `$Tag$`s and what each prints (shown in the app's help). */
    val TAGS = linkedMapOf(
        "Perf/IV%" to "! on a 100%, otherwise the average IV%",
        "MoveTypes" to "Move type symbols (㊋ fire, ☯ psychic, ☂ water…), read from the moves on screen",
        "IV%Avg" to "Average IV% over the possible combos, ~ added when not exact",
        "IV%Range" to "Lowest–highest IV%, one number once certain",
        "IVPerfect" to "! on a 100%, otherwise nothing",
        "Combs" to "Attack/Defense/HP IVs as circled numbers: ① exact, ❶ averaged",
        "Lvl" to "Pokémon level, ½ on half levels, ? when unsure",
        "Name3" to "First 3 letters of the species",
        "Name" to "Full species name",
        "NameFill" to "Species name cut to whatever room is left of GO's 12 characters",
    )

    /** Calcy-style `$Tag$` nickname. Unknown tags stay literal. Never truncates (GO cuts at 12), except NameFill fits. */
    fun rename(scheme: String, name: String, hits: List<Hit>, moveTypes: List<String> = emptyList()): String {
        if (hits.isEmpty()) return ""
        val n = hits.size
        val (lo, hi) = ivRange(hits)
        val mean = listOf(hits.sumOf { it.a } / n.toDouble(), hits.sumOf { it.d } / n.toDouble(), hits.sumOf { it.s } / n.toDouble())
        val exactStat = listOf(hits.map { it.a }.toSet().size == 1, hits.map { it.d }.toSet().size == 1, hits.map { it.s }.toSet().size == 1)
        val lv = Math.round(hits.sumOf { it.level } / n * 2) / 2.0
        val avg = "${pct(mean.sum())}" + if (hits.toSet().size == 1) "" else "~"
        val tags = mapOf(
            "Perf/IV%" to if (lo == 100) "!" else avg,
            "MoveTypes" to moveTypes.distinct().joinToString("") { TYPE_SYMBOL[it].orEmpty() },
            "IV%Avg" to avg,
            "IV%Range" to if (lo == hi) "$lo" else "$lo-$hi",
            "IVPerfect" to if (lo == 100) "!" else "",
            "Combs" to mean.indices.joinToString("") { if (exactStat[it]) white(mean[it].toInt()) else black((mean[it] + 0.5).toInt()) },
            "Lvl" to "%02d".format(lv.toInt()) + (if (lv % 1 != 0.0) "½" else "") + (if (hits.map { it.level }.toSet().size == 1) "" else "?"),
            "Name3" to name.take(3),
            "Name" to name,
        )
        var out = scheme
        for ((k, v) in tags) out = out.replace("\$$k\$", v)
        if ("\$NameFill\$" in out) { // fill leftover room (12 UTF-16 chars, 24 UTF-8 bytes) with the name
            val rest = out.replace("\$NameFill\$", "")
            var fill = ""
            for (c in name) {
                val t = rest + fill + c
                if (t.length > 12 || t.toByteArray().size > 24) break
                fill += c
            }
            out = out.replace("\$NameFill\$", fill)
        }
        return out.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }
}
