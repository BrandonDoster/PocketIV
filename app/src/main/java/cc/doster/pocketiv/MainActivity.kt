package cc.doster.pocketiv

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

object Prefs {
    const val DEFAULT_SCHEME = "\$IVPerfect\$\$Name3\$\$IV%Range\$"
    private fun p(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun scheme(ctx: Context) = p(ctx).getString("scheme", DEFAULT_SCHEME)!!
    fun interval(ctx: Context) = p(ctx).getLong("interval", 1000L).coerceAtLeast(250L)
    /** Starts at 1 (deliberately wrong): the arc then fits nothing and the overlay asks for the profile screen,
     * which sets it. Level-up screens update it too. */
    fun trainerLevel(ctx: Context) = p(ctx).getLong("trainer", 1L).toInt().coerceIn(1, 80)

    /** Arc right end. trainer + 10.5 fits two Pixel 11 Pro captures at trainer 41 (levels 26 and 35).
     * ponytail: below trainer 40 (cap 40) and the 55 cap are unverified; recalibrate with screenshots if readings drift. */
    fun maxArcLevel(ctx: Context): Double {
        val t = trainerLevel(ctx)
        return minOf(t + 10.5, if (t < 40) 40.5 else 55.0) // 55 = end of the CPM table
    }
    fun set(ctx: Context, key: String, v: String) = p(ctx).edit().putString(key, v).apply()
    fun set(ctx: Context, key: String, v: Long) = p(ctx).edit().putLong(key, v).apply()
}

private fun EditText.onChange(f: (String) -> Unit) = addTextChangedListener(object : TextWatcher {
    override fun afterTextChanged(s: Editable) = f(s.toString())
    override fun beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
    override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
})

class MainActivity : Activity() {
    private lateinit var startBtn: Button
    private lateinit var trainer: EditText
    private lateinit var scheme: EditText

    /** Preview cases for [sp]: perfect, one exact combo, unsure (dust only, no arc/appraisal), weak. */
    private fun samples(sp: Species) = listOf(
        "Perfect" to listOf(Hit(40.0, 15, 15, 15)),
        "Exact" to listOf(Hit(25.0, 13, 8, 5)),
        "Unsure" to IvMath.solve(sp, IvMath.cp(sp, 13, 8, 5, 25.0), IvMath.hp(sp, 5, 25.0), IvMath.levelsForDust(4000)),
        "Weak" to listOf(Hit(20.0, 2, 4, 0)),
    )

    private val presets = listOf(
        "Calcy style" to "\$IVPerfect\$\$Name3\$\$IV%Range\$",
        "IV + stats" to "\$IV%Avg\$\$Combs\$",
        "IV + level" to "\$IV%Range\$ \$Lvl\$",
        "IV + name" to "\$IV%Avg\$ \$NameFill\$",
        "IV + moves + stats" to "\$Perf/IV%\$\$MoveTypes\$\$Combs\$",
    )

