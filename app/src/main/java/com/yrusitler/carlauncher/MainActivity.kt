package com.yrusitler.carlauncher

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.*

/**
 * Y_RUSITLER CAR LAUNCHER - MASTER CORE EDITION
 * 
 * Entegre Edilen ve Temizlenen Modüller:
 * 1. Compass 360 Pro (v5.5) -> Clinometer (Roll/Pitch Arazi Eğim & Su Terazisi Motoru), Rakım, Koordinat
 * 2. GPS Compass Navigator (v2.20) -> Car Mode GPS Bearing (>5 km/h Manyetik Sapma Koruması) & HUD Yansıtma
 * 3. Torque Pro (v1.12) -> CANbus / OBD2 Telemetri (Motor Suyu, Voltaj, Turbo Boost, 4WD Durumu, Arıza Kodları)
 * 4. Weather Advanced (v1.2) -> Çevrimdışı/Çevrimiçi Hava Raporu, Çiğ Noktası (Dew Point), Nem, Rüzgar, Özel Font Desteği
 */
class MainActivity : AppCompatActivity(), SensorEventListener, LocationListener {

    // --- TEMA & SKIN RENK PALETİ (Sonraki aşamada harici dosyadan da beslenebilir) ---
    object SkinTheme {
        const val BG_DARK = "#0B0D12"
        const val CARD_BG = "#151922"
        const val CARD_STROKE = "#33D4AF37"
        const val ACCENT_GOLD = "#D4AF37"
        const val ACCENT_CYAN = "#00E5FF"
        const val ACCENT_GREEN = "#00E676"
        const val ACCENT_RED = "#FF5252"
        const val TEXT_PRIMARY = "#FFFFFF"
        const val TEXT_SECONDARY = "#9E9E9E"
    }

    // --- SİSTEM SERVİSLERİ ---
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private val backgroundExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    // --- UI BİLEŞENLERİ (SOL PANEL: SAAT & WEATHER ADVANCED MODÜLÜ) ---
    private lateinit var imgWeatherBg: ImageView
    private lateinit var tvDigitalClock: TextView
    private lateinit var tvDate: TextView
    private lateinit var imgWeatherIcon: ImageView
    private lateinit var tvWeatherTemp: TextView
    private lateinit var tvWeatherCondition: TextView
    private lateinit var tvWeatherDetails: TextView // Hissedilen, Nem, Rüzgar, Basınç, Çiğ Noktası

    // --- UI BİLEŞENLERİ (ORTA PANEL: HIZ, HUD, COMPASS 360 & NAVIGATOR MODÜLÜ) ---
    private lateinit var speedHudContainer: LinearLayout
    private lateinit var tvSpeedValue: TextView
    private lateinit var tvSpeedLimitBadge: TextView
    private lateinit var btnHudMode: TextView
    private lateinit var tvGpsModeBadge: TextView
    private lateinit var customCompassClinometerView: CompassClinometerCanvasView
    private lateinit var tvAltitude: TextView
    private lateinit var tvCoordinates: TextView
    private lateinit var tvMagneticWarning: TextView

    // --- UI BİLEŞENLERİ (SAĞ PANEL: TORQUE OBD2 / CANBUS TELEMETRİ MODÜLÜ) ---
    private lateinit var imgObdCoolant: ImageView
    private lateinit var tvObdCoolant: TextView
    private lateinit var imgObdBattery: ImageView
    private lateinit var tvObdVoltage: TextView
    private lateinit var imgObdTurbo: ImageView
    private lateinit var tvObdTurbo: TextView
    private lateinit var imgObdAwd: ImageView
    private lateinit var tvObdAwd: TextView
    private lateinit var tvObdDtcStatus: TextView

    // --- UI BİLEŞENLERİ (ALT DOCK: NAVİGASYON, MEDYA VE HIZLI LAUNCHER BAR) ---
    private lateinit var tvActiveMediaTrack: TextView

