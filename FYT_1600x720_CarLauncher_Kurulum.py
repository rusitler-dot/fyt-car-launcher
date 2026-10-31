name: Build FYT Car Launcher APK

on:
  push:
    branches: [ "main", "master" ]
  workflow_dispatch:

jobs:
  build-apk:
    runs-on: ubuntu-latest
    steps:
      - name: 1. Depoyu Cek
        uses: actions/checkout@v4

      - name: 2. JDK 17 Kur
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'

      - name: 3. Proje Dosyalarini Python ile Hatasiz Olustur
        run: python3 -c '
          import os, shutil, subprocess
          subprocess.run(["python3", "FYT_1600x720_CarLauncher_Kurulum.py"], check=True)
          if os.path.exists("FYTCarLauncher"):
              for item in os.listdir("FYTCarLauncher"):
                  s = os.path.join("FYTCarLauncher", item)
                  d = os.path.join(".", item)
                  if os.path.isdir(s):
                      shutil.copytree(s, d, dirs_exist_ok=True)
                  else:
                      shutil.copy2(s, d)
          open("settings.gradle.kts", "w").write("pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ndependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }\nrootProject.name = \"FYTCarLauncher\"\ninclude(\":app\")\n")
          open("build.gradle.kts", "w").write("plugins {\n id(\"com.android.application\") version \"8.2.2\" apply false\n id(\"org.jetbrains.kotlin.android\") version \"1.9.22\" apply false\n}\n")
          open("gradle.properties", "w").write("org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8\nandroid.useAndroidX=true\nandroid.nonTransitiveRClass=true\n")
          os.makedirs("app/src/main/res/values", exist_ok=True)
          open("app/src/main/res/values/themes.xml", "w").write("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n<style name=\"Theme.FYTCarLauncher\" parent=\"android:Theme.Material.NoActionBar\">\n<item name=\"android:windowNoTitle\">true</item>\n<item name=\"android:windowFullscreen\">true</item>\n</style>\n</resources>\n")
          app_gradle = open("app/build.gradle.kts").read()
          if "material-icons-extended" not in app_gradle:
              app_gradle = app_gradle.replace("implementation(\"androidx.compose.material3:material3\")", "implementation(\"androidx.compose.material3:material3\")\n    implementation(\"androidx.compose.material:material-icons-extended\")")
              open("app/build.gradle.kts", "w").write(app_gradle)
          '

      - name: 4. Gradle 8.5 Indir ve APK Derle
        run: |
          wget -q https://services.gradle.org/distributions/gradle-8.5-bin.zip
          unzip -q gradle-8.5-bin.zip
          export PATH=$PWD/gradle-8.5/bin:$PATH
          yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null || true
          gradle assembleDebug --no-daemon --stacktrace

      - name: 5. Hazir APK Dosyasini Yukle
        uses: actions/upload-artifact@v4
        with:
          name: FYT-1600x720-Car-Launcher-APK
          path: app/build/outputs/apk/debug/*.apk
