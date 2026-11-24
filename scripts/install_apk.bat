@echo off
setlocal EnableExtensions

rem ============================================================
rem Install the debug APK to the connected emulator/device and
rem launch the main activity.
rem ============================================================

set "ADB=C:\app\soft\android-sdk\platform-tools\adb.exe"
set "APK=%~dp0..\android\app\build\outputs\apk\debug\app-debug.apk"
set "APP_ID=com.sjz.harvest"
set "MAIN=com.sjz.harvest/.ui.MainActivity"

if not exist "%ADB%" (
    echo [ERROR] adb not found: "%ADB%"
    echo         Check the path in this script.
    exit /b 1
)

if not exist "%APK%" (
    echo [ERROR] APK not found: "%APK%"
    echo         Build it first: scripts\build_apk.bat
    exit /b 1
)

echo ============================================================
echo  Connected devices:
echo ============================================================
"%ADB%" devices
if errorlevel 1 (
    echo [ERROR] adb devices failed.
    exit /b 1
)

echo.
echo Installing "%APK%" ...
"%ADB%" install -r "%APK%"
if errorlevel 1 (
    echo.
    echo [ERROR] Install failed.
    echo         - Make sure the emulator is running and USB debugging is enabled.
    echo         - If the error mentions INSTALL_FAILED_UPDATE_INCOMPATIBLE,
    echo           uninstall the old package first:
    echo           "%ADB%" uninstall %APP_ID%
    exit /b 1
)

echo.
echo Launching %MAIN% ...
"%ADB%" shell am start -n %MAIN%
if errorlevel 1 (
    echo [WARN] Could not launch the activity automatically.
    echo        Open "特勤处收菜" manually from the launcher.
    exit /b 1
)

echo.
echo [OK] Installed and launched.
echo      Next steps: enable the accessibility service, then run the in-app self-test.
endlocal
exit /b 0
