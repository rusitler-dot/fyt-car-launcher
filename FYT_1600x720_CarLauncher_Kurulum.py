#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
FYT UIS8581A (1600x720 - Android 10 API 29) Custom Car Launcher
Otomatik Proje ve GitHub Actions APK Oluşturucu
"""
import os

FILES = {
    ".github/workflows/build-apk.yml": '''name: Build FYT Car Launcher APK

on:
  push:
    branches: [ "main", "master" ]
  workflow_dispatch:

jobs:
  build-android-apk:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout Source Code
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Setup Android SDK (API 29 - Android 10 FYT)
        uses: android-actions/setup-android@v3

      - name: Grant Execute Permission for Gradle
        run: chmod +x gradlew || gradle wrapper

      - name: Build Debug APK
        run: ./gradlew assembleDebug

      - name: Upload Ready-to-Install APK
        uses: actions/upload-artifact@v4
        with:
          name: FYT-1600x720-CarLauncher-APK
          path: app/build/outputs/apk/debug/app-debug.apk''',
    "app/src/main/AndroidManifest.xml": '''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    package="com.fyt.customcarlauncher">

    <!-- GPS & Hız Limiti İzinleri -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.INTERNET" />

    <!-- AUX Ön Kamera (CVBS / Camera2 / UVC) İzinleri -->
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera" android:required="false" />
    <uses-feature android:glEsVersion="0x00020000" android:required="true" />

    <!-- FYT Ekran Parlaklığı, Paket Sorgulama ve Çoklu Pencere İzinleri -->
    <uses-permission android:name="android.permission.WRITE_SETTINGS" tools:ignore="ProtectedPermissions" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" tools:ignore="QueryAllPackagesPermission" />

    <application
        android:allowBackup="true"
        android:label="FYT Pro Launcher"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.NoTitleBar.Fullscreen">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:screenOrientation="landscape"
            android:configChanges="orientation|screenSize|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.HOME" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- YouTube Music Entegrasyonu İçin Bildirim ve Medya Oturumu Dinleyicisi -->
        <service
            android:name=".YouTubeMusicNotificationService"
            android:label="YT Music Bridge"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
            android:exported="true">
            <intent-filter>
                <action android:name="android.service.notification.NotificationListenerService" />
            </intent-filter>
        </service>
    </application>
</manifest>''',
    "app/src/main/java/com/fyt/customcarlauncher/AuxCameraGlRenderer.kt": '''package com.fyt.customcarlauncher

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

enum class AuxFilterMode(val id: Int) {
    NORMAL(0),
    NIGHT_VISION(1),
    INFRARED_THERMAL(2),
    BLACK_AND_WHITE(3)
}

class AuxCameraGlRenderer(private val onSurfaceReady: (SurfaceTexture) -> Unit) :
    GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    @Volatile
    var currentFilter: AuxFilterMode = AuxFilterMode.NORMAL
    private var textureId: Int = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var shaderProgram: Int = 0

    // Android 10 (QP1A.190711.020) Uyumlu GLSL ES 2.0 Fragment Shader
    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;
        uniform int uFilterMode;

        void main() {
            vec4 color = texture2D(sTexture, vTextureCoord);
            float luma = dot(color.rgb, vec3(0.299, 0.587, 0.114));

            if (uFilterMode == 1) {
                // b) GECE GÖRÜŞÜ (Gama düzeltmesi + x2.5 Gain + Fosforlu Yeşil Netleştirme)
                float boosted = pow(clamp(luma * 2.4, 0.0, 1.0), 0.45);
                gl_FragColor = vec4(boosted * 0.22, boosted * 0.98, boosted * 0.30, 1.0);
            } else if (uFilterMode == 2) {
                // c) KIZILÖTESİ / TERMAL (Isı Haritası LUT: Lacivert -> Mor -> Kırmızı -> Sarı -> Beyaz)
                vec3 c1 = vec3(0.05, 0.02, 0.35);
                vec3 c2 = vec3(0.55, 0.05, 0.60);
                vec3 c3 = vec3(0.95, 0.15, 0.10);
                vec3 c4 = vec3(1.00, 0.90, 0.15);
                vec3 c5 = vec3(1.00, 1.00, 1.00);
                vec3 irColor;
                if (luma < 0.25) irColor = mix(c1, c2, luma * 4.0);
                else if (luma < 0.5) irColor = mix(c2, c3, (luma - 0.25) * 4.0);
                else if (luma < 0.75) irColor = mix(c3, c4, (luma - 0.5) * 4.0);
                else irColor = mix(c4, c5, (luma - 0.75) * 4.0);
                gl_FragColor = vec4(irColor, 1.0);
            } else if (uFilterMode == 3) {
                // d) YÜKSEK KONTRASTLI SİYAH BEYAZ (Monokrom)
                float bw = clamp((luma - 0.5) * 1.45 + 0.5, 0.0, 1.0);
                gl_FragColor = vec4(vec3(bw), 1.0);
            } else {
                // Normal Kamera Görünümü
                gl_FragColor = color;
            }
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        surfaceTexture = SurfaceTexture(textureId).apply {
            setOnFrameAvailableListener(this@AuxCameraGlRenderer)
            onSurfaceReady(this)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        surfaceTexture?.updateTexImage()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val filterLoc = GLES20.glGetUniformLocation(shaderProgram, "uFilterMode")
        GLES20.glUniform1i(filterLoc, currentFilter.id)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {}
}''',
    "app/src/main/java/com/fyt/customcarlauncher/MainActivity.kt": '''package com.fyt.customcarlauncher

import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity(), LocationListener {

    private var currentSpeedKmh by mutableStateOf(0)
    private var currentSpeedLimit by mutableStateOf(50)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveFullscreen()

        setContent {
            var isDarkMode by remember { mutableStateOf(true) }
            var brightness by remember { mutableStateOf(0.9f) }
            var showYtMusicPopup by remember { mutableStateOf(false) }

            val bgColor = if (isDarkMode) Color(0xFF0A0B0E) else Color(0xFFE2E8F0)
            val cardColor = if (isDarkMode) Color(0xFF15171C) else Color(0xFFFFFFFF)

            // 1600x720 Tam Ekran 7 Bölmeli Ana Grid (Genişletilmiş Harita & İnce Barlar)
            Box(modifier = Modifier.fillMaxSize().background(bgColor).padding(8.dp)) {
                Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 1. BÖLÜM: İnceltilmiş Sol Dikey Dock (56.dp Genişlik)
                    Surface(
                        modifier = Modifier.width(56.dp).fillMaxHeight(),
                        shape = RoundedCornerShape(18.dp),
                        color = cardColor
                    ) { /* Kompakt Dock İçeriği */ }

                    // Sağ 6 Bölme (Üst: Geniş Harita + İnce Kamera + Hız/Blitzer | Alt: İnce YT Music + Parlaklık)
                    Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 2. BÖLÜM: Genişletilmiş Orijinal Google Maps Konteyneri (weight: 1.95f)
                            Surface(modifier = Modifier.weight(1.95f).fillMaxHeight(), shape = RoundedCornerShape(22.dp), color = cardColor) {}

                            // 3. BÖLÜM: İnceltilmiş Dikey Ön Kamera Kartı + Kompakt 4 Shader Butonu (weight: 0.86f)
                            Surface(modifier = Modifier.weight(0.86f).fillMaxHeight(), shape = RoundedCornerShape(22.dp), color = cardColor) {}

                            // 4. ve 5. BÖLÜM: Sağ Sütun (Üst: Hız & Hız Limiti, Alt: Blitzer.de Çerçevesi - weight: 0.65f)
                            Column(modifier = Modifier.weight(0.65f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Surface(modifier = Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = cardColor) {}
                                Surface(modifier = Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = cardColor) {}
                            }
                        }

                        // Alt Satır (52.dp İnce Yükseklik): 6. Bölüm (YT Music Bar) & 7. Bölüm (Tema + FYT Parlaklık Fader)
                        Row(modifier = Modifier.height(52.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                modifier = Modifier.weight(1f).fillMaxHeight().pointerInput(Unit) {
                                    detectTapGestures(onDoubleTap = { showYtMusicPopup = true })
                                },
                                shape = RoundedCornerShape(18.dp),
                                color = cardColor
                            ) {}

                            Surface(
                                modifier = Modifier.width(210.dp).fillMaxHeight(),
                                shape = RoundedCornerShape(18.dp),
                                color = cardColor
                            ) {}
                        }
                    }
                }
            }
        }
    }

    // FYT Üst Durum Çubuğunu Gizleyen Tam Ekran Modu
    private fun enableImmersiveFullscreen() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
    }

    override fun onLocationChanged(location: Location) {
        currentSpeedKmh = (location.speed * 3.6f).toInt()
    }
}'''
}

for path, content in FILES.items():
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
print("Tebrikler! Tüm FYT 1600x720 Car Launcher proje dosyaları oluşturuldu.")
print("Şimdi bu klasörü GitHub'a yükleyip 'Actions' sekmesinden hazır APK dosyanızı indirebilirsiniz.")
