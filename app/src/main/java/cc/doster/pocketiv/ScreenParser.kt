package cc.doster.pocketiv

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** One OCR line. y = vertical center, h = height (fractions of screen height); x0 = left edge (fraction of width). */
data class Line(val text: String, val y: Double, val x0: Double = 0.0, val h: Double = 0.0)

/** Screen pixels as ARGB ints, so the readers run on a Bitmap on device and a BufferedImage in tests. */
class Pixels(val w: Int, val h: Int, private val get: (Int, Int) -> Int) {
    operator fun invoke(x: Double, y: Double) = get(x.toInt().coerceIn(0, w - 1), y.toInt().coerceIn(0, h - 1))
}

/** [arcOff]: the dot was found but fits no possible level, so the trainer level setting is probably wrong. */
data class Scan(
    val species: Species, val cp: Int, val hp: Int, val hits: List<Hit>, val arcOff: Boolean = false,
    val moveTypes: List<String> = emptyList(),
)

/**
 * Remembers readings per Pokemon (species + CP + HP). Every frame votes for the combos it allows, and the most-voted
 * combos win. One frame with a wing over the arc dot can't override good frames, and the appraisal result
 * survives closing the appraisal.
 */
class ScanMemory(private val size: Int = 50) {
    private val votes = object : LinkedHashMap<String, MutableMap<Hit, Int>>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, MutableMap<Hit, Int>>) = this.size > size
    }
    /** Move types seen per Pokemon: moves show on the detail screen but the appraisal covers them. */
    private val moves = object : LinkedHashMap<String, LinkedHashSet<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, LinkedHashSet<String>>) = this.size > size
    }
    private var last: Pair<Scan, Long>? = null

    /** Key of the pre-power-up/evolution reading the last add() carried over from, else null. */
    var linkedFrom: String? = null
        private set

    private fun key(s: Scan) = "${s.species.name}|${s.cp}|${s.hp}"

    fun add(scan: Scan, now: Long = System.currentTimeMillis()): Scan {
        val key = key(scan)
        linkedFrom = null
        val v = votes[key] ?: mutableMapOf<Hit, Int>().also {
            votes[key] = it
            linked(scan, now)?.let { carried ->
                it.putAll(carried)
                linkedFrom = last?.first?.let(::key)
            }
        }
        for (h in scan.hits) v[h] = (v[h] ?: 0) + 1
        val top = v.values.max()
        val types = moves.getOrPut(key) { LinkedHashSet() }.apply { addAll(scan.moveTypes) }
        val result = scan.copy(hits = v.filterValues { it == top }.keys.sortedBy { it.level }, moveTypes = types.toList())
        last = result to now
        return result
    }

    /**
     * Power-up (same species, +0.5..+2 levels) or evolution (same family, same level) right after the previous
     * reading: IVs never change, so carry the previous result over with its full weight.
     * ponytail: swiping to a different same-species Pokemon with higher CP and matching IVs would wrongly link;
     * rare, and the next appraisal outvotes it.
     */
    private fun linked(scan: Scan, now: Long): Map<Hit, Int>? {
        val (prev, t) = last ?: return null
        if (now - t > 120_000 || scan.cp <= prev.cp) return null
        val sameSpecies = prev.species == scan.species
        if (!sameSpecies && prev.species.family != scan.species.family) return null
        val weight = votes[key(prev)]?.values?.maxOrNull() ?: return null
        val carried = scan.hits.filter { h ->
            prev.hits.any { p ->
                p.a == h.a && p.d == h.d && p.s == h.s &&
                    if (sameSpecies) h.level - p.level in 0.5..2.0 else h.level == p.level
            }
        }
        return carried.ifEmpty { null }?.associateWith { weight }
    }
}

/** Reads a Pokemon GO detail screen (English UI). Returns null when the screen isn't one or nothing fits. */
object ScreenParser {
    /** OCR look-alikes in GO's font and the digits each can really be, most likely first ("12O" HP, "CPÓ63" = 663). */
    private val LOOKALIKE = mapOf(
        'O' to "0", 'o' to "0", 'D' to "0", 'Q' to "0", 'I' to "1", 'l' to "1", 'i' to "1", '|' to "1",
        'S' to "5", 's' to "5", 'B' to "8", 'Z' to "2", 'Ó' to "60", 'ó' to "60", 'Ö' to "60", 'ö' to "60", 'Ò' to "60",
    )
    private const val D = """[\dOoDQIli|SsBZÓóÖöÒ]""" // digits + LOOKALIKE keys
    private val CP = Regex("""[CcO]\s*[Pp]\s*($D{2,5})""") // "OP26": C misread too
    private val HP = Regex("""($D{1,4})\s*/\s*($D{1,4})\s*HP""", RegexOption.IGNORE_CASE)

