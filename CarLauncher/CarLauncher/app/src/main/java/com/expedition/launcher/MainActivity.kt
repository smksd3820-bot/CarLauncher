package com.expedition.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.time.chrono.HijrahDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/* ------------------------------------------------------------------
 *  حساب مواقيت الصلاة (طريقة أم القرى) - يعمل بدون إنترنت
 * ------------------------------------------------------------------ */
object PrayerCalc {
    private fun rad(d: Double) = Math.toRadians(d)
    private fun dsin(d: Double) = sin(rad(d))
    private fun dcos(d: Double) = cos(rad(d))
    private fun dtan(d: Double) = tan(rad(d))
    private fun darcsin(x: Double) = Math.toDegrees(asin(x))
    private fun darccos(x: Double) = Math.toDegrees(acos(x.coerceIn(-1.0, 1.0)))
    private fun darctan2(y: Double, x: Double) = Math.toDegrees(atan2(y, x))
    private fun darccot(x: Double) = Math.toDegrees(atan(1.0 / x))

    private fun fix(a: Double, b: Double): Double {
        val r = a - b * floor(a / b)
        return if (r < 0) r + b else r
    }

    private fun julian(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    /** يرجع الساعات بالترتيب: الفجر، الشروق، الظهر، العصر، المغرب، العشاء */
    fun compute(year: Int, month: Int, day: Int, lat: Double, lng: Double, tz: Double): DoubleArray {
        val jd = julian(year, month, day) - lng / 360.0
        val d = jd + 0.5 - 2451545.0
        val g = fix(357.529 + 0.98560028 * d, 360.0)
        val q = fix(280.459 + 0.98564736 * d, 360.0)
        val l = fix(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g), 360.0)
        val e = 23.439 - 0.00000036 * d
        val ra = fix(darctan2(dcos(e) * dsin(l), dcos(l)) / 15.0, 24.0)
        val eqt = q / 15.0 - ra
        val decl = darcsin(dsin(e) * dsin(l))
        val noon = fix(12.0 - eqt, 24.0)

        fun at(angle: Double, ccw: Boolean): Double {
            val t = darccos((-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat))) / 15.0
            return noon + if (ccw) -t else t
        }

        val asrAngle = -darccot(1.0 + dtan(abs(lat - decl)))
        val shift = tz - lng / 15.0
        val maghrib = at(0.833, false)
        val raw = doubleArrayOf(
            at(18.5, true),      // الفجر
            at(0.833, true),     // الشروق
            noon,                // الظهر
            at(asrAngle, false), // العصر
            maghrib,             // المغرب
            maghrib + 1.5        // العشاء (90 دقيقة بعد المغرب - أم القرى)
        )
        return DoubleArray(6) { fix(raw[it] + shift, 24.0) }
    }
}

class City(val name: String, val lat: Double, val lng: Double)
class Palette(val bg1: String, val bg2: String, val accent: String)

/* ------------------------------------------------------------------
 *  اللانشر
 * ------------------------------------------------------------------ */
class MainActivity : Activity() {

