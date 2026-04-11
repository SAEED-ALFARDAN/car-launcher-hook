package com.saeed.headunithook

import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainHook : IXposedHookLoadPackage {

    private var myCustomRoad: HighPerfRoadView? = null

    private val LASTFM_API_KEY = "21792d3b89dc04b702439159c0811263"
    private val albumArtCache = HashMap<String, android.graphics.Bitmap?>()
    private val diskCacheDir = File("/data/local/tmp/albumart")
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var pendingArtTitle = ""

    // Orbital Phase Variables
    private var orbitalPhase1 = 0f
    private var orbitalPhase2 = 2.09f  // 120 degrees offset
    private var orbitalPhase3 = 4.18f  // 240 degrees offset
    private var lastDrawTime = System.currentTimeMillis()

    // Ring Variables
    private var ringStyle = 0  // 0=Pulsing, 1=Particles, 2=SoundWave, 3=Minimal
    private var ringPhase = 0f
    private var lastRingTime = System.currentTimeMillis()
    private var enableCustomRings = true
    private var ringRainbowStart = 0f
    private var ringRainbowEnd = 360f

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.spd.home") return
        hook3DScene(lpparam)
        hookMusicFixes(lpparam)
        hookMyID3View(lpparam)
    }

    // ==========================================
    // 3D SCENE HOOKS
    // ==========================================

    private fun hook3DScene(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookConstructor(
                "com.spd.Scene.CarinfoFrameManager",
                lpparam.classLoader,
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val context = param.args[0] as Context
                            val host = param.thisObject
                            val carLogo = XposedHelpers.getObjectField(host, "m_car_logo") as View
                            val rootView = XposedHelpers.getObjectField(host, "m_root_view") as ViewGroup
                            val originalRoad = XposedHelpers.getObjectField(host, "m_road_car_view") as View

                            myCustomRoad = HighPerfRoadView(context)
                            loadSettings(context) // Load saved settings and presets immediately

                            originalRoad.visibility = View.GONE
                            rootView.addView(myCustomRoad, 0, originalRoad.layoutParams)

                            carLogo.setOnClickListener { showControlPanel(context) }

                            carLogo.setOnLongClickListener {
                                try {
                                    val currentFlag = XposedHelpers.getBooleanField(host, "m_demo_flag")
                                    XposedHelpers.setBooleanField(host, "m_demo_flag", !currentFlag)
                                    val handler = XposedHelpers.getObjectField(host, "m_call_back_handler") as android.os.Handler
                                    handler.sendEmptyMessageDelayed(0, 33L)
                                    Toast.makeText(context, "Demo Speed: ${if (!currentFlag) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    XposedBridge.log("Demo Toggle Failed: ${e.message}")
                                }
                                true
                            }
                        } catch (e: Exception) {
                            XposedBridge.log("3D Scene inject failed: ${e.message}")
                        }
                    }
                }
            )
        } catch (e: Exception) {
            XposedBridge.log("Failed to hook CarinfoFrameManager: ${e.message}")
        }

        try {
            XposedHelpers.findAndHookMethod(
                "com.spd.roadcar.RoadCarRootView",
                lpparam.classLoader,
                "setSpeed",
                Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val speed = param.args[0] as Float
                            myCustomRoad?.setSpeed(speed)
                        } catch (e: Exception) {
                            XposedBridge.log("setSpeed hook failed: ${e.message}")
                        }
                    }
                }
            )
        } catch (e: Exception) {
            XposedBridge.log("Failed to hook setSpeed: ${e.message}")
        }
    }

    // ==========================================
    // MUSIC HOOKS
    // ==========================================

    private fun hookMusicFixes(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookConstructor(
                "com.spd.Scene.MusicFrameManager",
                lpparam.classLoader,
                Context::class.java,
                ViewGroup::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val host = param.thisObject
                            XposedHelpers.setAdditionalInstanceField(host, "m_last_bt_title", "")
                            XposedHelpers.setAdditionalInstanceField(host, "m_last_bt_artist", "")

                            val rootView = XposedHelpers.getObjectField(host, "m_root_view") as ViewGroup
                            rootView.isLongClickable = true
                            rootView.setOnLongClickListener {
                                injectFakeTrack(host, lpparam.classLoader)
                                true
                            }
                        } catch (e: Exception) {
                            XposedBridge.log("MusicFrameManager constructor inject failed: ${e.message}")
                        }
                    }
                }
            )
        } catch (e: Exception) {
            XposedBridge.log("Failed to hook MusicFrameManager constructor: ${e.message}")
        }

        try {
            XposedHelpers.findAndHookMethod(
                "com.spd.Scene.MusicFrameManager",
                lpparam.classLoader,
                "onMediaPlayTimeChanged",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val timeMs = param.args[0] as Int
                            val durationMs = param.args[1] as Int
                            if (durationMs <= 0) { param.result = null; return }
                            val host = param.thisObject
                            val mediaHelper = XposedHelpers.callStaticMethod(
                                XposedHelpers.findClass("com.spd.custom.view.MediaHelper", lpparam.classLoader), "get"
                            )
                            val isAudioFocus = XposedHelpers.callMethod(mediaHelper, "isAudioFocus") as Boolean
                            if (isAudioFocus) {
                                XposedHelpers.setObjectField(host, "m_playing_time_total", durationMs)
                                val progress = timeMs.toFloat() / durationMs.toFloat()
                                val progressBar = XposedHelpers.getObjectField(host, "m_progress_bar")
                                if (progressBar != null) {
                                    XposedHelpers.callMethod(progressBar, "setCurrentValue", progress)
                                }
                            }
                            param.result = null
                        } catch (e: Exception) {
                            XposedBridge.log("onMediaPlayTimeChanged hook error: ${e.message}")
                            param.result = null
                        }
                    }
                }
            )
        } catch (e: Exception) {
            XposedBridge.log("Failed to hook onMediaPlayTimeChanged: ${e.message}")
        }

        try {
            XposedHelpers.findAndHookMethod(
                "com.spd.Scene.MusicFrameManager",
                lpparam.classLoader,
                "checkBtMediaInfo",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val host = param.thisObject
                            var musicInfo = XposedHelpers.getObjectField(host, "m_current_music_info")
                            if (musicInfo == null) {
                                val btHelper = XposedHelpers.callStaticMethod(
                                    XposedHelpers.findClass("com.spd.custom.view.BtHelper", lpparam.classLoader), "get"
                                )
                                musicInfo = XposedHelpers.callMethod(btHelper, "getBtMusicInfo")
                                if (musicInfo != null) {
                                    XposedHelpers.setObjectField(host, "m_current_music_info", musicInfo)
                                }
                            }

                            val btStatus = XposedHelpers.getIntField(host, "m_bt_media_status")
                            val id3View = XposedHelpers.getObjectField(host, "m_my_id3_view")
                            val btnPlay = XposedHelpers.getObjectField(host, "m_bn_play")
                            val titleView = XposedHelpers.getObjectField(host, "m_id3_title")
                            val subTitleView = XposedHelpers.getObjectField(host, "m_id3_sub_title")
                            val drawableClass = XposedHelpers.findClass("com.spd.home.R\$drawable", lpparam.classLoader)
                            val rPlay = XposedHelpers.getStaticIntField(drawableClass, "icon_music_play")
                            val rPause = XposedHelpers.getStaticIntField(drawableClass, "icon_music_pause")

                            if (btStatus >= 1 && musicInfo != null) {
                                val title = (XposedHelpers.callMethod(musicInfo, "getSongTitle") as? String) ?: ""
                                val artist = (XposedHelpers.callMethod(musicInfo, "getSongArtist") as? String) ?: ""
                                if (titleView != null) XposedHelpers.callMethod(titleView, "setText", title)
                                if (subTitleView != null) XposedHelpers.callMethod(subTitleView, "setText", artist)

                                if (btStatus == 3) {
                                    if (btnPlay != null) XposedHelpers.callMethod(btnPlay, "setIconDrawableResource", rPause)
                                    if (id3View != null) XposedHelpers.callMethod(id3View, "setSpeedEffect", 1.0f)
                                } else {
                                    if (btnPlay != null) XposedHelpers.callMethod(btnPlay, "setIconDrawableResource", rPlay)
                                    if (id3View != null) XposedHelpers.callMethod(id3View, "setSpeedEffect", 0.25f)
                                }
                            } else {
                                if (btnPlay != null) XposedHelpers.callMethod(btnPlay, "setIconDrawableResource", rPlay)
                                if (id3View != null) XposedHelpers.callMethod(id3View, "setSpeedEffect", 0.25f)
                                if (titleView != null) XposedHelpers.callMethod(titleView, "setText", "")
                            }

                            val currentTime = XposedHelpers.getLongField(host, "m_current_time")
                            val totalTime = XposedHelpers.getLongField(host, "m_total_time")
                            if (musicInfo != null) {
                                val durationStr = (XposedHelpers.callMethod(musicInfo, "getDuration") as? String) ?: ""
                                val duration = if (durationStr.isNotEmpty()) {
                                    try { durationStr.toLong() } catch (e: Exception) { 1L }
                                } else 1L
                                XposedHelpers.setLongField(host, "m_total_time", duration)
                            }
                            val progressBar = XposedHelpers.getObjectField(host, "m_progress_bar")
                            val finalTotal = XposedHelpers.getLongField(host, "m_total_time")
                            if (progressBar != null && finalTotal > 0) {
                                XposedHelpers.callMethod(progressBar, "setCurrentValue", currentTime.toFloat() / finalTotal.toFloat())
                            }

                            param.result = null
                        } catch (e: Exception) {
                            XposedBridge.log("checkBtMediaInfo replacement error: ${e.message}")
                        }
                    }
                }
            )
        } catch (e: Exception) {
            XposedBridge.log("Failed to hook checkBtMediaInfo: ${e.message}")
        }

        try {
            XposedHelpers.findAndHookMethod(
                "com.spd.Scene.MusicFrameManager",
                lpparam.classLoader,
                "onPlayStatusChanged",
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val status = param.args[0] as Int
                            val position = param.args[1] as Long
                            val host = param.thisObject

                            XposedHelpers.setIntField(host, "m_bt_media_status", status)
                            XposedHelpers.setLongField(host, "m_current_time", position)

                            val totalTime = XposedHelpers.getLongField(host, "m_total_time")
                            if (totalTime > 0) {
                                val progress = position.toFloat() / totalTime.toFloat()
                                val progressBar = XposedHelpers.getObjectField(host, "m_progress_bar")
                                if (progressBar != null) {
                                    XposedHelpers.callMethod(progressBar, "setCurrentValue", progress)
                                }
                            }

                            val id3View = XposedHelpers.getObjectField(host, "m_my_id3_view")
                            val btnPlay = XposedHelpers.getObjectField(host, "m_bn_play")
                            val drawableClass = XposedHelpers.findClass("com.spd.home.R\$drawable", lpparam.classLoader)
                            val rPause = XposedHelpers.getStaticIntField(drawableClass, "icon_music_pause")
                            val rPlay = XposedHelpers.getStaticIntField(drawableClass, "icon_music_play")

                            if (status == 3) {
                                if (btnPlay != null) XposedHelpers.callMethod(btnPlay, "setIconDrawableResource", rPause)
                                if (id3View != null) XposedHelpers.callMethod(id3View, "setSpeedEffect", 1.0f)
                            } else {
                                if (btnPlay != null) XposedHelpers.callMethod(btnPlay, "setIconDrawableResource", rPlay)
                                if (id3View != null) XposedHelpers.callMethod(id3View, "setSpeedEffect", 0.25f)
                            }

                            param.result = true
                        } catch (e: Exception) {
                            XposedBridge.log("onPlayStatusChanged hook error: ${e.message}")
                            param.result = true
                        }
                    }
                }
            )
        } catch (e: Exception) {
            XposedBridge.log("Failed to hook onPlayStatusChanged: ${e.message}")
        }

        try {
            XposedHelpers.findAndHookMethod(
                "com.spd.Scene.MusicFrameManager",
                lpparam.classLoader,
                "onTrackInfoChanged",
                XposedHelpers.findClass("com.spd.bluetooth.entity.aidl.MusicInfo", lpparam.classLoader),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val musicInfo = param.args[0]
                            val host = param.thisObject
                            val id3View = XposedHelpers.getObjectField(host, "m_my_id3_view")
                            val btnPlay = XposedHelpers.getObjectField(host, "m_bn_play")
                            val titleView = XposedHelpers.getObjectField(host, "m_id3_title")
                            val subTitleView = XposedHelpers.getObjectField(host, "m_id3_sub_title")
                            val drawableClass = XposedHelpers.findClass("com.spd.home.R\$drawable", lpparam.classLoader)
                            val rPlay = XposedHelpers.getStaticIntField(drawableClass, "icon_music_play")
                            val rPause = XposedHelpers.getStaticIntField(drawableClass, "icon_music_pause")

                            if (musicInfo == null) {
                                XposedHelpers.setObjectField(host, "m_current_music_info", null)
                                if (titleView != null) XposedHelpers.callMethod(titleView, "setText", "")
                                if (subTitleView != null) XposedHelpers.callMethod(subTitleView, "setText", "")
                                if (btnPlay != null) XposedHelpers.callMethod(btnPlay, "setIconDrawableResource", rPlay)
                                if (id3View != null) XposedHelpers.callMethod(id3View, "setSpeedEffect", 0.25f)
                                param.result = false
                                return
                            }

                            val newTitle = (XposedHelpers.callMethod(musicInfo, "getSongTitle") as? String) ?: ""
                            val newArtist = (XposedHelpers.callMethod(musicInfo, "getSongArtist") as? String) ?: ""
                            val duration = (XposedHelpers.callMethod(musicInfo, "getDuration") as? String) ?: ""

                            val lastTitle = XposedHelpers.getAdditionalInstanceField(host, "m_last_bt_title") as? String ?: ""
                            val lastArtist = XposedHelpers.getAdditionalInstanceField(host, "m_last_bt_artist") as? String ?: ""

                            XposedHelpers.setObjectField(host, "m_current_music_info", musicInfo)

                            if (newTitle != lastTitle || newArtist != lastArtist) {
                                XposedHelpers.setAdditionalInstanceField(host, "m_last_bt_title", newTitle)
                                XposedHelpers.setAdditionalInstanceField(host, "m_last_bt_artist", newArtist)

                                if (titleView != null) XposedHelpers.callMethod(titleView, "setText", newTitle)
                                if (subTitleView != null) XposedHelpers.callMethod(subTitleView, "setText", newArtist)

                                try {
                                    val oldBitmap = XposedHelpers.getObjectField(host, "m_current_album_art")
                                    if (oldBitmap != null) {
                                        val isRecycled = XposedHelpers.callMethod(oldBitmap, "isRecycled") as? Boolean ?: true
                                        if (!isRecycled) XposedHelpers.callMethod(oldBitmap, "recycle")
                                    }
                                } catch (e: Exception) { /* ignore recycle errors */ }

                                XposedHelpers.setObjectField(host, "m_current_album_art", null)
                                if (id3View != null) XposedHelpers.callMethod(id3View, "setUserAlbum", null as android.graphics.Bitmap?)

                                val capturedTitle = newTitle
                                val capturedArtist = newArtist
                                pendingArtTitle = newTitle

                                val densityScale = try {
                                    XposedHelpers.getStaticFloatField(
                                        XposedHelpers.findClass("com.spd.custom.view.MyID3View", lpparam.classLoader),
                                        "m_img_density_scale"
                                    )
                                } catch (e: Exception) { 1.0f }

                                val displaySize = (140f * densityScale).toInt()

                                fetchAlbumArt(capturedTitle, capturedArtist, 600) { bitmap ->
                                    if (pendingArtTitle == capturedTitle && bitmap != null) {
                                        try {
                                            val scaled = scaleBitmap(bitmap, displaySize)
                                            val circle = toCircleBitmap(scaled)
                                            if (scaled != bitmap) bitmap.recycle()
                                            if (id3View != null) XposedHelpers.callMethod(id3View, "setUserAlbum", circle)
                                            XposedHelpers.setObjectField(host, "m_current_album_art", circle)
                                        } catch (e: Exception) {
                                            XposedBridge.log("setUserAlbum failed: ${e.message}")
                                        }
                                    }
                                }
                            }

                            if (duration.isNotEmpty()) {
                                try {
                                    XposedHelpers.setLongField(host, "m_total_time", duration.toLong())
                                } catch (e: NumberFormatException) {
                                    XposedHelpers.setLongField(host, "m_total_time", 1L)
                                }
                            }

                            val btStatus = XposedHelpers.getIntField(host, "m_bt_media_status")
                            if (btStatus == 3) {
                                if (btnPlay != null) XposedHelpers.callMethod(btnPlay, "setIconDrawableResource", rPause)
                                if (id3View != null) XposedHelpers.callMethod(id3View, "setSpeedEffect", 1.0f)
                            } else {
                                if (btnPlay != null) XposedHelpers.callMethod(btnPlay, "setIconDrawableResource", rPlay)
                                if (id3View != null) XposedHelpers.callMethod(id3View, "setSpeedEffect", 0.25f)
                            }

                            param.result = false
                        } catch (e: Exception) {
                            XposedBridge.log("onTrackInfoChanged hook error: ${e.message}")
                            param.result = false
                        }
                    }
                }
            )
        } catch (e: Exception) {
            XposedBridge.log("Failed to hook onTrackInfoChanged: ${e.message}")
        }
    }

    // ==========================================
    // ALBUM ART HELPERS
    // ==========================================

    private fun toCircleBitmap(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val size = minOf(src.width, src.height)
        val xOffset = (src.width - size) / 2
        val yOffset = (src.height - size) / 2
        val squared = android.graphics.Bitmap.createBitmap(src, xOffset, yOffset, size, size)
        val output = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        val paint = android.graphics.Paint().apply { isAntiAlias = true; isFilterBitmap = true }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(squared, 0f, 0f, paint)
        if (squared != src) squared.recycle()
        return output
    }

    private fun scaleBitmap(src: android.graphics.Bitmap, targetSize: Int): android.graphics.Bitmap {
        if (src.width == targetSize && src.height == targetSize) return src
        return android.graphics.Bitmap.createScaledBitmap(src, targetSize, targetSize, true)
    }

    private fun drawModernOrbitals(
        canvas: android.graphics.Canvas,
        speedEffect: Float,
        classLoader: ClassLoader
    ) {
        val now = System.currentTimeMillis()
        val dt = ((now - lastDrawTime) / 1000f).coerceIn(0f, 0.05f)
        lastDrawTime = now

        val baseSpeed = 0.8f + speedEffect * 1.2f
        orbitalPhase1 += dt * baseSpeed * 1.0f
        orbitalPhase2 += dt * baseSpeed * 1.3f
        orbitalPhase3 += dt * baseSpeed * 0.7f

        val cx = canvas.width / 2f
        val cy = canvas.height / 2f
        val radii = floatArrayOf(100f, 112f, 124f)
        val phases = floatArrayOf(orbitalPhase1, orbitalPhase2, orbitalPhase3)
        val arcLengths = floatArrayOf(120f, 90f, 60f)
        val strokeWidths = floatArrayOf(2.5f, 2f, 1.5f)
        val colors = getOrbitalColors()

        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
        }

        for (i in 0..2) {
            val r = radii[i]
            val phase = phases[i]
            val arcLen = arcLengths[i]
            val color = colors[i]

            paint.strokeWidth = strokeWidths[i] * 3f
            paint.color = color and 0x00FFFFFF or 0x30000000
            val rectOuter = android.graphics.RectF(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(rectOuter, Math.toDegrees(phase.toDouble()).toFloat(), arcLen, false, paint)

            paint.strokeWidth = strokeWidths[i]
            paint.color = color or 0xFF000000.toInt()
            canvas.drawArc(rectOuter, Math.toDegrees(phase.toDouble()).toFloat(), arcLen, false, paint)

            val dotX = cx + r * Math.cos(phase.toDouble()).toFloat()
            val dotY = cy + r * Math.sin(phase.toDouble()).toFloat()
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(dotX, dotY, strokeWidths[i] * 2f, paint)
            paint.style = android.graphics.Paint.Style.STROKE
        }
    }

    private fun getOrbitalColors(): IntArray {
        return when (myCustomRoad?.renderer?.colorMode?.toInt() ?: 0) {
            0 -> intArrayOf(0xFF00FFFF.toInt(), 0xFFFF0066.toInt(), 0xFF8800FF.toInt())
            1 -> intArrayOf(0xFF00FF44.toInt(), 0xFF00AAFF.toInt(), 0xFFFFFF00.toInt())
            2 -> intArrayOf(0xFFFF2200.toInt(), 0xFFFF6600.toInt(), 0xFF880011.toInt())
            3 -> intArrayOf(0xFF00FFD0.toInt(), 0xFF0066FF.toInt(), 0xFF00AAFF.toInt())
            4 -> intArrayOf(0xFFFFCC00.toInt(), 0xFFFF8800.toInt(), 0xFFFFFFAA.toInt())
            5 -> intArrayOf(0xFF8800FF.toInt(), 0xFF0088FF.toInt(), 0xFF6600CC.toInt())
            6 -> {
                // Uses 3D SCENE ranges
                val startHue = myCustomRoad?.renderer?.rainbowStart ?: 0f
                val endHue = myCustomRoad?.renderer?.rainbowEnd ?: 360f

                val baseT = ((System.currentTimeMillis() / 30f) % 360f) / 360f

                fun getHsv(offset: Float): Int {
                    val t = (baseT + offset) % 1f
                    var h = startHue + (endHue - startHue) * t
                    h %= 360f
                    if (h < 0) h += 360f
                    return android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, 1f))
                }

                intArrayOf(getHsv(0f), getHsv(0.333f), getHsv(0.666f))
            }
            7 -> {
                val aurora = myCustomRoad?.renderer?.auroraColors
                intArrayOf(
                    if (aurora != null) android.graphics.Color.rgb((aurora[0][0]*255).toInt(), (aurora[0][1]*255).toInt(), (aurora[0][2]*255).toInt()) else 0xFF00FF88.toInt(),
                    if (aurora != null) android.graphics.Color.rgb((aurora[2][0]*255).toInt(), (aurora[2][1]*255).toInt(), (aurora[2][2]*255).toInt()) else 0xFF0088FF.toInt(),
                    if (aurora != null) android.graphics.Color.rgb((aurora[4][0]*255).toInt(), (aurora[4][1]*255).toInt(), (aurora[4][2]*255).toInt()) else 0xFF8800FF.toInt()
                )
            }
            else -> intArrayOf(0xFF00FFFF.toInt(), 0xFFFF0066.toInt(), 0xFF8800FF.toInt())
        }
    }

    private fun injectFakeTrack(host: Any, classLoader: ClassLoader) {
        mainHandler.post {
            try {
                val titleView = XposedHelpers.getObjectField(host, "m_id3_title")
                val subtitleView = XposedHelpers.getObjectField(host, "m_id3_sub_title")
                val id3View = XposedHelpers.getObjectField(host, "m_my_id3_view")
                val btnPlay = XposedHelpers.getObjectField(host, "m_bn_play")
                val drawableClass = XposedHelpers.findClass("com.spd.home.R\$drawable", classLoader)
                val rPause = XposedHelpers.getStaticIntField(drawableClass, "icon_music_pause")
                XposedHelpers.callMethod(titleView, "setText", "Hello")
                XposedHelpers.callMethod(subtitleView, "setText", "Adele")
                XposedHelpers.callMethod(btnPlay, "setIconDrawableResource", rPause)
                XposedHelpers.callMethod(id3View, "setSpeedEffect", 1.0f)
                XposedHelpers.callMethod(id3View, "setUserAlbum", null as android.graphics.Bitmap?)
                fetchAlbumArt("Hello", "Adele", 600) { bitmap ->
                    bitmap?.let {
                        val scaled = scaleBitmap(it, 140)
                        val circle = toCircleBitmap(scaled)
                        if (scaled != it) it.recycle()
                        XposedHelpers.callMethod(id3View, "setUserAlbum", circle)
                    }
                }
            } catch (e: Throwable) {
                XposedBridge.log("injectFakeTrack failed: ${e.message}")
            }
        }
    }

    private fun albumCacheKey(title: String, artist: String): String {
        val raw = "$artist-$title"
        return raw.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(100)
    }

    private fun loadFromDiskCache(key: String): android.graphics.Bitmap? {
        val file = File(diskCacheDir, "$key.png")
        if (!file.exists()) return null
        return try { BitmapFactory.decodeFile(file.absolutePath) } catch (e: Exception) { null }
    }

    private fun saveToDiskCache(key: String, bitmap: android.graphics.Bitmap) {
        try {
            diskCacheDir.mkdirs()
            val file = File(diskCacheDir, "$key.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: Exception) {
            XposedBridge.log("saveToDiskCache failed: ${e.message}")
        }
    }

    private fun fetchAlbumArt(
        title: String,
        artist: String,
        targetSize: Int = 280,
        onResult: (android.graphics.Bitmap?) -> Unit
    ) {
        val key = albumCacheKey(title, artist)
        if (albumArtCache.containsKey(key)) { onResult(albumArtCache[key]); return }
        val diskBitmap = loadFromDiskCache(key)
        if (diskBitmap != null) { albumArtCache[key] = diskBitmap; onResult(diskBitmap); return }
        Thread {
            val bitmap = fetchFromItunes(title, artist, targetSize) ?: fetchFromLastFm(title, artist)
            if (bitmap != null) { albumArtCache[key] = bitmap; saveToDiskCache(key, bitmap) }
            else albumArtCache[key] = null
            mainHandler.post { onResult(bitmap) }
        }.start()
    }

    private fun fetchFromItunes(title: String, artist: String, targetSize: Int = 280): android.graphics.Bitmap? {
        return try {
            val query = URLEncoder.encode("$artist $title", "UTF-8")
            val url = "https://itunes.apple.com/search?term=$query&media=music&limit=5&entity=song"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000; conn.readTimeout = 6000
            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val imageUrl = extractItunesImageUrl(response, targetSize) ?: return null
            val imgConn = URL(imageUrl).openConnection() as HttpURLConnection
            imgConn.connectTimeout = 6000; imgConn.readTimeout = 6000
            val bitmap = BitmapFactory.decodeStream(imgConn.inputStream)
            imgConn.disconnect()
            bitmap
        } catch (e: Exception) { XposedBridge.log("iTunes fetch failed: ${e.message}"); null }
    }

    private fun extractItunesImageUrl(json: String, targetSize: Int = 280): String? {
        val marker = "\"artworkUrl100\":\""
        val idx = json.indexOf(marker)
        if (idx == -1) return null
        val start = idx + marker.length
        val end = json.indexOf("\"", start)
        if (end <= start) return null
        return json.substring(start, end).replace("/100x100bb.jpg", "/${targetSize}x${targetSize}bb.jpg")
    }

    private fun fetchFromLastFm(title: String, artist: String): android.graphics.Bitmap? {
        return try {
            val encodedArtist = URLEncoder.encode(artist, "UTF-8")
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val apiUrl = "https://ws.audioscrobbler.com/2.0/?method=track.getInfo&api_key=$LASTFM_API_KEY&artist=$encodedArtist&track=$encodedTitle&format=json"
            val conn = URL(apiUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000; conn.readTimeout = 6000
            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val imageUrl = extractLastFmImageUrl(response) ?: return null
            val imgConn = URL(imageUrl).openConnection() as HttpURLConnection
            imgConn.connectTimeout = 6000; imgConn.readTimeout = 6000
            val bitmap = BitmapFactory.decodeStream(imgConn.inputStream)
            imgConn.disconnect()
            bitmap
        } catch (e: Exception) { XposedBridge.log("Last.fm fetch failed: ${e.message}"); null }
    }

    private fun extractLastFmImageUrl(json: String): String? {
        val sizes = listOf("mega", "extralarge", "large")
        for (size in sizes) {
            val idx = json.indexOf("\"$size\"")
            if (idx == -1) continue
            val textIdx = json.lastIndexOf("\"#text\":\"", idx)
            if (textIdx == -1 || idx - textIdx > 200) continue
            val start = textIdx + 9
            val end = json.indexOf("\"", start)
            if (end <= start) continue
            val url = json.substring(start, end)
            if (url.startsWith("http")) return url
        }
        return null
    }

    private fun hookMyID3View(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.spd.custom.view.MyID3View",
                lpparam.classLoader,
                "onDraw",
                android.graphics.Canvas::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!enableCustomRings) {
                                return // If disabled, let original method run.
                            }

                            val canvas = param.args[0] as android.graphics.Canvas
                            val host = param.thisObject
                            val speedEffect = XposedHelpers.getFloatField(host, "m_speed_effect")

                            canvas.setDrawFilter(android.graphics.PaintFlagsDrawFilter(0, 3))
                            val paint = android.graphics.Paint().apply { isAntiAlias = true }

                            val userAlbum = XposedHelpers.getObjectField(host, "m_album_user") as? android.graphics.Bitmap
                            val defaultAlbum = XposedHelpers.getObjectField(host, "m_album_default") as? android.graphics.Bitmap
                            val mask = XposedHelpers.getObjectField(host, "m_album_mask") as? android.graphics.Bitmap

                            val cx = canvas.width / 2f
                            val cy = canvas.height / 2f

                            val bmp = userAlbum ?: defaultAlbum
                            if (bmp != null && !bmp.isRecycled) {
                                val matrix = android.graphics.Matrix()
                                matrix.postTranslate((-bmp.width / 2f) + cx, (-bmp.height / 2f) + cy)
                                canvas.drawBitmap(bmp, matrix, paint)
                            }

                            if (mask != null && !mask.isRecycled) {
                                val matrix = android.graphics.Matrix()
                                matrix.postTranslate((-mask.width / 2f) + cx, (-mask.height / 2f) + cy)
                                canvas.drawBitmap(mask, matrix, paint)
                            }

                            drawRingStyle(canvas, speedEffect)

                            val handler = XposedHelpers.getObjectField(host, "m_refresh_handler") as? android.os.Handler
                            if (handler != null && !handler.hasMessages(0)) {
                                handler.sendEmptyMessageDelayed(0, 16L)
                            }

                            param.result = null
                        } catch (e: Exception) {
                            XposedBridge.log("MyID3View onDraw hook error: ${e.message}")
                        }
                    }
                }
            )
        } catch (e: Exception) {
            XposedBridge.log("Failed to hook MyID3View onDraw: ${e.message}")
        }
    }

    private fun drawRingStyle(canvas: android.graphics.Canvas, speedEffect: Float) {
        val now = System.currentTimeMillis()
        val dt = ((now - lastRingTime) / 1000f).coerceIn(0f, 0.05f)
        lastRingTime = now
        ringPhase += dt * speedEffect * 2f

        when (ringStyle) {
            0 -> drawPulsingRings(canvas, speedEffect)
            1 -> drawDNAHelix(canvas, speedEffect)
            2 -> drawSoundWaveBars(canvas, speedEffect)
            3 -> drawMinimalRings(canvas, speedEffect)
            4 -> drawPlasmaRings(canvas, speedEffect)
            5 -> drawNeonScanLines(canvas, speedEffect)
            6 -> drawColorfulOriginal(canvas, speedEffect)
            7 -> drawTripleDNA(canvas, speedEffect)
            8 -> drawDNANodes(canvas, speedEffect)
            9 -> drawRadarSweep(canvas, speedEffect)
        }
    }

    private fun getColors(): IntArray {
        return when (myCustomRoad?.renderer?.colorMode?.toInt() ?: 0) {
            0 -> intArrayOf(0xFF00FFFF.toInt(), 0xFFFF0066.toInt(), 0xFF8800FF.toInt())
            1 -> intArrayOf(0xFF00FF44.toInt(), 0xFF00AAFF.toInt(), 0xFFFFFF00.toInt())
            2 -> intArrayOf(0xFFFF2200.toInt(), 0xFFFF6600.toInt(), 0xFF880011.toInt())
            3 -> intArrayOf(0xFF00FFD0.toInt(), 0xFF0066FF.toInt(), 0xFF00AAFF.toInt())
            4 -> intArrayOf(0xFFFFCC00.toInt(), 0xFFFF8800.toInt(), 0xFFFFFFAA.toInt())
            5 -> intArrayOf(0xFF8800FF.toInt(), 0xFF0088FF.toInt(), 0xFF6600CC.toInt())
            6 -> {
                // Uses custom RING ranges
                val startHue = ringRainbowStart
                val endHue = ringRainbowEnd

                val baseT = ((System.currentTimeMillis() / 30f) % 360f) / 360f

                fun getHsv(offset: Float): Int {
                    val t = (baseT + offset) % 1f
                    var h = startHue + (endHue - startHue) * t
                    h %= 360f
                    if (h < 0) h += 360f
                    return android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, 1f))
                }

                intArrayOf(getHsv(0f), getHsv(0.333f), getHsv(0.666f))
            }
            7 -> {
                val aurora = myCustomRoad?.renderer?.auroraColors
                intArrayOf(
                    if (aurora != null) android.graphics.Color.rgb((aurora[0][0]*255).toInt(), (aurora[0][1]*255).toInt(), (aurora[0][2]*255).toInt()) else 0xFF00FF88.toInt(),
                    if (aurora != null) android.graphics.Color.rgb((aurora[2][0]*255).toInt(), (aurora[2][1]*255).toInt(), (aurora[2][2]*255).toInt()) else 0xFF0088FF.toInt(),
                    if (aurora != null) android.graphics.Color.rgb((aurora[4][0]*255).toInt(), (aurora[4][1]*255).toInt(), (aurora[4][2]*255).toInt()) else 0xFF8800FF.toInt()
                )
            }
            else -> intArrayOf(0xFF00FFFF.toInt(), 0xFFFF0066.toInt(), 0xFF8800FF.toInt())
        }
    }

    private fun drawPulsingRings(canvas: android.graphics.Canvas, speedEffect: Float) {
        val cx = canvas.width / 2f
        val cy = canvas.height / 2f
        val colors = getColors()
        val radii = floatArrayOf(100f, 112f, 124f)
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
        }

        for (i in 0..2) {
            val pulse = (Math.sin((ringPhase + i * 1.2f).toDouble()) * 0.5 + 0.5).toFloat()
            val alpha = (80 + pulse * 175).toInt()
            val strokeW = 1.5f + pulse * 3.5f
            val r = radii[i] + pulse * 4f

            paint.color = (colors[i] and 0x00FFFFFF) or ((alpha / 3) shl 24)
            paint.strokeWidth = strokeW * 3f
            canvas.drawCircle(cx, cy, r, paint)

            paint.color = (colors[i] and 0x00FFFFFF) or (alpha shl 24)
            paint.strokeWidth = strokeW
            canvas.drawCircle(cx, cy, r, paint)
        }
    }

    private fun drawDNAHelix(canvas: android.graphics.Canvas, speedEffect: Float) {
        val cx = canvas.width / 2f
        val cy = canvas.height / 2f
        val colors = getColors()
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
        }

        val baseR = 112f
        val helixAmp = 12f
        val rungs = 18
        val segments = 360
        val helixFreq = 6f

        for (strand in 0..1) {
            val strandOffset = if (strand == 0) 0f else Math.PI.toFloat()
            val color = if (strand == 0) colors[0] else colors[1]
            val path = android.graphics.Path()

            for (s in 0..segments) {
                val angle = (s.toFloat() / segments) * Math.PI.toFloat() * 2f
                val helixPhase = angle * helixFreq + ringPhase * 0.5f + strandOffset
                val r = baseR + Math.sin(helixPhase.toDouble()).toFloat() * helixAmp
                val px = cx + r * Math.cos(angle.toDouble()).toFloat()
                val py = cy + r * Math.sin(angle.toDouble()).toFloat()
                if (s == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()

            paint.strokeWidth = 4f
            paint.color = (color and 0x00FFFFFF) or (50 shl 24)
            canvas.drawPath(path, paint)

            paint.strokeWidth = 1.5f
            paint.color = (color and 0x00FFFFFF) or (220 shl 24)
            canvas.drawPath(path, paint)
        }

        for (r in 0 until rungs) {
            val angle = (r.toFloat() / rungs) * Math.PI.toFloat() * 2f
            val helixPhase0 = angle * helixFreq + ringPhase * 0.5f
            val helixPhase1 = helixPhase0 + Math.PI.toFloat()
            val r0 = baseR + Math.sin(helixPhase0.toDouble()).toFloat() * helixAmp
            val r1 = baseR + Math.sin(helixPhase1.toDouble()).toFloat() * helixAmp
            val x0 = cx + r0 * Math.cos(angle.toDouble()).toFloat()
            val y0 = cy + r0 * Math.sin(angle.toDouble()).toFloat()
            val x1 = cx + r1 * Math.cos(angle.toDouble()).toFloat()
            val y1 = cy + r1 * Math.sin(angle.toDouble()).toFloat()

            val t = r.toFloat() / rungs
            val rungColor = blendColors(colors[0], colors[1], t)

            paint.strokeWidth = 3f
            paint.color = (rungColor and 0x00FFFFFF) or (40 shl 24)
            canvas.drawLine(x0, y0, x1, y1, paint)

            paint.strokeWidth = 1f
            paint.color = (rungColor and 0x00FFFFFF) or (180 shl 24)
            canvas.drawLine(x0, y0, x1, y1, paint)

            paint.style = android.graphics.Paint.Style.FILL
            paint.color = colors[0]
            canvas.drawCircle(x0, y0, 2.5f, paint)
            paint.color = colors[1]
            canvas.drawCircle(x1, y1, 2.5f, paint)
            paint.style = android.graphics.Paint.Style.STROKE
        }
    }

    private fun drawTripleDNA(canvas: android.graphics.Canvas, speedEffect: Float) {
        val cx = canvas.width / 2f
        val cy = canvas.height / 2f
        val colors = getColors()
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
        }

        val baseR = 112f
        val helixAmp = 10f
        val helixFreq = 5f
        val segments = 360
        val rungs = 15
        val strandOffsets = floatArrayOf(0f, Math.PI.toFloat() * 2f / 3f, Math.PI.toFloat() * 4f / 3f)

        for (strand in 0..2) {
            val strandOffset = strandOffsets[strand]
            val color = colors[strand]
            val path = android.graphics.Path()

            for (s in 0..segments) {
                val angle = (s.toFloat() / segments) * Math.PI.toFloat() * 2f
                val helixPhase = angle * helixFreq + ringPhase * 0.4f + strandOffset
                val r = baseR + Math.sin(helixPhase.toDouble()).toFloat() * helixAmp
                val px = cx + r * Math.cos(angle.toDouble()).toFloat()
                val py = cy + r * Math.sin(angle.toDouble()).toFloat()
                if (s == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()

            paint.strokeWidth = 4f
            paint.color = (color and 0x00FFFFFF) or (40 shl 24)
            canvas.drawPath(path, paint)

            paint.strokeWidth = 1.2f
            paint.color = (color and 0x00FFFFFF) or (210 shl 24)
            canvas.drawPath(path, paint)
        }

        for (r in 0 until rungs) {
            val angle = (r.toFloat() / rungs) * Math.PI.toFloat() * 2f
            val points = strandOffsets.mapIndexed { _, offset ->
                val helixPhase = angle * helixFreq + ringPhase * 0.4f + offset
                val radius = baseR + Math.sin(helixPhase.toDouble()).toFloat() * helixAmp
                Pair(
                    cx + radius * Math.cos(angle.toDouble()).toFloat(),
                    cy + radius * Math.sin(angle.toDouble()).toFloat()
                )
            }

            for (s in 0..1) {
                val (x0, y0) = points[s]
                val (x1, y1) = points[s + 1]
                val rungColor = blendColors(colors[s], colors[s + 1], 0.5f)

                paint.strokeWidth = 2.5f
                paint.color = (rungColor and 0x00FFFFFF) or (35 shl 24)
                canvas.drawLine(x0, y0, x1, y1, paint)

                paint.strokeWidth = 0.8f
                paint.color = (rungColor and 0x00FFFFFF) or (160 shl 24)
                canvas.drawLine(x0, y0, x1, y1, paint)
            }

            paint.style = android.graphics.Paint.Style.FILL
            points.forEachIndexed { idx, (px, py) ->
                paint.color = colors[idx]
                canvas.drawCircle(px, py, 2f, paint)
            }
            paint.style = android.graphics.Paint.Style.STROKE
        }
    }

    private fun drawDNANodes(canvas: android.graphics.Canvas, speedEffect: Float) {
        val cx = canvas.width / 2f
        val cy = canvas.height / 2f
        val colors = getColors()
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
        }

        val baseR = 112f
        val helixAmp = 14f
        val helixFreq = 4f
        val segments = 360
        val nodeCount = 12

        for (strand in 0..1) {
            val strandOffset = if (strand == 0) 0f else Math.PI.toFloat()
            val color = colors[strand]
            val path = android.graphics.Path()

            for (s in 0..segments) {
                val angle = (s.toFloat() / segments) * Math.PI.toFloat() * 2f
                val helixPhase = angle * helixFreq + ringPhase * 0.45f + strandOffset
                val r = baseR + Math.sin(helixPhase.toDouble()).toFloat() * helixAmp
                val px = cx + r * Math.cos(angle.toDouble()).toFloat()
                val py = cy + r * Math.sin(angle.toDouble()).toFloat()
                if (s == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()

            paint.strokeWidth = 5f
            paint.color = (color and 0x00FFFFFF) or (35 shl 24)
            canvas.drawPath(path, paint)

            paint.strokeWidth = 1.5f
            paint.color = (color and 0x00FFFFFF) or (200 shl 24)
            canvas.drawPath(path, paint)
        }

        for (n in 0 until nodeCount) {
            val angle = (n.toFloat() / nodeCount) * Math.PI.toFloat() * 2f

            for (strand in 0..1) {
                val strandOffset = if (strand == 0) 0f else Math.PI.toFloat()
                val helixPhase = angle * helixFreq + ringPhase * 0.45f + strandOffset
                val r = baseR + Math.sin(helixPhase.toDouble()).toFloat() * helixAmp
                val px = cx + r * Math.cos(angle.toDouble()).toFloat()
                val py = cy + r * Math.sin(angle.toDouble()).toFloat()
                val color = colors[strand]

                paint.style = android.graphics.Paint.Style.FILL
                paint.color = (color and 0x00FFFFFF) or (50 shl 24)
                canvas.drawCircle(px, py, 8f, paint)

                paint.color = (color and 0x00FFFFFF) or (120 shl 24)
                canvas.drawCircle(px, py, 5f, paint)

                paint.color = 0xFFFFFFFF.toInt()
                canvas.drawCircle(px, py, 2f, paint)
                paint.style = android.graphics.Paint.Style.STROKE
            }

            val hp0 = angle * helixFreq + ringPhase * 0.45f
            val hp1 = hp0 + Math.PI.toFloat()
            val r0 = baseR + Math.sin(hp0.toDouble()).toFloat() * helixAmp
            val r1 = baseR + Math.sin(hp1.toDouble()).toFloat() * helixAmp
            val x0 = cx + r0 * Math.cos(angle.toDouble()).toFloat()
            val y0 = cy + r0 * Math.sin(angle.toDouble()).toFloat()
            val x1 = cx + r1 * Math.cos(angle.toDouble()).toFloat()
            val y1 = cy + r1 * Math.sin(angle.toDouble()).toFloat()

            val rungColor = blendColors(colors[0], colors[1], 0.5f)
            paint.strokeWidth = 2f
            paint.color = (rungColor and 0x00FFFFFF) or (30 shl 24)
            canvas.drawLine(x0, y0, x1, y1, paint)
            paint.strokeWidth = 0.8f
            paint.color = (rungColor and 0x00FFFFFF) or (140 shl 24)
            canvas.drawLine(x0, y0, x1, y1, paint)
        }
    }

    private fun drawSoundWaveBars(canvas: android.graphics.Canvas, speedEffect: Float) {
        val cx = canvas.width / 2f
        val cy = canvas.height / 2f
        val colors = getColors()
        val baseRadius = 91f
        val barCount = 32
        val paint = android.graphics.Paint().apply { isAntiAlias = true; strokeCap = android.graphics.Paint.Cap.ROUND }

        for (i in 0 until barCount) {
            val angle = (i.toFloat() / barCount) * Math.PI.toFloat() * 2f
            val wave1 = Math.sin((ringPhase * 2f + i * 0.4f).toDouble()).toFloat()
            val wave2 = Math.sin((ringPhase * 1.3f + i * 0.7f).toDouble()).toFloat()
            val barHeight = (wave1 * 0.6f + wave2 * 0.4f) * 0.5f + 0.5f
            val barLen = 4f + barHeight * 16f

            val x1 = cx + baseRadius * Math.cos(angle.toDouble()).toFloat()
            val y1 = cy + baseRadius * Math.sin(angle.toDouble()).toFloat()
            val x2 = cx + (baseRadius + barLen) * Math.cos(angle.toDouble()).toFloat()
            val y2 = cy + (baseRadius + barLen) * Math.sin(angle.toDouble()).toFloat()

            val colorIndex = (i.toFloat() / barCount * 3).toInt().coerceIn(0, 2)
            val alpha = (150 + barHeight * 105).toInt()
            paint.color = (colors[colorIndex] and 0x00FFFFFF) or (alpha shl 24)
            paint.strokeWidth = 2.5f
            canvas.drawLine(x1, y1, x2, y2, paint)
        }
    }

    private fun drawMinimalRings(canvas: android.graphics.Canvas, speedEffect: Float) {
        val cx = canvas.width / 2f
        val cy = canvas.height / 2f
        val colors = getColors()
        val radii = floatArrayOf(100f, 112f, 124f)

        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3f
            strokeCap = android.graphics.Paint.Cap.ROUND
        }

        for (i in 0..2) {
            val r = radii[i]
            val rect = android.graphics.RectF(cx - r, cy - r, cx + r, cy + r)
            val speed = (i + 1) * 0.3f
            val startAngle = (ringPhase * speed * (180f / Math.PI.toFloat())) % 360f

            paint.color = (colors[i] and 0x00FFFFFF) or (30 shl 24)
            paint.strokeWidth = 3f
            canvas.drawCircle(cx, cy, r, paint)

            val arcLength = if (speedEffect > 0.5f) 300f else 120f
            paint.color = colors[i]
            paint.strokeWidth = 3f
            canvas.drawArc(rect, startAngle, arcLength, false, paint)

            val endAngle = Math.toRadians((startAngle + arcLength).toDouble())
            val dotX = cx + r * Math.cos(endAngle).toFloat()
            val dotY = cy + r * Math.sin(endAngle).toFloat()
            paint.style = android.graphics.Paint.Style.FILL
            canvas.drawCircle(dotX, dotY, 3f, paint)
            paint.style = android.graphics.Paint.Style.STROKE
        }
    }

    private fun drawPlasmaRings(canvas: android.graphics.Canvas, speedEffect: Float) {
        val cx = canvas.width / 2f
        val cy = canvas.height / 2f
        val colors = getColors()
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
        }

        val layerCount = 6
        val baseRadii = floatArrayOf(100f, 112f, 124f)

        for (ring in 0..2) {
            val baseR = baseRadii[ring]
            val color = colors[ring]

            for (layer in 0 until layerCount) {
                val t = layer.toFloat() / layerCount
                val layerPhase = ringPhase * (0.5f + t * 0.8f) + layer * 0.4f
                val segments = 120

                val alpha = (20 + (1f - t) * 160).toInt()
                val strokeW = 0.8f + (1f - t) * 2f

                paint.strokeWidth = strokeW
                paint.color = (color and 0x00FFFFFF) or (alpha shl 24)

                val path = android.graphics.Path()
                for (s in 0..segments) {
                    val angle = (s.toFloat() / segments) * Math.PI.toFloat() * 2f
                    val interference1 = Math.sin((angle * 5f + layerPhase).toDouble()).toFloat()
                    val interference2 = Math.sin((angle * 3f - layerPhase * 0.7f).toDouble()).toFloat()
                    val r = baseR + interference1 * 5f + interference2 * 3f
                    val px = cx + r * Math.cos(angle.toDouble()).toFloat()
                    val py = cy + r * Math.sin(angle.toDouble()).toFloat()
                    if (s == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                canvas.drawPath(path, paint)
            }
        }
    }

    private fun drawNeonScanLines(canvas: android.graphics.Canvas, speedEffect: Float) {
        val cx = canvas.width / 2f
        val cy = canvas.height / 2f
        val colors = getColors()
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
        }

        val radii = floatArrayOf(100f, 112f, 124f)
        val tickCount = 48

        for (ring in 0..2) {
            val r = radii[ring]
            val color = colors[ring]
            val scanPos = (ringPhase * 0.5f + ring * 2.1f) % (Math.PI.toFloat() * 2f)

            paint.strokeWidth = 1f
            paint.color = (color and 0x00FFFFFF) or (35 shl 24)
            canvas.drawCircle(cx, cy, r, paint)

            for (i in 0 until tickCount) {
                val angle = (i.toFloat() / tickCount) * Math.PI.toFloat() * 2f
                val distFromScan = Math.abs(angle - scanPos).let { minOf(it, Math.PI.toFloat() * 2f - it) }
                val glow = (1f - (distFromScan / (Math.PI.toFloat() * 0.4f)).coerceIn(0f, 1f))
                val isLong = i % 4 == 0
                val tickLen = if (isLong) 8f else 4f
                val alpha = (30 + glow * 225).toInt()
                val sw = if (isLong) 1.5f else 0.8f

                paint.strokeWidth = sw
                paint.color = (color and 0x00FFFFFF) or (alpha shl 24)

                val x1 = cx + r * Math.cos(angle.toDouble()).toFloat()
                val y1 = cy + r * Math.sin(angle.toDouble()).toFloat()
                val x2 = cx + (r + tickLen) * Math.cos(angle.toDouble()).toFloat()
                val y2 = cy + (r + tickLen) * Math.sin(angle.toDouble()).toFloat()
                canvas.drawLine(x1, y1, x2, y2, paint)
            }

            val rect = android.graphics.RectF(cx - r, cy - r, cx + r, cy + r)
            paint.strokeWidth = 2f
            paint.color = (color and 0x00FFFFFF) or (180 shl 24)
            canvas.drawArc(rect, Math.toDegrees(scanPos.toDouble()).toFloat() - 20f, 40f, false, paint)
        }
    }

    private fun drawColorfulOriginal(canvas: android.graphics.Canvas, speedEffect: Float) {
        val cx = canvas.width / 2f
        val cy = canvas.height / 2f
        val colors = getColors()

        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1f
            alpha = 90
        }

        val xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
        paint.xfermode = xfermode

        data class CircleDef(val r0: Float, val r1: Float, val phaseSpeed: Float, val initPhase: Float)
        val circles = listOf(
            CircleDef(5f,  111f, 0.016f, 0f),
            CircleDef(6f,  127f, 0.014f, 1.7f),
            CircleDef(8f,  131f, 0.021f, 0.2f),
            CircleDef(3f,  109f, 0.030f, 0.3f),
            CircleDef(10f, 115f, 0.012f, 0.7f),
            CircleDef(5f,  106f, 0.017f, 1.2f),
            CircleDef(7f,  112f, 0.013f, 1.4f)
        )

        circles.forEachIndexed { idx, c ->
            val phase = c.initPhase + ringPhase * c.phaseSpeed * 60f
            val orbitX = cx + c.r0 * Math.cos(phase.toDouble()).toFloat()
            val orbitY = cy + c.r0 * Math.sin(phase.toDouble()).toFloat()

            val hue = (idx.toFloat() / circles.size) * 360f
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(colors[idx % 3], hsv)
            hsv[0] = (hsv[0] + hue) % 360f
            paint.color = android.graphics.Color.HSVToColor(hsv)

            canvas.drawCircle(orbitX, orbitY, c.r1, paint)
        }

        paint.xfermode = null
    }

    private fun drawRadarSweep(canvas: android.graphics.Canvas, speedEffect: Float) {
        val cx = canvas.width / 2f
        val cy = canvas.height / 2f
        val colors = getColors()
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
        }

        val outerR = 126f
        val innerR = 98f

        listOf(innerR, outerR).forEachIndexed { i, r ->
            paint.strokeWidth = 0.8f
            paint.color = (colors[i] and 0x00FFFFFF) or (30 shl 24)
            canvas.drawCircle(cx, cy, r, paint)
        }

        paint.strokeWidth = 0.5f
        paint.color = (colors[2] and 0x00FFFFFF) or (20 shl 24)
        for (i in 0..3) {
            val a = i * Math.PI.toFloat() / 4f
            canvas.drawLine(
                cx + innerR * Math.cos(a.toDouble()).toFloat(),
                cy + innerR * Math.sin(a.toDouble()).toFloat(),
                cx + outerR * Math.cos(a.toDouble()).toFloat(),
                cy + outerR * Math.sin(a.toDouble()).toFloat(),
                paint
            )
        }

        val sweepAngles = floatArrayOf(ringPhase * 0.8f, ringPhase * 0.8f + Math.PI.toFloat())

        sweepAngles.forEachIndexed { beamIdx, sweepAngle ->
            val color = colors[beamIdx % 3]
            val trailSweep = 60f
            val rect = android.graphics.RectF(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
            val startDeg = Math.toDegrees(sweepAngle.toDouble()).toFloat() - trailSweep

            for (pass in 0..8) {
                val t = pass.toFloat() / 8f
                val alpha = ((1f - t) * 120).toInt()
                paint.strokeWidth = 2f + (1f - t) * 4f
                paint.color = (color and 0x00FFFFFF) or (alpha shl 24)
                canvas.drawArc(rect, startDeg + t * trailSweep, trailSweep * (1f - t) + 2f, false, paint)
            }

            val sweepX = cx + outerR * Math.cos(sweepAngle.toDouble()).toFloat()
            val sweepY = cy + outerR * Math.sin(sweepAngle.toDouble()).toFloat()
            val innerX = cx + innerR * Math.cos(sweepAngle.toDouble()).toFloat()
            val innerY = cy + innerR * Math.sin(sweepAngle.toDouble()).toFloat()

            paint.strokeWidth = 3f
            paint.color = (color and 0x00FFFFFF) or (200 shl 24)
            canvas.drawLine(innerX, innerY, sweepX, sweepY, paint)

            paint.style = android.graphics.Paint.Style.FILL
            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(sweepX, sweepY, 3f, paint)
            paint.style = android.graphics.Paint.Style.STROKE
        }
    }

    private fun blendColors(c1: Int, c2: Int, t: Float): Int {
        val r = ((c1 shr 16 and 0xFF) * (1 - t) + (c2 shr 16 and 0xFF) * t).toInt()
        val g = ((c1 shr 8  and 0xFF) * (1 - t) + (c2 shr 8  and 0xFF) * t).toInt()
        val b = ((c1        and 0xFF) * (1 - t) + (c2        and 0xFF) * t).toInt()
        return (r shl 16) or (g shl 8) or b
    }

    // ==========================================
    // CONTROL PANELS & UI
    // ==========================================

    private fun showControlPanel(context: Context) {
        val options = arrayOf(
            "Change Pattern", "Change Color Palette", "Change Dot Size",
            "3D Scene Rainbow Range", "Ring Rainbow Range", "Aurora Colors", "Grid Density",
            "Music Ring Style", "Toggle Custom Rings: ${if (enableCustomRings) "ON" else "OFF"}",
            "Presets", "Close"
        )
        showWhiteDialog(context, "3D Scene Controller", options) { which ->
            when (which) {
                0 -> showPatternPicker(context)
                1 -> showColorPicker(context)
                2 -> showSizePicker(context)
                3 -> showRainbowPicker(context, isRing = false)
                4 -> showRainbowPicker(context, isRing = true)
                5 -> showAuroraPicker(context)
                6 -> showGridPicker(context)
                7 -> showRingStylePicker(context)
                8 -> {
                    enableCustomRings = !enableCustomRings
                    saveSettings(context)
                    Toast.makeText(context, "Custom Rings: ${if (enableCustomRings) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
                }
                9 -> showPresetsMenu(context)
            }
        }
    }

    private fun showWhiteDialog(
        context: Context,
        title: String,
        items: Array<String>,
        onClick: (Int) -> Unit
    ) {
        val listView = android.widget.ListView(context)
        val adapter = object : android.widget.ArrayAdapter<String>(
            context, android.R.layout.simple_list_item_1, items
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as android.widget.TextView
                view.setTextColor(android.graphics.Color.WHITE)
                view.textSize = 16f
                view.setPadding(48, 24, 48, 24)
                return view
            }
        }
        listView.adapter = adapter
        listView.divider = android.graphics.drawable.ColorDrawable(0x22FFFFFF)
        listView.dividerHeight = 1

        val dialog = AlertDialog.Builder(context, AlertDialog.THEME_HOLO_DARK)
            .setTitle(title)
            .setView(listView)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            onClick(position)
        }
        dialog.setOnDismissListener { saveSettings(context) }
        dialog.show()
    }

    private fun showRingStylePicker(context: Context) {
        val styles = arrayOf(
            "Pulsing Glow Rings", "DNA Double Helix", "Sound Wave Bars",
            "Minimal Activity", "Plasma Energy", "Neon Scanner",
            "Colorful Original", "Triple DNA", "DNA Nodes", "Radar Sweep"
        )
        showWhiteDialog(context, "Music Ring Style", styles) { which ->
            ringStyle = which
            Toast.makeText(context, styles[which], Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSizePicker(context: Context) {
        val seekBar = android.widget.SeekBar(context).apply {
            max = 40
            progress = ((myCustomRoad?.renderer?.dotSizeScale ?: 1.0f) * 10).toInt() - 5
        }
        val label = android.widget.TextView(context).apply {
            text = "Size: ${myCustomRoad?.renderer?.dotSizeScale ?: 1.0f}"
            setTextColor(android.graphics.Color.WHITE)
            setPadding(48, 16, 48, 0)
        }
        seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, u: Boolean) {
                val scale = (p + 5) / 10f
                myCustomRoad?.renderer?.dotSizeScale = scale
                label.text = "Size: $scale"
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })
        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(label); addView(seekBar)
        }
        AlertDialog.Builder(context, AlertDialog.THEME_HOLO_DARK)
            .setTitle("Dot Size").setView(layout).setPositiveButton("OK", null)
            .setOnDismissListener { saveSettings(context) }
            .show()
    }

    private fun showPatternPicker(context: Context) {
        val patterns = arrayOf(
            "Silk", "Liquid Metal", "Aurora Gas", "Deep Ocean",
            "Breathing", "Drift", "Mercury", "Nebula",
            "Lava", "Silk Wind", "Glass", "Deep Pulse",
            "DNA Helix", "Fiber Optic", "Sonic Scan", "Gravity Well",
            "Plasma Field", "Tidal Lock"
        )
        showWhiteDialog(context, "Select Pattern", patterns) { which ->
            myCustomRoad?.renderer?.patternMode = which.toFloat()
        }
    }

    private fun showGridPicker(context: Context) {
        val colBar = android.widget.SeekBar(context).apply {
            max = 800; progress = (myCustomRoad?.renderer?.gridCols ?: 75) - 4
        }
        val rowBar = android.widget.SeekBar(context).apply {
            max = 800; progress = (myCustomRoad?.renderer?.gridRows ?: 200) - 4
        }
        val colLabel = android.widget.TextView(context).apply {
            text = "Columns: ${colBar.progress + 4}"; setTextColor(android.graphics.Color.WHITE); setPadding(48, 16, 48, 0)
        }
        val rowLabel = android.widget.TextView(context).apply {
            text = "Rows: ${rowBar.progress + 4}"; setTextColor(android.graphics.Color.WHITE); setPadding(48, 16, 48, 0)
        }
        colBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, u: Boolean) {
                val v = p + 4; myCustomRoad?.renderer?.gridCols = v; colLabel.text = "Columns: $v"
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })
        rowBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, u: Boolean) {
                val v = p + 4; myCustomRoad?.renderer?.gridRows = v; rowLabel.text = "Rows: $v"
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })
        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(colLabel); addView(colBar); addView(rowLabel); addView(rowBar)
        }
        AlertDialog.Builder(context, AlertDialog.THEME_HOLO_DARK)
            .setTitle("Grid Density").setView(layout).setPositiveButton("OK", null)
            .setOnDismissListener { saveSettings(context) }
            .show()
    }

    private fun showColorPicker(context: Context) {
        val colors = arrayOf("Cyberpunk", "Toxic Aurora", "Blood Moon", "Deep Sea", "Gold Rush", "Midnight", "Rainbow", "Aurora")
        AlertDialog.Builder(context, AlertDialog.THEME_HOLO_DARK)
            .setTitle("Select Colors")
            .setItems(colors) { _, which -> myCustomRoad?.renderer?.colorMode = which.toFloat() }
            .setOnDismissListener { saveSettings(context) }
            .show()
    }

    private fun showRainbowPicker(context: Context, isRing: Boolean) {
        val initialStart = if (isRing) ringRainbowStart.toInt() else myCustomRoad?.renderer?.rainbowStart?.toInt() ?: 0
        val initialEnd = if (isRing) ringRainbowEnd.toInt() else myCustomRoad?.renderer?.rainbowEnd?.toInt() ?: 360

        val startBar = android.widget.SeekBar(context).apply {
            max = 360; progress = initialStart
        }
        val endBar = android.widget.SeekBar(context).apply {
            max = 360; progress = initialEnd
        }
        val startLabel = android.widget.TextView(context).apply {
            text = "Start Hue: ${startBar.progress}°"
            setTextColor(android.graphics.Color.WHITE)
            setPadding(48, 16, 48, 0)
        }
        val endLabel = android.widget.TextView(context).apply {
            text = "End Hue: ${endBar.progress}°"
            setTextColor(android.graphics.Color.WHITE)
            setPadding(48, 16, 48, 0)
        }

        startBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, u: Boolean) {
                if (isRing) ringRainbowStart = p.toFloat() else myCustomRoad?.renderer?.rainbowStart = p.toFloat()
                startLabel.text = "Start Hue: $p°"
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })

        endBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, u: Boolean) {
                if (isRing) ringRainbowEnd = p.toFloat() else myCustomRoad?.renderer?.rainbowEnd = p.toFloat()
                endLabel.text = "End Hue: $p°"
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })

        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(startLabel); addView(startBar); addView(endLabel); addView(endBar)
        }

        val title = if (isRing) "Ring Rainbow Range" else "3D Scene Rainbow Range"
        AlertDialog.Builder(context, AlertDialog.THEME_HOLO_DARK)
            .setTitle(title).setView(layout).setPositiveButton("OK", null)
            .setOnDismissListener { saveSettings(context) }
            .show()
    }

    private fun showAuroraPicker(context: Context) {
        val bandNames = arrayOf("Band 1 (Near)", "Band 2", "Band 3", "Band 4", "Band 5", "Band 6 (Far)")
        AlertDialog.Builder(context, AlertDialog.THEME_HOLO_DARK)
            .setTitle("Aurora Bands — pick a band")
            .setItems(bandNames) { _, which ->
                val current = myCustomRoad?.renderer?.auroraColors?.get(which)
                val initHue = if (current != null) {
                    val hsv = FloatArray(3)
                    android.graphics.Color.RGBToHSV((current[0]*255).toInt(), (current[1]*255).toInt(), (current[2]*255).toInt(), hsv)
                    hsv[0].toInt()
                } else 120
                val hueBar = android.widget.SeekBar(context).apply { max = 360; progress = initHue }
                val preview = android.view.View(context).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(-1, 80)
                    setBackgroundColor(android.graphics.Color.HSVToColor(floatArrayOf(initHue.toFloat(), 1f, 1f)))
                }
                hueBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, u: Boolean) {
                        val rgb = android.graphics.Color.HSVToColor(floatArrayOf(p.toFloat(), 1f, 1f))
                        preview.setBackgroundColor(rgb)
                        myCustomRoad?.renderer?.auroraColors?.get(which)?.let {
                            it[0] = android.graphics.Color.red(rgb) / 255f
                            it[1] = android.graphics.Color.green(rgb) / 255f
                            it[2] = android.graphics.Color.blue(rgb) / 255f
                        }
                    }
                    override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
                })
                val layout = android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    addView(preview); addView(hueBar)
                }
                AlertDialog.Builder(context, AlertDialog.THEME_HOLO_DARK)
                    .setTitle(bandNames[which]).setView(layout).setPositiveButton("OK", null)
                    .setOnDismissListener { saveSettings(context) }
                    .show()
            }
            .setOnDismissListener { saveSettings(context) }
            .show()
    }

    // ==========================================
    // SETTINGS & PRESETS MANAGER
    // ==========================================

    private fun getCurrentStateJson(): JSONObject {
        val json = JSONObject()
        json.put("patternMode", myCustomRoad?.renderer?.patternMode ?: 0f)
        json.put("colorMode", myCustomRoad?.renderer?.colorMode ?: 0f)
        json.put("dotSizeScale", myCustomRoad?.renderer?.dotSizeScale ?: 1.0f)
        json.put("gridCols", myCustomRoad?.renderer?.gridCols ?: 75)
        json.put("gridRows", myCustomRoad?.renderer?.gridRows ?: 200)
        json.put("rainbowStart", myCustomRoad?.renderer?.rainbowStart ?: 0f)
        json.put("rainbowEnd", myCustomRoad?.renderer?.rainbowEnd ?: 360f)

        val auroraArray = JSONArray()
        myCustomRoad?.renderer?.auroraColors?.forEach { band ->
            val bandArray = JSONArray()
            band.forEach { bandArray.put(it.toDouble()) }
            auroraArray.put(bandArray)
        }
        json.put("auroraColors", auroraArray)

        json.put("ringStyle", ringStyle)
        json.put("ringRainbowStart", ringRainbowStart)
        json.put("ringRainbowEnd", ringRainbowEnd)
        json.put("enableCustomRings", enableCustomRings)

        return json
    }

    private fun applyStateJson(json: JSONObject) {
        myCustomRoad?.renderer?.patternMode = json.optDouble("patternMode", (myCustomRoad?.renderer?.patternMode ?: 0f).toDouble()).toFloat()
        myCustomRoad?.renderer?.colorMode = json.optDouble("colorMode", (myCustomRoad?.renderer?.colorMode ?: 0f).toDouble()).toFloat()
        myCustomRoad?.renderer?.dotSizeScale = json.optDouble("dotSizeScale", (myCustomRoad?.renderer?.dotSizeScale ?: 1f).toDouble()).toFloat()
        myCustomRoad?.renderer?.gridCols = json.optInt("gridCols", myCustomRoad?.renderer?.gridCols ?: 75)
        myCustomRoad?.renderer?.gridRows = json.optInt("gridRows", myCustomRoad?.renderer?.gridRows ?: 200)
        myCustomRoad?.renderer?.rainbowStart = json.optDouble("rainbowStart", (myCustomRoad?.renderer?.rainbowStart ?: 0f).toDouble()).toFloat()
        myCustomRoad?.renderer?.rainbowEnd = json.optDouble("rainbowEnd", (myCustomRoad?.renderer?.rainbowEnd ?: 360f).toDouble()).toFloat()

        if (json.has("auroraColors")) {
            val auroraArray = json.getJSONArray("auroraColors")
            for (i in 0 until Math.min(auroraArray.length(), myCustomRoad?.renderer?.auroraColors?.size ?: 0)) {
                val bandArray = auroraArray.getJSONArray(i)
                myCustomRoad?.renderer?.auroraColors?.get(i)?.let { band ->
                    if (bandArray.length() >= 3) {
                        band[0] = bandArray.getDouble(0).toFloat()
                        band[1] = bandArray.getDouble(1).toFloat()
                        band[2] = bandArray.getDouble(2).toFloat()
                    }
                }
            }
        }

        ringStyle = json.optInt("ringStyle", ringStyle)
        ringRainbowStart = json.optDouble("ringRainbowStart", ringRainbowStart.toDouble()).toFloat()
        ringRainbowEnd = json.optDouble("ringRainbowEnd", ringRainbowEnd.toDouble()).toFloat()
        enableCustomRings = json.optBoolean("enableCustomRings", enableCustomRings)
    }

    private fun saveSettings(context: Context) {
        try {
            val prefs = context.getSharedPreferences("HeadUnitHookSettings", Context.MODE_PRIVATE)
            prefs.edit().putString("current_settings", getCurrentStateJson().toString()).apply()
        } catch (e: Exception) {
            XposedBridge.log("Failed to save settings: ${e.message}")
        }
    }

    private fun loadSettings(context: Context) {
        try {
            val prefs = context.getSharedPreferences("HeadUnitHookSettings", Context.MODE_PRIVATE)
            val jsonStr = prefs.getString("current_settings", null)
            if (jsonStr != null) {
                applyStateJson(JSONObject(jsonStr))
            }
        } catch (e: Exception) {
            XposedBridge.log("Failed to load settings: ${e.message}")
        }
    }

    private fun showPresetsMenu(context: Context) {
        val options = arrayOf("Save Current as Preset", "Load Preset", "Delete Preset")
        showWhiteDialog(context, "Presets Manager", options) { which ->
            when (which) {
                0 -> promptSavePreset(context)
                1 -> showLoadPreset(context)
                2 -> showDeletePreset(context)
            }
        }
    }

    private fun promptSavePreset(context: Context) {
        val input = android.widget.EditText(context).apply {
            setTextColor(android.graphics.Color.WHITE)
            hint = "Preset Name"
            setHintTextColor(0x88FFFFFF.toInt())
            setPadding(48, 48, 48, 48)
        }
        AlertDialog.Builder(context, AlertDialog.THEME_HOLO_DARK)
            .setTitle("Save Preset")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val prefs = context.getSharedPreferences("HeadUnitHookSettings", Context.MODE_PRIVATE)
                    val presets = prefs.getStringSet("presets_list", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                    presets.add(name)
                    prefs.edit()
                        .putStringSet("presets_list", presets)
                        .putString("preset_$name", getCurrentStateJson().toString())
                        .apply()
                    Toast.makeText(context, "Saved preset: $name", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLoadPreset(context: Context) {
        val prefs = context.getSharedPreferences("HeadUnitHookSettings", Context.MODE_PRIVATE)
        val presets = prefs.getStringSet("presets_list", mutableSetOf())?.toList() ?: emptyList()
        if (presets.isEmpty()) {
            Toast.makeText(context, "No saved presets found.", Toast.LENGTH_SHORT).show()
            return
        }
        showWhiteDialog(context, "Load Preset", presets.toTypedArray()) { which ->
            val name = presets[which]
            val jsonStr = prefs.getString("preset_$name", null)
            if (jsonStr != null) {
                applyStateJson(JSONObject(jsonStr))
                saveSettings(context) // Save as the new active state
                Toast.makeText(context, "Loaded: $name", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showDeletePreset(context: Context) {
        val prefs = context.getSharedPreferences("HeadUnitHookSettings", Context.MODE_PRIVATE)
        val presets = prefs.getStringSet("presets_list", mutableSetOf())?.toList() ?: emptyList()
        if (presets.isEmpty()) {
            Toast.makeText(context, "No saved presets found.", Toast.LENGTH_SHORT).show()
            return
        }
        showWhiteDialog(context, "Delete Preset", presets.toTypedArray()) { which ->
            val name = presets[which]
            val newSet = prefs.getStringSet("presets_list", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
            newSet.remove(name)
            prefs.edit()
                .putStringSet("presets_list", newSet)
                .remove("preset_$name")
                .apply()
            Toast.makeText(context, "Deleted: $name", Toast.LENGTH_SHORT).show()
        }
    }
}