    /** Every number [s] could be, most likely reading first. */
    private fun readings(s: String): List<Int> =
        s.fold(listOf("")) { acc, c -> acc.flatMap { p -> (LOOKALIKE[c] ?: c.toString()).map { p + it } } }
            .take(16).mapNotNull { it.toIntOrNull() }.distinct()
    private val CANDY = Regex("""^(.+?)\s+CANDY""", RegexOption.IGNORE_CASE)

    /** Uppercase A-Z only, accents stripped first so OCR's "Flabébé" matches "Flabebe". */
    private fun norm(s: String) =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).uppercase().filter { it in 'A'..'Z' }
    private fun cps(lines: List<Line>) = lines.firstNotNullOfOrNull { CP.find(it.text) }?.groupValues?.get(1)?.let(::readings).orEmpty()
    private fun hps(lines: List<Line>) = lines.firstNotNullOfOrNull { HP.find(it.text) }?.groupValues?.get(2)?.let(::readings).orEmpty()

    /** Most likely CP on screen, or null when this isn't a Pokemon screen. */
    fun cp(lines: List<Line>) = cps(lines).firstOrNull()

    // OCR drops spaces here: "This Chařmanderwas caughton 7/6/2016"
    private val THIS = Regex("""^This\s*(.+?)\s*was\s*(?:caught|hatched|received|found)""", RegexOption.IGNORE_CASE)

    /**
     * [maxArcLevel]: level at the right end of the arc, from trainer level (see Prefs.maxArcLevel).
     * [known]: the previous result; reused for species when this frame shows no name (renamed, candy covered).
     */
    fun parse(
        lines: List<Line>, dex: List<Species>, px: Pixels? = null, maxArcLevel: Double = 50.0, known: Scan? = null,
        moves: Map<String, String> = emptyMap(),
    ): Scan? {
        val hps = hps(lines)
        for (cp in cps(lines)) for (hp in hps)
            parseAt(cp, hp, lines, dex, px, maxArcLevel, known)?.let { return it.copy(moveTypes = moveTypes(lines, moves)) }
        return null
    }

    /**
     * Types of the moves listed on screen. A line must be the move name, allowing one stray leading char where OCR
     * reads the type icon ("OStone Edge"). All-caps lines are skipped: that's the Pokemon's own type label ("PSYCHIC").
     */
    fun moveTypes(lines: List<Line>, moves: Map<String, String>): List<String> {
        val byNorm = moves.mapKeys { norm(it.key) }
        return lines.filter { it.text != it.text.uppercase() }.mapNotNull {
            val n = norm(it.text)
            byNorm[n] ?: byNorm[n.drop(1)]
        }.distinct()
    }

    private fun parseAt(cp: Int, hp: Int, lines: List<Line>, dex: List<Species>, px: Pixels?, maxArcLevel: Double, known: Scan?): Scan? {
        // Stardust cost sits on the POWER UP row; take the first number there that is a real dust price
        val powerY = lines.firstOrNull { "POWER" in it.text.uppercase() }?.y
        val dust = powerY?.let { py ->
            lines.filter { abs(it.y - py) < 0.04 }
                .mapNotNull { it.text.replace(",", "").replace(".", "").trim().toIntOrNull() }
                .firstOrNull(IvMath::isDustCost)
        }

        // Species: nickname line equals a species name, or the appraisal's "This Charmander was caught on..."
        // (works for renamed Pokemon), else every member of the candy family, else what we read last time at this CP/HP
        val texts = lines.map { norm(it.text) }.toSet()
        val told = lines.firstNotNullOfOrNull { THIS.find(it.text) }?.groupValues?.get(1)?.let(::norm)
        val byName = dex.filter { norm(it.baseName) in texts || norm(it.baseName) == told }
        val candy = lines.firstNotNullOfOrNull { CANDY.find(it.text) }?.groupValues?.get(1)?.let(::norm)
        // "NIDORAN♀ CANDY" reads as NIDORAN, the family id is NIDORANFEMALE
        val byFamily = if (candy != null) dex.filter { it.family == candy || norm(it.baseName) == candy } else emptyList()
        val remembered = known?.takeIf { it.cp == cp && it.hp == hp }?.species
        val candidates = (byName + byFamily + listOfNotNull(remembered)).distinct()

        val bars = px?.let { appraisalBars(it, lines) }
        val arc = px?.let(::arcDegrees)
        val levels = IvMath.levelsForDust(dust)
        return candidates.asSequence()
            .map { sp ->
                var hits = IvMath.solve(sp, cp, hp, levels)
                // Best Buddy: CP/HP show one level above the real one, but dust is priced at the real level
                if (hits.isEmpty() && dust != null) hits = IvMath.solve(sp, cp, hp, levels.map { it + 1 }.filter { it <= 55 })
                // Each extra reading narrows the hits, but a misread must never empty them
                if (bars != null) hits = hits.filter { Triple(it.a, it.d, it.s) == bars }.ifEmpty { hits }
                var arcOff = false
                if (arc != null) {
                    // Best Buddy's arc also runs one level further
                    val onArc = nearestArcLevel(hits, arc, maxArcLevel) ?: nearestArcLevel(hits, arc, maxArcLevel + 1)
                    arcOff = onArc == null && hits.isNotEmpty()
                    hits = onArc ?: hits
                }
                Scan(sp, cp, hp, hits, arcOff)
            }
            .filter { it.hits.isNotEmpty() }
            .sortedByDescending { it.species in byName } // named match wins over a sibling form that also fits
            .firstOrNull()
    }

    /** Arc angle (degrees from the left end) of [level]. Linear in CPM, like Calcy calc/ArcCalc.java. */
    fun arcAngle(level: Double, maxLevel: Double) =
        180 * (IvMath.cpm(level) - IvMath.CPM[0]) / (IvMath.cpm(maxLevel.coerceAtMost(55.0)) - IvMath.CPM[0])

    /** Hits at the level nearest the dot, or null if none is within 2 deg (dot misread or wrong trainer level). */
    private fun nearestArcLevel(hits: List<Hit>, deg: Double, maxLevel: Double): List<Hit>? {
        val best = hits.minByOrNull { abs(arcAngle(it.level, maxLevel) - deg) } ?: return null
        if (abs(arcAngle(best.level, maxLevel) - deg) > 2.0) return null
        return hits.filter { it.level == best.level }
    }

    private val TRAINER = Regex("""^\s*(?:LEVEL|LV\.?)\s*(\d{1,2})\s*!?\s*$""", RegexOption.IGNORE_CASE)
    private val NEXT = Regex("""more at level (\d{1,2})""", RegexOption.IGNORE_CASE)

    /** Trainer level from the profile or level-up screen, or null. Only on non-Pokemon screens. */
    fun trainerLevel(lines: List<Line>): Int? {
        if (cp(lines) != null) return null
        val level = lines.firstNotNullOfOrNull { TRAINER.find(it.text)?.groupValues?.get(1)?.toIntOrNull() } // "LEVEL 41"
            ?: lines.firstNotNullOfOrNull { NEXT.find(it.text)?.groupValues?.get(1)?.toIntOrNull()?.minus(1) } // profile: "Unlock these rewards and more at level 42!"
            ?: lines.firstOrNull { it.text.trim().equals("LEVEL", ignoreCase = true) }?.let { label -> // "LEVEL" and "41" as separate lines
                lines.filter { it.text.trim().all(Char::isDigit) && abs(it.y - label.y) < 0.06 }
                    .minByOrNull { abs(it.y - label.y) }?.text?.trim()?.toIntOrNull()
            }
        return level?.takeIf { it in 1..80 }
    }

    private fun bright(c: Int) = minOf(c shr 16 and 255, c shr 8 and 255, c and 255) > 200

    /**
     * Dot position on the level arc, in degrees from the left end, or null if not found.
     * Arc geometry measured on a Pixel 11 Pro: center (W/2, 0.639W), radius 0.409W.
     * The arc line is thin and drifts a few px off this circle, so require bright both inside and outside: only the dot is.
     */
    fun arcDegrees(p: Pixels): Double? {
        val w = p.w.toDouble()
        val cx = w / 2
        val cy = 0.639 * w
        val r = 0.409 * w // arc line center; line is ~0.008W thick, dot ~0.018W across
        val off = 0.0065 * w
        fun at(t: Double, rr: Double): Int {
            val a = Math.toRadians(180 - t)
            return p(cx + rr * cos(a), cy - rr * sin(a))
        }
        val runs = mutableListOf<List<Double>>()
        var run = mutableListOf<Double>()
        var t = 1.0
        while (t <= 179.2) {
            if (t <= 179 && bright(at(t, r - off)) && bright(at(t, r + off))) run.add(t)
            else {
                if (run.size >= 3) runs.add(run) // < 0.6 deg wide: a star, not the dot
                run = mutableListOf()
            }
            t += 0.2
        }
        // Sparkle glows can sit on the arc too. Only the dot has white line before it and grey line after it.
        // The grey part is translucent, so over a pale background it's bright but tinted: test for pure white.
        fun white(c: Int): Boolean {
            val ch = intArrayOf(c shr 16 and 255, c shr 8 and 255, c and 255)
            return ch.min() > 235 && ch.max() - ch.min() < 20
        }
        fun isDot(run: List<Double>) =
            (run.first() < 4 || white(at(run.first() - 2.5, r))) && (run.last() > 176 || !white(at(run.last() + 2.5, r)))
        val best = runs.filter(::isDot).maxByOrNull { it.size } ?: return null

        // Refine: centroid of pixels whose whole (2k+1)^2 square is bright. The line (~0.008W thick) can't
        // fill that square, the dot (~0.018W) can, so the centroid is the dot's true center.
        val a = Math.toRadians(180 - best.average())
        val gx = (cx + r * cos(a)).toInt()
        val gy = (cy - r * sin(a)).toInt()
        val k = maxOf(2, (0.004 * w).toInt())
        val box = (0.02 * w).toInt()
        var sx = 0.0
        var sy = 0.0
        var n = 0
        for (y in gy - box..gy + box) for (x in gx - box..gx + box) {
            var all = true
            loop@ for (dy in -k..k) for (dx in -k..k) if (!bright(p((x + dx).toDouble(), (y + dy).toDouble()))) {
                all = false
                break@loop
            }
            if (all) {
                sx += x
                sy += y
                n++
            }
        }
        if (n == 0) return best.average()
        return 180 - Math.toDegrees(kotlin.math.atan2(cy - sy / n, sx / n - cx))
    }

    /** Exact (atk, def, sta) from the team leader's appraisal bars, or null if they aren't on screen. */
    fun appraisalBars(p: Pixels, lines: List<Line>): Triple<Int, Int, Int>? {
        fun label(name: String) = lines.firstOrNull { it.text.trim().equals(name, ignoreCase = true) }
        val a = barIv(p, label("Attack") ?: return null) ?: return null
        val d = barIv(p, label("Defense") ?: return null) ?: return null
        val s = barIv(p, label("HP") ?: return null) ?: return null
        return Triple(a, d, s)
    }

    /** The bar sits just under its label: 3 segments of 5, orange/red = filled, light grey = empty. */
    private fun barIv(p: Pixels, label: Line): Int? {
        val x0 = (label.x0 - 0.005) * p.w
        val x1 = x0 + 0.36 * p.w
        var best = 0 to 0
        var y = (label.y + label.h * 0.6) * p.h
        while (y < (label.y + label.h * 2.2) * p.h) {
            var filled = 0
            var empty = 0
            var x = x0
            while (x < x1) {
                val c = p(x, y)
                val r = c shr 16 and 255
                val g = c shr 8 and 255
                val b = c and 255
                if (r > 180 && r - b > 80) filled++
                else if (maxOf(r, g, b) - minOf(r, g, b) < 14 && r in 195..240) empty++
                x += 1.0
            }
            if (filled + empty > best.first + best.second) best = filled to empty
            y += 1.0
        }
        val total = best.first + best.second
        if (total < 0.25 * p.w) return null
        return (15.0 * best.first / total).roundToInt()
    }
}
