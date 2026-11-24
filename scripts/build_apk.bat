@echo off
setlocal EnableExtensions

rem ============================================================
rem Build the debug APK for the Sjz Harvest Android app.
rem Absolute paths are intentional: this script can be run from
rem any working directory (Explorer, CI, or a shell).
rem ============================================================

set "GRADLE=C:\app\soft\gradle-8.11.1\bin\gradle.bat"
set "SDK=C:\app\soft\android-sdk"
set "PROJECT=C:\app\soft\youxi\sjz\android"
set "APK=%PROJECT%\app\build\outputs\apk\debug\app-debug.apk"
rem Use the project-local Gradle home. It avoids the user's global init.gradle
rem (which conflicts with this project's repository settings) and caches mirrors.
set "GRADLE_USER_HOME=C:\app\soft\youxi\sjz\.gradle-home"

if not exist "%GRADLE%" (
    echo [ERROR] Gradle not found: "%GRADLE%"
    echo         Check the path in this script.
    exit /b 1
)

if not exist "%SDK%" (
    echo [ERROR] Android SDK not found: "%SDK%"
    echo         Check the path in this script.
    exit /b 1
)

if not exist "%PROJECT%\settings.gradle.kts" (
    echo [ERROR] Android project not found: "%PROJECT%"
    exit /b 1
)

set "ANDROID_HOME=%SDK%"
set "ANDROID_SDK_ROOT=%SDK%"

echo ============================================================
echo  Building debug APK
echo  Project: %PROJECT%
echo  Gradle : %GRADLE%
echo  SDK    : %SDK%
echo ============================================================

pushd "%PROJECT%"
call "%GRADLE%" assembleDebug
set "BUILD_RESULT=%ERRORLEVEL%"
popd

if not "%BUILD_RESULT%"=="0" (
    echo.
    echo [ERROR] Gradle build failed with exit code %BUILD_RESULT%.
    echo         Scroll up for the first Kotlin/Android error.
    exit /b %BUILD_RESULT%
)

if not exist "%APK%" (
    echo.
    echo [ERROR] Build reported success but APK was not found:
    echo         "%APK%"
    exit /b 1
)

echo.
echo [OK] APK built: "%APK%"
echo      Install with: scripts\install_apk.bat
endlocal
exit /b 0
