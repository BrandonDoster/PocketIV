package cc.doster.pocketiv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IvMathTest {
    private val dex = Species.parse(File("src/main/assets/species.tsv").readText())
    private val mewtwo = dex.first { it.name == "Mewtwo" }

    @Test
    fun cpAndHpMatchGame() {
        assertEquals(4178, IvMath.cp(mewtwo, 15, 15, 15, 40.0))
        assertEquals(180, IvMath.hp(mewtwo, 15, 40.0))
    }

    @Test
    fun renameMatchesIvPy() {
        val one = IvMath.solve(mewtwo, 4178, 180, listOf(40.0))
        assertEquals(listOf(Hit(40.0, 15, 15, 15)), one)
        assertEquals("!Mew100", IvMath.rename("\$IVPerfect\$\$Name3\$\$IV%Range\$", "Mewtwo", one))
        assertEquals("⑮⑮⑮ 40", IvMath.rename("\$Combs\$ \$Lvl\$", "Mewtwo", one))
        assertEquals("⑮⑮⑮100Crabom", IvMath.rename("\$Combs\$\$IV%Range\$\$NameFill\$", "Crabominable", one))
        val all = IvMath.solve(mewtwo, 4178, 180)
        assertEquals(19, all.size) // same as iv.py
        assertEquals("18-100❾❽❾ 44½?", IvMath.rename("\$IV%Range\$\$Combs\$ \$Lvl\$", "Mewtwo", all))
    }

    @Test
    fun dustNarrowsLevels() {
        assertEquals(listOf(39.0, 39.5, 40.0, 40.5), IvMath.levelsForDust(10000).filter { it >= 39 })
        assertTrue(IvMath.isDustCost(12000)) // 10000 * 1.2 shadow
    }

    @Test
    fun parsesDetailScreen() {
        val lines = listOf(
            Line("CP4178", 0.08), Line("!Mew100", 0.45), Line("180 / 180 HP", 0.5),
            Line("MEWTWO CANDY", 0.62), Line("POWER UP", 0.85), Line("10,000", 0.85), Line("100", 0.85),
        )
        val scan = ScreenParser.parse(lines, dex)!!
        assertEquals("Mewtwo", scan.species.name) // renamed nickname, found via candy family
        assertTrue(Hit(40.0, 15, 15, 15) in scan.hits)
        assertTrue(scan.hits.all { it.level in 39.0..40.5 })
        assertNull(ScreenParser.parse(listOf(Line("Settings", 0.1)), dex))
    }

    // Real Pixel 11 Pro capture: Helioptile CP632 89HP, level 26, appraisal 13/8/5, trainer 41.
    // OCR lines typed in from the screenshot (ML Kit doesn't run on the JVM).
    private val shot = javax.imageio.ImageIO.read(File("src/test/resources/helioptile_appraisal.png"))
    private val px = Pixels(shot.width, shot.height) { x, y -> shot.getRGB(x, y) }
    private val helioLines = listOf(Line("CP632", 0.07), Line("Helioptile", 0.411), Line("89 / 89 HP", 0.458))
    private val barLines = listOf(
        Line("Attack", 0.7385, 0.119, 0.015), Line("Defense", 0.7795, 0.117, 0.015), Line("HP", 0.8205, 0.118, 0.015),
    )

    @Test
    fun readsAppraisalBars() {
        assertEquals(Triple(13, 8, 5), ScreenParser.appraisalBars(px, barLines))
        val scan = ScreenParser.parse(helioLines + barLines, dex, px, 51.5)!!
        assertEquals(listOf(Hit(26.0, 13, 8, 5)), scan.hits)
    }

    @Test
    fun readsLevelArc() {
        val deg = ScreenParser.arcDegrees(px)!!
        assertEquals(ScreenParser.arcAngle(26.0, 51.5), deg, 0.75) // half-levels are ~1.5 deg apart here
        val scan = ScreenParser.parse(helioLines, dex, px, 51.5)!! // no bars: arc alone pins the level
        assertTrue(scan.hits.isNotEmpty() && scan.hits.all { it.level == 26.0 })
        assertTrue(Hit(26.0, 13, 8, 5) in scan.hits)
    }

    @Test
    fun durantRealOcr() { // level 35, appraisal 12/7/7; OCR text copied from device log, incl. its "12O" misread
        val img = javax.imageio.ImageIO.read(File("src/test/resources/durant_appraisal.png"))
        val dpx = Pixels(img.width, img.height) { x, y -> img.getRGB(x, y) }
        val lines = listOf(Line("CP2331", 0.07), Line("Durant", 0.411), Line("120/ 12O HP", 0.458))
        val arcOnly = ScreenParser.parse(lines, dex, dpx, 51.5)!!
        assertTrue(arcOnly.hits.isNotEmpty() && arcOnly.hits.all { it.level == 35.0 })
        val half = Pixels(img.width / 2, img.height / 2) { x, y -> img.getRGB(x * 2, y * 2) }
        assertTrue(ScreenParser.parse(lines, dex, half, 51.5)!!.hits.all { it.level == 35.0 })
        assertEquals(listOf(Hit(35.0, 12, 7, 7)), ScreenParser.parse(lines + barLines, dex, dpx, 51.5)!!.hits)
    }

    @Test
    fun flabebeAccentsAndSparkles() { // level 11, appraisal 14/4/14; glowing sparkles sit on the arc
        val img = javax.imageio.ImageIO.read(File("src/test/resources/flabebe_appraisal.png"))
        val fpx = Pixels(img.width, img.height) { x, y -> img.getRGB(x, y) }
        assertEquals(ScreenParser.arcAngle(11.0, 51.5), ScreenParser.arcDegrees(fpx)!!, 1.0)
        val lines = listOf(Line("CP316", 0.07), Line("Flabébé", 0.411), Line("62 /62 HP", 0.458))
        assertEquals(listOf(Hit(11.0, 14, 4, 14)), ScreenParser.parse(lines + barLines, dex, fpx, 51.5)!!.hits)
        assertTrue(ScreenParser.parse(lines, dex, fpx, 51.5)!!.hits.all { it.level == 11.0 })
    }

    @Test
    fun memoryOutvotesMisreads() {
        val durant = dex.first { it.name == "Durant" }
        val arc35 = IvMath.solve(durant, 2331, 120, listOf(35.0)) // 3 combos
        val wing = IvMath.solve(durant, 2331, 120, listOf(33.0))  // dot hidden: wrong level
        val m = ScanMemory()
        val s = Scan(durant, 2331, 120, arc35)
        assertEquals(arc35, m.add(s).hits)
        m.add(s)
        assertEquals(arc35, m.add(s.copy(hits = wing)).hits)                // misread outvoted
        val exact = listOf(Hit(35.0, 12, 7, 7))
        assertEquals(exact, m.add(s.copy(hits = exact)).hits)              // appraisal narrows
        assertEquals(exact, m.add(s).hits)                                  // and survives closing it
    }

    @Test
    fun renamedPokemonFromAppraisalText() { // OCR lines copied from the device log; nickname is "Charma19 203"
        val lines = listOf(Line("Charma19 203", 0.41), Line("CP398", 0.07), Line("64/64 HP", 0.458),
            Line("CHARMANDS", 0.6), Line("This Charmander was caught on", 0.9))
        val scan = ScreenParser.parse(lines, dex)!!
        assertEquals("Charmander", scan.species.name)
        // a later frame with no readable name reuses what we found at this CP/HP
        val bare = listOf(Line("CP398", 0.07), Line("64/64 HP", 0.458))
        assertNull(ScreenParser.parse(bare, dex))
        assertEquals("Charmander", ScreenParser.parse(bare, dex, known = scan)!!.species.name)
        val mangled = bare + Line("This Chařmanderwas caughton 7/6/2016", 0.9) // real OCR output
        assertEquals("Charmander", ScreenParser.parse(mangled, dex)!!.species.name)
    }

    @Test
    fun nidoranWithoutGenderSymbol() { // real OCR: "Nidoran", CP238 70HP, appraisal 10/12/13
        val lines = listOf(Line("CP238", 0.07), Line("Nidoran", 0.411), Line("70/70 HP", 0.458),
            Line("This Nidoran was caught on 10/6/2026", 0.9))
        val scan = ScreenParser.parse(lines, dex)!!
        assertTrue(scan.species.name.startsWith("Nidoran"))
        assertTrue(scan.hits.any { it.a == 10 && it.d == 12 && it.s == 13 })
    }

    @Test
    fun chikoritaAccentedCp() { // real OCR "CPÓ63"; bars 2/8/15 decide between two level-29 combos
        val img = javax.imageio.ImageIO.read(File("src/test/resources/chikorita_shiny.png"))
        val cpx = Pixels(img.width, img.height) { x, y -> img.getRGB(x, y) }
        val lines = listOf(Line("CPÓ63", 0.07), Line("102 /102 HP", 0.458),
            Line("This Chikorita was caught on 12/1/2018", 0.9)) + barLines
        assertEquals(listOf(Hit(29.0, 2, 8, 15)), ScreenParser.parse(lines, dex, cpx, 51.5)!!.hits)
    }

    private val moves = Moves.parse(File("src/main/assets/moves.tsv").readText())

    @Test
    fun moveTypesPreset() {
        // real Durant OCR lines: "G Bug Bite" (icon read as G), type label "BUG / STEEL"; "PSYCHIC" label must not count
        val lines = listOf(Line("G Bug Bite", 0.7), Line("Stone Edge", 0.75), Line("BUG / STEEL", 0.5), Line("PSYCHIC", 0.5))
        assertEquals(listOf("bug", "rock"), ScreenParser.moveTypes(lines, moves))
        val scheme = "\$Perf/IV%\$\$MoveTypes\$\$Combs\$"
        assertEquals("58᯼☗⑫⑦⑦", IvMath.rename(scheme, "Durant", listOf(Hit(35.0, 12, 7, 7)), listOf("bug", "rock")))
        assertEquals("!㊋⑮⑮⑮", IvMath.rename(scheme, "Arcanine", listOf(Hit(40.0, 15, 15, 15)), listOf("fire")))
        val two = listOf(Hit(35.0, 12, 7, 7), Hit(35.0, 8, 14, 7))
        assertTrue(IvMath.rename(scheme, "Durant", two).contains("~")) // averaged marker, as in existing Calcy names
        // moves seen on the detail screen are kept once the appraisal covers them
        val durant = dex.first { it.name == "Durant" }
        val m = ScanMemory()
        m.add(Scan(durant, 2331, 120, two, moveTypes = listOf("bug", "rock")))
        assertEquals(listOf("bug", "rock"), m.add(Scan(durant, 2331, 120, two.take(1))).moveTypes)
    }

    @Test
    fun historyRecords() {
        val durant = dex.first { it.name == "Durant" }
        val r = Record.from(Scan(durant, 2331, 120, listOf(Hit(35.0, 12, 7, 7)), moveTypes = listOf("bug", "rock")), now = 1)
        assertEquals(Record("Durant", 2331, 120, 58, 58, "12/7/7", "35", "bug,rock", 1), r)
        assertEquals(r, Record.parse(r.tsv()))
        val unsure = Record.from(Scan(durant, 2331, 120, IvMath.solve(durant, 2331, 120)))
        assertEquals("", unsure.ivs)
        assertTrue("-" in unsure.level)
        // a power-up links to the earlier reading so history can drop the old row
        val m = ScanMemory()
        m.add(Scan(durant, 2331, 120, listOf(Hit(35.0, 12, 7, 7))), now = 0)
        val cp = IvMath.cp(durant, 12, 7, 7, 35.5)
        val hp = IvMath.hp(durant, 7, 35.5)
        m.add(Scan(durant, cp, hp, IvMath.solve(durant, cp, hp, listOf(35.5))), now = 1_000)
        assertEquals("Durant|2331|120", m.linkedFrom)
        m.add(Scan(durant, cp, hp, IvMath.solve(durant, cp, hp, listOf(35.5))), now = 2_000)
        assertNull(m.linkedFrom)
    }

    @Test
    fun bestBuddyShowsOneLevelUp() { // real level 35 (dust 8,000), CP/HP shown at 36
        val durant = dex.first { it.name == "Durant" }
        val cp = IvMath.cp(durant, 12, 7, 7, 36.0)
        val hp = IvMath.hp(durant, 7, 36.0)
        val lines = listOf(Line("CP$cp", 0.07), Line("Durant", 0.41), Line("$hp/$hp HP", 0.458),
            Line("POWER UP", 0.85), Line("8,000", 0.85))
        assertTrue(Hit(36.0, 12, 7, 7) in ScreenParser.parse(lines, dex)!!.hits)
    }

    @Test
    fun powerUpKeepsIvs() {
        val durant = dex.first { it.name == "Durant" }
        val m = ScanMemory()
        m.add(Scan(durant, 2331, 120, listOf(Hit(35.0, 12, 7, 7))), now = 0)
        val cp = IvMath.cp(durant, 12, 7, 7, 35.5)
        val hp = IvMath.hp(durant, 7, 35.5)
        val after = IvMath.solve(durant, cp, hp, listOf(35.5)) // arc alone: several combos
        assertTrue(after.size > 1)
        assertEquals(listOf(Hit(35.5, 12, 7, 7)), m.add(Scan(durant, cp, hp, after), now = 5_000).hits)
        // too late to be the same power-up session: no carry-over
        assertEquals(after.size, ScanMemory().add(Scan(durant, cp, hp, after)).hits.size)
    }

    @Test
    fun trainerLevelAndArcOff() {
        assertEquals(41, ScreenParser.trainerLevel(listOf(Line("Trainer123", 0.2), Line("LEVEL 41", 0.3))))
        assertNull(ScreenParser.trainerLevel(listOf(Line("CP398", 0.07), Line("LEVEL 41", 0.3))))
        // real profile OCR: "LEVEL" and "41" split, plus the next-level rewards line
        assertEquals(41, ScreenParser.trainerLevel(listOf(Line("41", 0.31), Line("Trainer123", 0.25), Line("LEVEL", 0.33),
            Line("Unlock these rewards and more at level 42!", 0.55))))
        assertEquals(41, ScreenParser.trainerLevel(listOf(Line("41", 0.31), Line("LEVEL", 0.33), Line("20", 0.5))))
        val lines = listOf(Line("CP2331", 0.07), Line("Durant", 0.411), Line("120/ 12O HP", 0.458))
        val img = javax.imageio.ImageIO.read(File("src/test/resources/durant_appraisal.png"))
        val dpx = Pixels(img.width, img.height) { x, y -> img.getRGB(x, y) }
        assertTrue(ScreenParser.parse(lines, dex, dpx, maxArcLevel = 11.5)!!.arcOff) // default trainer level 1
        assertTrue(!ScreenParser.parse(lines, dex, dpx, maxArcLevel = 51.5)!!.arcOff)
    }

    @Test
    fun worksAtHalfResolution() { // the service captures at W/2 x H/2
        val half = Pixels(shot.width / 2, shot.height / 2) { x, y -> shot.getRGB(x * 2, y * 2) }
        assertEquals(Triple(13, 8, 5), ScreenParser.appraisalBars(half, barLines))
        assertEquals(ScreenParser.arcAngle(26.0, 51.5), ScreenParser.arcDegrees(half)!!, 0.75)
    }
}