    /** Species with how many you've scanned (dupes) and the best IV%; tap one for each Pokemon. */
    private fun showHistory(singlesFirst: Boolean = false) {
        val all = History.get(this).all
        if (all.isEmpty()) {
            Toast.makeText(this, "Nothing scanned yet", Toast.LENGTH_SHORT).show()
            return
        }
        val groups = all.groupBy { it.species }.entries.sortedWith(
            if (singlesFirst) compareBy({ it.value.size }, { it.key })
            else compareByDescending<Map.Entry<String, List<Record>>> { it.value.size }.thenBy { it.key },
        )
        val items = groups.map { (sp, rs) -> "$sp  ×${rs.size}   best ${rs.maxOf { it.ivHi }}%" }
        android.app.AlertDialog.Builder(this)
            .setTitle("Scanned: ${all.size} Pokémon, ${groups.size} species")
            .setItems(items.toTypedArray()) { _, i -> showSpecies(groups[i].key, groups[i].value) }
            .setNeutralButton(if (singlesFirst) "Most dupes first" else "Singles first") { _, _ -> showHistory(!singlesFirst) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showSpecies(species: String, rs: List<Record>) {
        val items = rs.sortedByDescending { it.ivHi }.map {
            val moves = it.moves.split(',').filter(String::isNotEmpty).joinToString("") { t -> IvMath.TYPE_SYMBOL[t].orEmpty() }
            "${it.iv}   CP ${it.cp}   L${it.level}" + (if (it.ivs.isNotEmpty()) "   ${it.ivs}" else "") +
                (if (moves.isNotEmpty()) "   $moves" else "")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("$species ×${rs.size}")
            .setItems(items.toTypedArray(), null)
            .setNegativeButton("Back") { _, _ -> showHistory() }
            .show()
    }

    /** Save as CSV wherever the user picks (Downloads, Drive...) via the system file picker. */
    private fun exportHistory() {
        if (History.get(this).all.isEmpty()) {
            Toast.makeText(this, "Nothing scanned yet", Toast.LENGTH_SHORT).show()
            return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/csv").putExtra(Intent.EXTRA_TITLE, "pocketiv-history.csv"), EXPORT)
    }

    /** Every tag with a live example; tap a tag to insert it at the cursor, tap a preset to replace the scheme. */
    private fun showRenameHelp() {
        val ex = listOf(Hit(25.0, 13, 8, 5))
        val tags = IvMath.TAGS.entries.toList()
        val items = listOf("Text outside \$…\$ is kept as typed. GO cuts names at 12 characters.") +
            tags.map { (tag, what) ->
                "\$$tag\$\n$what\ne.g. ${IvMath.rename("\$$tag\$", "Mewtwo", ex, listOf("psychic", "fire")).ifEmpty { "(nothing)" }}"
            } +
            presets.map { (name, s) -> "Preset: $name\n$s" }
        android.app.AlertDialog.Builder(this)
            .setTitle("Rename tags")
            .setItems(items.toTypedArray()) { _, i ->
                when {
                    i == 0 -> {}
                    i <= tags.size -> {
                        val tag = "\$${tags[i - 1].key}\$"
                        val at = scheme.selectionStart.coerceAtLeast(0)
                        scheme.text.insert(at, tag)
                    }
                    else -> scheme.setText(presets[i - 1 - tags.size].second)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dex = Species.load(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        fun label(t: String, size: Float = 14f) = TextView(this).apply {
            text = t
            textSize = size
            setPadding(0, 32, 0, 8)
        }.also(col::addView)

        label("PocketIV", 24f)

        label("Trainer level (read automatically when you open your GO profile)")
        trainer = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1–80"
            setText(Prefs.trainerLevel(this@MainActivity).toString())
            onChange { s -> s.toLongOrNull()?.takeIf { it in 1..80 }?.let { Prefs.set(this@MainActivity, "trainer", it) } }
        }
        col.addView(trainer)

        label("Rename scheme")
        scheme = EditText(this).apply {
            setText(Prefs.scheme(this@MainActivity))
            isSingleLine = true
        }
        col.addView(scheme)
        col.addView(LinearLayout(this).apply {
            addView(Button(this@MainActivity).apply {
                text = "Tags & presets"
                setOnClickListener { showRenameHelp() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Reset"
                setOnClickListener { scheme.setText(Prefs.DEFAULT_SCHEME) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })

        label("Preview — type a Pokémon")
        val previewAs = EditText(this).apply {
            setText("Mewtwo")
            isSingleLine = true
        }
        col.addView(previewAs)
        val preview = TextView(this).apply {
            textSize = 16f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(android.graphics.Color.WHITE)
            setPadding(32, 24, 32, 24)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 24f
                setColor(0xFF2A2A2A.toInt())
            }
        }
        col.addView(preview)
        label("Red = past GO's 12-character limit, cut off when you paste.", 12f)

        fun updatePreview() {
            val typed = previewAs.text.toString().trim()
            val sp = dex.firstOrNull { it.name.equals(typed, true) } ?: dex.firstOrNull { it.baseName.equals(typed, true) }
            if (sp == null) {
                preview.text = "No Pokémon called \"$typed\""
                return
            }
            val out = android.text.SpannableStringBuilder()
            for ((what, hits) in samples(sp)) {
                val name = IvMath.rename(scheme.text.toString(), sp.baseName, hits, sp.types) // own types stand in for moves
                out.append(what.padEnd(9))
                val start = out.length
                out.append(name)
                if (name.length > 12) {
                    out.setSpan(android.text.style.ForegroundColorSpan(0xFFFF5252.toInt()), start + 12, out.length, 0)
                    out.setSpan(android.text.style.StrikethroughSpan(), start + 12, out.length, 0)
                }
                out.append("\n")
            }
            preview.text = out.trimEnd()
        }
        scheme.onChange {
            Prefs.set(this, "scheme", it)
            updatePreview()
        }
        previewAs.onChange { updatePreview() }
        updatePreview()

        label("Scan interval (ms) — higher saves battery")
        col.addView(EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Prefs.interval(this@MainActivity).toString())
            onChange { s -> s.toLongOrNull()?.let { Prefs.set(this@MainActivity, "interval", it) } }
        })

        label("")
        startBtn = Button(this).apply { setOnClickListener { toggleOverlay() } }
        col.addView(startBtn)
        col.addView(Button(this).apply {
            text = "Launch Pokémon GO"
            setOnClickListener {
                val go = packageManager.getLaunchIntentForPackage(GO_PACKAGE)
                if (go == null) Toast.makeText(this@MainActivity, "Pokémon GO not installed", Toast.LENGTH_SHORT).show()
                else startActivity(go)
            }
        })
        label("Floating button: tap = pause/resume reading, hold = copy rename, drag = move.")

        label("Scanned Pokémon")
        col.addView(LinearLayout(this).apply {
            addView(Button(this@MainActivity).apply {
                text = "History"
                setOnClickListener { showHistory() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Export CSV"
                setOnClickListener { exportHistory() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Clear"
                setOnClickListener {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setMessage("Delete all ${History.get(this@MainActivity).all.size} scanned Pokémon?")
                        .setPositiveButton("Delete") { _, _ -> History.get(this@MainActivity).clear() }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })

        setContentView(ScrollView(this).apply {
            addView(col)
            setOnApplyWindowInsetsListener { v, insets -> // edge-to-edge is enforced on SDK 35+
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != 0)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
    }

    override fun onResume() {
        super.onResume()
        trainer.setText(Prefs.trainerLevel(this).toString()) // the overlay may have read a new one
        startBtn.text = if (OverlayService.running) "Stop screen reading" else "Start screen reading"
    }

    private fun toggleOverlay() {
        if (OverlayService.running) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
            startBtn.text = "Start screen reading"
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow PocketIV to display over other apps, then come back", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 1)
    }

    @Deprecated("Activity result API needs androidx; this app has no androidx")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == EXPORT) {
            data.data?.let { uri -> contentResolver.openOutputStream(uri)?.use { it.write(History.get(this).csv().toByteArray()) } }
            Toast.makeText(this, "Exported ${History.get(this).all.size} Pokémon", Toast.LENGTH_SHORT).show()
            return
        }
        if (requestCode != 1) return
        startForegroundService(Intent(this, OverlayService::class.java)
            .putExtra(OverlayService.EXTRA_CODE, resultCode)
            .putExtra(OverlayService.EXTRA_DATA, data))
        startBtn.text = "Stop screen reading"
    }

    companion object {
        const val GO_PACKAGE = "com.nianticlabs.pokemongo"
        private const val EXPORT = 3
    }
}