    // --- SENSÖR VE DURUM DEĞİŞKENLERİ ---
    private val accelValues = FloatArray(3)
    private val magValues = FloatArray(3)
    private var hasMagnetometer = false
    private var isHudActive = false
    private var currentSpeedKmh = 0
    private var currentAzimuth = 0f
    private var currentPitch = 0f // Ön-Arka Eğim (Derece)
    private var currentRoll = 0f  // Sağ-Sol Yatış (Derece)
    private var lastWeatherLat = 0.0
    private var lastWeatherLon = 0.0

    // --- SAAT GÜNCELLEYİCİ ---
    private val clockRunnable = object : Runnable {
        override fun run() {
            val now = Date()
            tvDigitalClock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            tvDate.text = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale("tr")).format(now).uppercase()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    // --- CANBUS / TORQUE & MEDYA BROADCAST DİNLEYİCİSİ ---
    private val carSystemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                // Torque Pro / OBD2 veya Özel CANbus Servisinden Gelen Veriler
                "com.yrusitler.carlauncher.ACTION_OBD_UPDATE" -> {
                    val coolant = intent.getIntExtra("coolant_temp", 88)
                    val voltage = intent.getFloatExtra("battery_voltage", 14.2f)
                    val boost = intent.getFloatExtra("turbo_boost", 0.4f)
                    val awdMode = intent.getStringExtra("awd_mode") ?: "4WD AUTO"
                    val dtcCount = intent.getIntExtra("dtc_count", 0)
                    updateTorqueTelemetryUI(coolant, voltage, boost, awdMode, dtcCount)
                }
                // Medya Çalar Bilgisi (Spotify, Poweramp, Araç Radyosu)
                "com.android.music.metachanged", "com.spotify.music.metachanged" -> {
                    val track = intent.getStringExtra("track") ?: "Medya Bekleniyor"
                    val artist = intent.getStringExtra("artist") ?: ""
                    tvActiveMediaTrack.text = if (artist.isNotEmpty()) "$artist - $track" else track
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableFullScreenCarMode()

        // Tüm Launcher Arayüzünü Kod Üzerinden İnşa Et
        setContentView(buildCarLauncherCockpitUI())

        // Sistem Yöneticilerini Başlat
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // APK'lardan Gelecek Özel Font ve İkonları Bağla
        applyExternalAssetsAndSkins()

        // İzinleri Kontrol Et ve Sensörleri Başlat
        checkPermissionsAndStartEngines()
    }

    /**
     * Araç multimedya ekranı için tam ekran, durum çubuksuz ve ekran kapanmayan mod
     */
    private fun enableFullScreenCarMode() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
    }