    companion object {
        val CITIES = listOf(
            City("الرياض", 24.7136, 46.6753),
            City("جدة", 21.4858, 39.1925),
            City("مكة المكرمة", 21.3891, 39.8579),
            City("المدينة المنورة", 24.5247, 39.5692),
            City("الدمام", 26.4207, 50.0888),
            City("الطائف", 21.2703, 40.4158),
            City("أبها", 18.2164, 42.5053),
            City("تبوك", 28.3835, 36.5662),
            City("بريدة", 26.3260, 43.9750),
            City("حائل", 27.5114, 41.7208),
            City("دبي", 25.2048, 55.2708),
            City("أبوظبي", 24.4539, 54.3773),
            City("الكويت", 29.3759, 47.9774),
            City("الدوحة", 25.2854, 51.5310),
            City("المنامة", 26.2285, 50.5860),
            City("مسقط", 23.5880, 58.3829),
            City("عمّان", 31.9454, 35.9284),
            City("القاهرة", 30.0444, 31.2357)
        )

        // أزرق ليلي + ذهبي / أسود + زمردي / داكن + برتقالي
        val PALETTES = listOf(
            Palette("#0B1D3A", "#040B18", "#D4AF37"),
            Palette("#0D0D0D", "#000000", "#1ABC9C"),
            Palette("#1A1A1A", "#0A0A0A", "#FF8C42")
        )

        val PRAYER_NAMES = arrayOf("الفجر", "الشروق", "الظهر", "العصر", "المغرب", "العشاء")
        val NEXT_IDX = intArrayOf(0, 2, 3, 4, 5)
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var pal: Palette
    private var accent = Color.WHITE

    private lateinit var tvTime: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvHijri: TextView
    private lateinit var tvNext: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvCity: TextView
    private val rows = ArrayList<Pair<TextView, TextView>>()

    private var mins = IntArray(6)
    private var timesDay = -1

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("car", MODE_PRIVATE)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        timesDay = -1
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onBackPressed() {
        // اللانشر ما يقفل
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        timesDay = -1
        refresh()
    }

    /* ---------------------------- أدوات ---------------------------- */

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    private fun rounded(color: Int, r: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        return g
    }

    private fun label(size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(null, Typeface.BOLD)
        return t
    }

    private fun ripple(v: View) {
        val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        v.background = ta.getDrawable(0)
        ta.recycle()
    }

    private fun open(i: Intent) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
        }
    }

    private fun tile(label: String, icon: Drawable?, size: Int, onClick: () -> Unit): View {
        val v = LinearLayout(this)
        v.orientation = LinearLayout.VERTICAL
        v.gravity = Gravity.CENTER
        v.isClickable = true
        ripple(v)
        v.setOnClickListener { onClick() }
        val iv = ImageView(this)
        iv.setImageDrawable(icon)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        v.addView(iv, lp(dp(size), dp(size)))
        val tv = label(14f, Color.WHITE)
        tv.text = label
        tv.gravity = Gravity.CENTER
        tv.maxLines = 1
        tv.ellipsize = TextUtils.TruncateAt.END
        val p = lp(WRAP, WRAP)
        p.topMargin = dp(4)
        v.addView(tv, p)
        return v
    }

    private fun fmt(totalMin: Int): String {
        val h = totalMin / 60
        val m = totalMin % 60
        val h12 = if (h % 12 == 0) 12 else h % 12
        return String.format(Locale.US, "%d:%02d %s", h12, m, if (h < 12) "ص" else "م")
    }

    /* ----------------------------- الواجهة ----------------------------- */

    private fun buildUi() {
        pal = PALETTES[prefs.getInt("theme", 0) % PALETTES.size]
        accent = Color.parseColor(pal.accent)
        val cardColor = Color.parseColor("#26FFFFFF")
        rows.clear()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor(pal.bg1), Color.parseColor(pal.bg2))
        )
        root.setPadding(dp(24), dp(20), dp(24), dp(16))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        root.addView(top, lp(MATCH, 0, 1f))

        // ---- بطاقة المواقيت (يمين) ----
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = rounded(cardColor, 28)
        card.setPadding(dp(28), dp(16), dp(28), dp(16))
        card.setOnLongClickListener {
            pickCity()
            true
        }
        tvCity = label(20f, accent, true)
        tvCity.gravity = Gravity.CENTER
        card.addView(tvCity, lp(MATCH, WRAP))
        for (i in 0 until 6) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val n = label(28f, Color.WHITE)
            n.text = PRAYER_NAMES[i]
            val t = label(28f, Color.WHITE, true)
            row.addView(n, lp(0, WRAP, 1f))
            row.addView(t, lp(WRAP, WRAP))
            card.addView(row, lp(MATCH, 0, 1f))
            rows.add(Pair(n, t))
        }
        val cp = lp(0, MATCH, 1f)
        cp.marginEnd = dp(16)
        top.addView(card, cp)

        // ---- الساعة والتاريخ (يسار) ----
        val info = LinearLayout(this)
        info.orientation = LinearLayout.VERTICAL
        info.gravity = Gravity.CENTER
        info.setOnLongClickListener {
            prefs.edit().putInt("theme", (prefs.getInt("theme", 0) + 1) % PALETTES.size).apply()
            buildUi()
            refresh()
            true
        }
        tvTime = label(96f, Color.WHITE, true)
        tvDate = label(24f, Color.WHITE)
        tvHijri = label(24f, accent)
        tvNext = label(26f, Color.WHITE)
        tvCount = label(52f, accent, true)
        for (t in listOf(tvTime, tvDate, tvHijri, tvNext, tvCount)) {
            t.gravity = Gravity.CENTER
            info.addView(t, lp(MATCH, WRAP))
        }
        tvNext.setPadding(0, dp(24), 0, 0)
        top.addView(info, lp(0, MATCH, 1f))

        // ---- شريط التطبيقات (أسفل) ----
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER
        bar.background = rounded(cardColor, 28)
        bar.setPadding(dp(8), dp(8), dp(8), dp(8))

        val favs = ArrayList<Pair<String, Intent>>()
        fun add(name: String, pkg: String) {
            packageManager.getLaunchIntentForPackage(pkg)?.let { favs.add(Pair(name, it)) }
        }
        // عدّل أو أضف التطبيقات من هنا (الاسم + اسم الحزمة)
        add("الخرائط", "com.google.android.apps.maps")
        add("Waze", "com.waze")
        add("سبوتيفاي", "com.spotify.music")
        add("أنغامي", "com.anghami")
        add("يوتيوب ميوزك", "com.google.android.apps.youtube.music")
        add("يوتيوب", "com.google.android.youtube")
        add("القرآن", "com.quran.labs.androidquran")
        favs.add(Pair("الهاتف", Intent(Intent.ACTION_DIAL)))
        favs.add(Pair("الإعدادات", Intent(Settings.ACTION_SETTINGS)))

        for ((name, intent) in favs) {
            val icon = intent.resolveActivityInfo(packageManager, 0)?.loadIcon(packageManager)
            bar.addView(tile(name, icon, 56) { open(intent) }, lp(0, MATCH, 1f))
        }
        val dot = GradientDrawable()
        dot.shape = GradientDrawable.OVAL
        dot.setColor(accent)
        dot.setSize(dp(56), dp(56))
        bar.addView(tile("كل التطبيقات", dot, 56) { showAllApps() }, lp(0, MATCH, 1f))

        val bp = lp(MATCH, dp(120))
        bp.topMargin = dp(14)
        root.addView(bar, bp)

        setContentView(root)
        timesDay = -1
    }

    /* ----------------------------- كل التطبيقات ----------------------------- */

    private fun showAllApps() {
        val pm = packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        val dlg = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.layoutDirection = View.LAYOUT_DIRECTION_RTL
        box.setBackgroundColor(Color.parseColor(pal.bg2))
        box.setPadding(dp(24), dp(16), dp(24), dp(16))

        val title = label(26f, accent, true)
        title.text = "كل التطبيقات"
        title.setPadding(0, 0, 0, dp(12))
        box.addView(title)

        val gv = GridView(this)
        gv.numColumns = 6
        gv.verticalSpacing = dp(16)
        gv.adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(p: Int): Any = apps[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val ri = apps[p]
                return tile(ri.loadLabel(pm).toString(), ri.loadIcon(pm), 64) {
                    val i = Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                    dlg.dismiss()
                    open(i)
                }
            }
        }
        box.addView(gv, lp(MATCH, 0, 1f))
        dlg.setContentView(box)
        dlg.show()
    }

    /* ----------------------------- المدينة والموقع ----------------------------- */

    private fun pickCity() {
        val names = arrayOf("تلقائي (GPS)") + CITIES.map { it.name }
        AlertDialog.Builder(this)
            .setTitle("اختر المدينة")
            .setItems(names) { _, i ->
                if (i == 0) {
                    prefs.edit().putInt("city", -1).apply()
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
                } else {
                    prefs.edit().putInt("city", i - 1).apply()
                }
                timesDay = -1
                refresh()
            }
            .show()
    }

    private fun currentLatLng(): Pair<Double, Double> {
        val c = prefs.getInt("city", 0)
        if (c >= 0 && c < CITIES.size) return Pair(CITIES[c].lat, CITIES[c].lng)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val lm = getSystemService(LocationManager::class.java)
            for (p in lm.getProviders(true)) {
                val loc = try {
                    lm.getLastKnownLocation(p)
                } catch (e: SecurityException) {
                    null
                }
                if (loc != null) return Pair(loc.latitude, loc.longitude)
            }
        }
        return Pair(CITIES[0].lat, CITIES[0].lng)
    }

    /* ----------------------------- التحديث ----------------------------- */

    private fun computeToday(now: Calendar) {
        val (lat, lng) = currentLatLng()
        val tz = TimeZone.getDefault().getOffset(now.timeInMillis) / 3600000.0
        val t = PrayerCalc.compute(
            now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH),
            lat, lng, tz
        )
        mins = IntArray(6) { (Math.round(t[it] * 60) % 1440).toInt() }
        for (i in 0 until 6) rows[i].second.text = fmt(mins[i])

        val c = prefs.getInt("city", 0)
        val cityName = if (c >= 0 && c < CITIES.size) CITIES[c].name else "موقعي"
        tvCity.text = "مواقيت الصلاة · $cityName"

        tvDate.text = SimpleDateFormat("EEEE d MMMM yyyy", Locale("ar")).format(Date())
        tvHijri.text = try {
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ar")).format(HijrahDate.now()) + " هـ"
        } catch (e: Exception) {
            ""
        }
    }

    private fun refresh() {
        val now = Calendar.getInstance()
        val key = now.get(Calendar.YEAR) * 400 + now.get(Calendar.DAY_OF_YEAR)
        if (key != timesDay) {
            computeToday(now)
            timesDay = key
        }
        val h = now.get(Calendar.HOUR_OF_DAY)
        val m = now.get(Calendar.MINUTE)
        val s = now.get(Calendar.SECOND)

        val h12 = if (h % 12 == 0) 12 else h % 12
        val ap = if (h < 12) "ص" else "م"
        val str = String.format(Locale.US, "%d:%02d %s", h12, m, ap)
        val sp = SpannableString(str)
        sp.setSpan(RelativeSizeSpan(0.3f), str.length - 1, str.length, 0)
        tvTime.text = sp

        val nowSec = h * 3600 + m * 60 + s
        var next = -1
        for (i in NEXT_IDX) {
            if (mins[i] * 60 > nowSec) {
                next = i
                break
            }
        }
        val diff = if (next >= 0) mins[next] * 60 - nowSec else 86400 - nowSec + mins[0] * 60
        val shown = if (next >= 0) next else 0
        tvNext.text = "الصلاة القادمة: " + PRAYER_NAMES[shown]
        tvCount.text = String.format(Locale.US, "%02d:%02d:%02d", diff / 3600, diff % 3600 / 60, diff % 60)

        for (i in 0 until 6) {
            val col = if (i == shown) accent else Color.WHITE
            rows[i].first.setTextColor(col)
            rows[i].second.setTextColor(col)
        }
    }
}