    /**
     * Y_RUSITLER CAR LAUNCHER ANA KOKPİT TASARIMI
     * Üst Bar + 3 Ana Yeni Modül Sütunu + Alt Hızlı Erişim & Medya Barı
     */
    private fun buildCarLauncherCockpitUI(): View {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(SkinTheme.BG_DARK))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            weightSum = 100f
        }

        // =========================================================================
        // 1. ÜST DURUM BARI (Y_RUSITLER BRANDING & GPS / MANYETİK ALAN UYARILARI)
        // =========================================================================
        val topHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 8f)
        }

        val tvBrandTitle = TextView(this).apply {
            text = "Y_RUSITLER CAR LAUNCHER"
            setTextColor(Color.parseColor(SkinTheme.ACCENT_GOLD))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        tvMagneticWarning = TextView(this).apply {
            text = "MANYETİK ALAN: NORMAL"
            setTextColor(Color.parseColor(SkinTheme.TEXT_SECONDARY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(12), dp(2), dp(12), dp(2))
        }

        tvGpsModeBadge = TextView(this).apply {
            text = "GPS BEKLENİYOR..."
            setTextColor(Color.parseColor(SkinTheme.ACCENT_GREEN))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            background = createRoundedCard( "#1A00E676", SkinTheme.ACCENT_GREEN, 8f)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }

        topHeader.addView(tvBrandTitle)
        topHeader.addView(tvMagneticWarning)
        topHeader.addView(tvGpsModeBadge)
        rootLayout.addView(topHeader)

        // =========================================================================
        // 2. ORTA ANA KOKPİT ALANI (3 YENİ MODÜL SÜTUNU)
        // =========================================================================
        val centerGrid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 76f)
            weightSum = 12f
            setPadding(0, dp(8), 0, dp(8))
        }

        // --- SÜTUN 1: WEATHER ADVANCED & DİJİTAL SAAT MODÜLÜ (Weight: 3.5) ---
        val weatherCard = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3.5f).apply {
                marginEnd = dp(8)
            }
            background = createRoundedCard(SkinTheme.CARD_BG, SkinTheme.CARD_STROKE, 18f)
        }

        imgWeatherBg = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.22f
        }
        weatherCard.addView(imgWeatherBg)

        val weatherContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        tvDigitalClock = TextView(this).apply {
            text = "09:50"
            setTextColor(Color.parseColor(SkinTheme.TEXT_PRIMARY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
            typeface = Typeface.DEFAULT_BOLD
        }

        tvDate = TextView(this).apply {
            text = "PAZARTESİ, 02 KASIM"
            setTextColor(Color.parseColor(SkinTheme.ACCENT_GOLD))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            letterSpacing = 0.08f
        }

        val weatherSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val weatherRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        imgWeatherIcon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setImageResource(android.R.drawable.ic_menu_day)
            setColorFilter(Color.parseColor(SkinTheme.ACCENT_GOLD))
        }

        val weatherTempBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }

        tvWeatherTemp = TextView(this).apply {
            text = "--°C"
            setTextColor(Color.parseColor(SkinTheme.TEXT_PRIMARY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
        }

        tvWeatherCondition = TextView(this).apply {
            text = "Hava Raporu Güncelleniyor..."
            setTextColor(Color.parseColor(SkinTheme.ACCENT_CYAN))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }

        weatherTempBox.addView(tvWeatherTemp)
        weatherTempBox.addView(tvWeatherCondition)
        weatherRow.addView(imgWeatherIcon)
        weatherRow.addView(weatherTempBox)

        tvWeatherDetails = TextView(this).apply {
            text = "Hissedilen: --°C  •  Nem: --%\nRüzgar: -- km/h  •  Çiğ Noktası: --°C"
            setTextColor(Color.parseColor(SkinTheme.TEXT_SECONDARY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(10), 0, 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        }

        weatherContent.addView(tvDigitalClock)
        weatherContent.addView(tvDate)
        weatherContent.addView(weatherSpacer)
        weatherContent.addView(weatherRow)
        weatherContent.addView(tvWeatherDetails)
        weatherCard.addView(weatherContent)
        centerGrid.addView(weatherCard)

        // --- SÜTUN 2: GPS COMPASS NAVIGATOR + COMPASS 360 PRO + HIZ & HUD (Weight: 5.0) ---
        val centerNavCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 5.0f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
            background = createRoundedCard(SkinTheme.CARD_BG, SkinTheme.CARD_STROKE, 18f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        // Üst kontrol: Hız Sınırı Rozeti & HUD Yansıtma Butonu
        val centerTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        tvSpeedLimitBadge = TextView(this).apply {
            text = "LİMİT: 100"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            background = createRoundedCard("#33FF5252", SkinTheme.ACCENT_RED, 20f)
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }

        val centerTopSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        btnHudMode = TextView(this).apply {
            text = "HUD YANSITMA: KAPALI"
            setTextColor(Color.parseColor(SkinTheme.ACCENT_CYAN))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            background = createRoundedCard("#1A00E5FF", SkinTheme.ACCENT_CYAN, 8f)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnClickListener { toggleHudReflectionMode() }
        }

        centerTopRow.addView(tvSpeedLimitBadge)
        centerTopRow.addView(centerTopSpacer)
        centerTopRow.addView(btnHudMode)
        centerNavCard.addView(centerTopRow)

        // Orta Bölüm: Sol tarafta Dev Dijital Hız, Sağ tarafta 360° Pusula + Arazi Eğim/Su Terazisi Kadranı
        speedHudContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            weightSum = 10f
        }

        val speedBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 4.5f)
        }

        tvSpeedValue = TextView(this).apply {
            text = "0"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 68f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val tvSpeedUnit = TextView(this).apply {
            text = "KM/H"
            setTextColor(Color.parseColor(SkinTheme.ACCENT_GOLD))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.2f
        }

        speedBox.addView(tvSpeedValue)
        speedBox.addView(tvSpeedUnit)

        // Özel Çizim Pusula + Clinometer (Pitch/Roll Arazi Eğim Ölçer)
        customCompassClinometerView = CompassClinometerCanvasView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 5.5f)
        }

        speedHudContainer.addView(speedBox)
        speedHudContainer.addView(customCompassClinometerView)
        centerNavCard.addView(speedHudContainer)

        // Alt Bölüm: Rakım (Barometrik/GPS) ve Koordinat (DMS/Ondalık)
        val centerBottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        tvAltitude = TextView(this).apply {
            text = "RAKIM: -- m"
            setTextColor(Color.parseColor(SkinTheme.TEXT_SECONDARY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        tvCoordinates = TextView(this).apply {
            text = "KOORD: --.----°K, --.----°D"
            setTextColor(Color.parseColor(SkinTheme.TEXT_SECONDARY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }

        centerBottomRow.addView(tvAltitude)
        centerBottomRow.addView(tvCoordinates)
        centerNavCard.addView(centerBottomRow)
        centerGrid.addView(centerNavCard)

        // --- SÜTUN 3: TORQUE PRO OBD2 / CANBUS TELEMETRİ & 4WD MODÜLÜ (Weight: 3.5) ---
        val torqueCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3.5f).apply {
                marginStart = dp(8)
            }
            background = createRoundedCard(SkinTheme.CARD_BG, SkinTheme.CARD_STROKE, 18f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            weightSum = 5f
        }

        val tvTorqueHeader = TextView(this).apply {
            text = "CANBUS & TORQUE TELEMETRİ"
            setTextColor(Color.parseColor(SkinTheme.ACCENT_GOLD))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.7f)
        }
        torqueCard.addView(tvTorqueHeader)

        // 1. Motor Suyu Sıcaklığı (Coolant)
        imgObdCoolant = ImageView(this)
        tvObdCoolant = TextView(this)
        torqueCard.addView(createTelemetryRow(imgObdCoolant, tvObdCoolant, "Motor Sıcaklığı: 88°C", SkinTheme.TEXT_PRIMARY))

        // 2. Akü Voltajı (Battery)
        imgObdBattery = ImageView(this)
        tvObdVoltage = TextView(this)
        torqueCard.addView(createTelemetryRow(imgObdBattery, tvObdVoltage, "Sistem Voltajı: 14.2 V", SkinTheme.ACCENT_CYAN))

        // 3. Turbo Basıncı (Boost)
        imgObdTurbo = ImageView(this)
        tvObdTurbo = TextView(this)
        torqueCard.addView(createTelemetryRow(imgObdTurbo, tvObdTurbo, "Turbo Boost: 0.4 Bar", SkinTheme.TEXT_PRIMARY))

        // 4. 4WD Aktarma Durumu (Outlander 4WD / AWD)
        imgObdAwd = ImageView(this)
        tvObdAwd = TextView(this)
        torqueCard.addView(createTelemetryRow(imgObdAwd, tvObdAwd, "Aktarma: 4WD AUTO", SkinTheme.ACCENT_GREEN))

        // 5. ECU Arıza Kodu (DTC) Durumu
        tvObdDtcStatus = TextView(this).apply {
            text = "ECU: ARIZA KAYDI YOK (0 DTC)"
            setTextColor(Color.parseColor(SkinTheme.ACCENT_GREEN))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.6f)
        }
        torqueCard.addView(tvObdDtcStatus)
        centerGrid.addView(torqueCard)

        rootLayout.addView(centerGrid)

        // =========================================================================
        // 3. ALT DOCK: NAVİGASYON KISAYOLLARI VE MEDYA KONTROL BARI
        // =========================================================================
        val bottomDock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 16f)
            background = createRoundedCard(SkinTheme.CARD_BG, "#22FFFFFF", 16f)
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }

        val btnWaze = createDockButton("WAZE", SkinTheme.ACCENT_CYAN) {
            launchExternalApp("com.waze")
        }
        val btnMaps = createDockButton("HARİTA", SkinTheme.ACCENT_GREEN) {
            launchExternalApp("com.google.android.apps.maps")
        }
        val btnSpotify = createDockButton("MÜZİK", SkinTheme.ACCENT_GOLD) {
            launchExternalApp("com.spotify.music")
        }

        tvActiveMediaTrack = TextView(this).apply {
            text = "Y_RUSITLER SES SİSTEMİ AKTİF"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            ellipsize = TextUtils.TruncateAt.MARQUEE
            isSingleLine = true
            isSelected = true
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
            }
        }

        val btnAllApps = createDockButton("UYGULAMALAR", "#FFFFFF") {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MAPS))
        }

        bottomDock.addView(btnWaze)
        bottomDock.addView(btnMaps)
        bottomDock.addView(btnSpotify)
        bottomDock.addView(tvActiveMediaTrack)
        bottomDock.addView(btnAllApps)
        rootLayout.addView(bottomDock)

        return rootLayout
    }

    /**
     * Sağdaki Torque / CANbus kartı için ikonlu satır oluşturucu
     */
    private fun createTelemetryRow(iconView: ImageView, labelView: TextView, defaultText: String, textColorHex: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.9f)

            iconView.layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
            iconView.setImageResource(android.R.drawable.ic_Dialog_info)
            iconView.setColorFilter(Color.parseColor(SkinTheme.ACCENT_GOLD))

            labelView.text = defaultText
            labelView.setTextColor(Color.parseColor(textColorHex))
            labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            labelView.typeface = Typeface.DEFAULT_BOLD
            labelView.setPadding(dp(10), 0, 0, 0)

            addView(iconView)
            addView(labelView)
        }
    }

    private fun createDockButton(title: String, accentHex: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(Color.parseColor(accentHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            background = createRoundedCard("#1F2430", accentHex, 10f)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(10)
            }
            setOnClickListener { onClick() }
        }
    }

    /**
     * İkinci aşamada APK'lardan çıkarıp projeye ekleyeceğimiz font ve ikonları otomatik algılar.
     * Dosyalar henüz yoksa çökmez, varsayılan sistem stillerini kullanır.
     */
    private fun applyExternalAssetsAndSkins() {
        try {
            // Weather Advanced & GPS Tools içindeki fontlar
            val digitalFont = Typeface.createFromAsset(assets, "fonts/digital_font.ttf")
            tvDigitalClock.typeface = digitalFont
            tvSpeedValue.typeface = digitalFont
        } catch (_: Exception) {}

        // Torque Pro (App 3) içindeki assets/indicator/ ikonları
        loadAssetIndicator("indicator/wovl_temperature.png", imgObdCoolant)
        loadAssetIndicator("indicator/wovl_battery.png", imgObdBattery)
        loadAssetIndicator("indicator/wovl_turbo.png", imgObdTurbo)
        loadAssetIndicator("indicator/awd1.png", imgObdAwd)
    }

    private fun loadAssetIndicator(assetPath: String, targetView: ImageView) {
        try {
            assets.open(assetPath).use { stream ->
                val bmp = BitmapFactory.decodeStream(stream)
                targetView.clearColorFilter()
                targetView.setImageBitmap(bmp)
            }
        } catch (_: Exception) {
            // Görsel dosyası bir sonraki adımda eklendiğinde otomatik devreye girer
        }
    }

    /**
     * App 2 (GPS Compass Navigator) HUD Modu:
     * Aracın ön camına gece yansıtma yapmak için hız ve pusula panelini ayna görüntüsüne çevirir.
     */
    private fun toggleHudReflectionMode() {
        isHudActive = !isHudActive
        speedHudContainer.scaleX = if (isHudActive) -1f else 1f
        btnHudMode.text = if (isHudActive) "HUD YANSITMA: AÇIK" else "HUD YANSITMA: KAPALI"
    }

    // =========================================================================
    // SENSÖR, GPS, CAR MODE PUSULA VE HAVA RAPORU MOTORLARI
    // =========================================================================

    private fun checkPermissionsAndStartEngines() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 101)
        } else {
            startCockpitSensors()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCockpitSensors() {
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val mag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        if (accel != null) {
            sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI)
        }
        if (mag != null) {
            hasMagnetometer = true
            sensorManager.registerListener(this, mag, SensorManager.SENSOR_DELAY_UI)
        }

        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0.5f, this)
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onLocationChanged(it) }
        } catch (e: Exception) {
            tvGpsModeBadge.text = "GPS KAPALI"
        }
    }

    override fun onLocationChanged(location: Location) {
        // 1. Anlık Hız
        currentSpeedKmh = (location.speed * 3.6f).roundToInt()
        tvSpeedValue.text = currentSpeedKmh.toString()

        // Hız Sınırı Uyarı Kontrolü (100 km/h üstünde kırmızı uyarı)
        if (currentSpeedKmh > 100) {
            tvSpeedValue.setTextColor(Color.parseColor(SkinTheme.ACCENT_RED))
        } else {
            tvSpeedValue.setTextColor(Color.WHITE)
        }

        // 2. Rakım & Koordinatlar (App 1 & App 2)
        tvAltitude.text = "RAKIM: ${location.altitude.roundToInt()} m"
        tvCoordinates.text = String.format(Locale.US, "%.4f°K, %.4f°D", location.latitude, location.longitude)

        // 3. App 2 "Car Mode" Pusula Mantığı:
        // Araç 5 km/h hızın üzerine çıktığında mıknatıs sapmalarını yoksayarak doğrudan GPS uydularından yön alır.
        if (currentSpeedKmh >= 5 && location.hasBearing()) {
            currentAzimuth = location.bearing
            tvGpsModeBadge.text = "CAR MODE GPS (±${location.accuracy.roundToInt()}m)"
            customCompassClinometerView.updateTelemetry(currentAzimuth, currentPitch, currentRoll, true)
        } else {
            tvGpsModeBadge.text = "SENSÖR AKTİF (±${location.accuracy.roundToInt()}m)"
        }

        // 4. Hava Durumu Güncellemesi (Weather Advanced Motoru)
        if (abs(location.latitude - lastWeatherLat) > 0.03 || abs(location.longitude - lastWeatherLon) > 0.03) {
            lastWeatherLat = location.latitude
            lastWeatherLon = location.longitude
            fetchWeatherAdvancedData(location.latitude, location.longitude)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            // Alçak geçiren filtre (Low-pass filter) ile titreşimsiz kadran hareketi
            accelValues[0] = accelValues[0] * 0.85f + event.values[0] * 0.15f
            accelValues[1] = accelValues[1] * 0.85f + event.values[1] * 0.15f
            accelValues[2] = accelValues[2] * 0.85f + event.values[2] * 0.15f

            // Compass 360 Pro Arazi Eğim / Su Terazisi Hesaplaması (Pitch & Roll)
            val norm = sqrt(accelValues[0] * accelValues[0] + accelValues[1] * accelValues[1] + accelValues[2] * accelValues[2])
            if (norm > 0) {
                currentRoll = Math.toDegrees(asin((accelValues[0] / norm).toDouble())).toFloat()
                currentPitch = Math.toDegrees(asin((accelValues[1] / norm).toDouble())).toFloat()
            }
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            magValues[0] = magValues[0] * 0.85f + event.values[0] * 0.15f
            magValues[1] = magValues[1] * 0.85f + event.values[1] * 0.15f
            magValues[2] = magValues[2] * 0.85f + event.values[2] * 0.15f

            // App 2 Anormal Manyetik Alan Uyarı Kontrolü (> 75 microTesla)
            val magneticStrength = sqrt(magValues[0] * magValues[0] + magValues[1] * magValues[1] + magValues[2] * magValues[2])
            if (magneticStrength > 75f) {
                tvMagneticWarning.text = "MANYETİK SAPMA (${magneticStrength.roundToInt()} μT) -> GPS MODU"
                tvMagneticWarning.setTextColor(Color.parseColor(SkinTheme.ACCENT_RED))
            } else {
                tvMagneticWarning.text = "MANYETİK ALAN: ${magneticStrength.roundToInt()} μT"
                tvMagneticWarning.setTextColor(Color.parseColor(SkinTheme.TEXT_SECONDARY))
            }
        }

        // Araç duruyorsa (< 5 km/h) yönü manyetik pusuladan al
        if (currentSpeedKmh < 5 && hasMagnetometer) {
            val r = FloatArray(9)
            val i = FloatArray(9)
            if (SensorManager.getRotationMatrix(r, i, accelValues, magValues)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(r, orientation)
                var deg = Math.toDegrees(orientation[0].toDouble()).toFloat()
                deg = (deg + 360) % 360
                currentAzimuth = deg
            }
        }

        customCompassClinometerView.updateTelemetry(currentAzimuth, currentPitch, currentRoll, currentSpeedKmh >= 5)
    }

    /**
     * App 4 (Weather Advanced) Özelliği:
     * Sıcaklık, Hissedilen, Nem, Rüzgar, Basınç ve Çiğ Noktası (Dew Point) verilerini reklamsız çeker.
     */
    private fun fetchWeatherAdvancedData(lat: Double, lon: Double) {
        backgroundExecutor.execute {
            try {
                val urlStr = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                        "&current=temperature_2m,relative_humidity_2m,apparent_temperature,dew_point_2m,surface_pressure,wind_speed_10m,weather_code"
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val current = JSONObject(response).getJSONObject("current")

                val temp = current.getDouble("temperature_2m").roundToInt()
                val feelsLike = current.getDouble("apparent_temperature").roundToInt()
                val humidity = current.getInt("relative_humidity_2m")
                val dewPoint = current.getDouble("dew_point_2m").roundToInt()
                val wind = current.getDouble("wind_speed_10m").roundToInt()
                val pressure = current.getDouble("surface_pressure").roundToInt()
                val code = current.getInt("weather_code")

                mainHandler.post {
                    tvWeatherTemp.text = "${temp}°C"
                    tvWeatherCondition.text = decodeWeatherCode(code)
                    tvWeatherDetails.text = "Hissedilen: ${feelsLike}°C  •  Nem: %$humidity\n" +
                            "Rüzgar: $wind km/h  •  Basınç: $pressure hPa  •  Çiğ: ${dewPoint}°C"
                }
            } catch (_: Exception) {
                mainHandler.post {
                    tvWeatherCondition.text = "Çevrimdışı Mod"
                }
            }
        }
    }

    private fun decodeWeatherCode(code: Int): String = when (code) {
        0 -> "Açık & Güneşli"
        1, 2, 3 -> "Parçalı Bulutlu"
        45, 48 -> "Sisli"
        51, 53, 55, 61, 63, 65 -> "Yağışlı"
        71, 73, 75 -> "Kar Yağışlı"
        95, 96, 99 -> "Fırtına / Gök Gürültülü"
        else -> "Bulutlu"
    }

    /**
     * App 3 (Torque Pro) Telemetri Güncelleyici
     */
    private fun updateTorqueTelemetryUI(coolant: Int, voltage: Float, boost: Float, awdMode: String, dtcCount: Int) {
        tvObdCoolant.text = "Motor Sıcaklığı: ${coolant}°C"
        tvObdVoltage.text = String.format(Locale.US, "Sistem Voltajı: %.1f V", voltage)
        tvObdTurbo.text = String.format(Locale.US, "Turbo Boost: %.2f Bar", boost)
        tvObdAwd.text = "Aktarma: $awdMode"

        if (dtcCount > 0) {
            tvObdDtcStatus.text = "UYARI: $dtcCount ARIZA KODU (DTC) TESPİT EDİLDİ!"
            tvObdDtcStatus.setTextColor(Color.parseColor(SkinTheme.ACCENT_RED))
        } else {
            tvObdDtcStatus.text = "ECU: ARIZA KAYDI YOK (0 DTC)"
            tvObdDtcStatus.setTextColor(Color.parseColor(SkinTheme.ACCENT_GREEN))
        }
    }

    // =========================================================================
    // ÖZEL CANVAS KADRANI: COMPASS 360 PRO + CLINOMETER (SU TERAZİSİ / EĞİM)
    // =========================================================================
    inner class CompassClinometerCanvasView(context: Context) : View(context) {
        private var azimuth = 0f
        private var pitch = 0f
        private var roll = 0f
        private var isGpsCarMode = false

        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.parseColor(SkinTheme.ACCENT_GOLD)
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.parseColor("#33FFFFFF")
        }
        private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor(SkinTheme.ACCENT_CYAN)
        }
        private val northPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor(SkinTheme.ACCENT_RED)
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        fun updateTelemetry(newAzimuth: Float, newPitch: Float, newRoll: Float, carMode: Boolean) {
            this.azimuth = newAzimuth
            this.pitch = newPitch
            this.roll = newRoll
            this.isGpsCarMode = carMode
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = min(cx, cy) * 0.78f

            // 1. Dış Pusula Çemberi
            canvas.drawCircle(cx, cy, radius, ringPaint)
            canvas.drawCircle(cx, cy, radius * 0.55f, gridPaint)
            canvas.drawLine(cx - radius * 0.6f, cy, cx + radius * 0.6f, cy, gridPaint)
            canvas.drawLine(cx, cy - radius * 0.6f, cx, cy + radius * 0.6f, gridPaint)

            // 2. Dönen Pusula Kadranı (K, D, G, B)
            canvas.save()
            canvas.rotate(-azimuth, cx, cy)
            val directions = arrayOf("K", "D", "G", "B")
            for (i in 0..3) {
                canvas.save()
                canvas.rotate(i * 90f, cx, cy)
                if (i == 0) {
                    // Kuzey Ok İşareti
                    val path = Path().apply {
                        moveTo(cx, cy - radius + 8f)
                        lineTo(cx - 12f, cy - radius + 32f)
                        lineTo(cx + 12f, cy - radius + 32f)
                        close()
                    }
                    canvas.drawPath(path, northPaint)
                }
                canvas.drawText(directions[i], cx, cy - radius + 62f, textPaint)
                canvas.restore()
            }
            canvas.restore()

            // 3. Compass 360 Pro Su Terazisi / Off-Road Eğim Noktası (Pitch & Roll Bubble)
            val maxTilt = 30f
            val clampedRoll = roll.coerceIn(-maxTilt, maxTilt)
            val clampedPitch = pitch.coerceIn(-maxTilt, maxTilt)
            val bubbleX = cx + (clampedRoll / maxTilt) * (radius * 0.45f)
            val bubbleY = cy + (clampedPitch / maxTilt) * (radius * 0.45f)
            canvas.drawCircle(bubbleX, bubbleY, 14f, bubblePaint)

            // 4. Alt Bilgi: Derece ve Eğim Açıları
            val dirNames = arrayOf("K", "KD", "D", "GD", "G", "GB", "B", "KB")
            val dirIndex = (((azimuth + 22.5) % 360) / 45.0).toInt()
            val summaryText = "${dirNames[dirIndex]} ${azimuth.roundToInt()}°  |  Eğim: P:${pitch.roundToInt()}° R:${roll.roundToInt()}°"
            textPaint.textSize = 22f
            textPaint.color = Color.parseColor(SkinTheme.ACCENT_GOLD)
            canvas.drawText(summaryText, cx, height - 10f, textPaint)
            textPaint.color = Color.WHITE
            textPaint.textSize = 28f
        }
    }

    // =========================================================================
    // YARDIMCI FONKSİYONLAR VE YAŞAM DÖNGÜSÜ
    // =========================================================================

    private fun createRoundedCard(bgHex: String, strokeHex: String, cornerRadiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(cornerRadiusDp.toInt()).toFloat()
            setColor(Color.parseColor(bgHex))
            setStroke(dp(1), Color.parseColor(strokeHex))
        }
    }

    private fun launchExternalApp(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, "$packageName yüklü değil", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    override fun onResume() {
        super.onResume()
        mainHandler.post(clockRunnable)
        val filter = IntentFilter().apply {
            addAction("com.yrusitler.carlauncher.ACTION_OBD_UPDATE")
            addAction("com.android.music.metachanged")
            addAction("com.spotify.music.metachanged")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(carSystemReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(carSystemReceiver, filter)
        }
        checkPermissionsAndStartEngines()
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(clockRunnable)
        try {
            unregisterReceiver(carSystemReceiver)
        } catch (_: Exception) {}
        locationManager.removeUpdates(this)
        sensorManager.unregisterListener(this)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
