@echo off
setlocal

cd /d "%~dp0"

echo [1/3] Building signed release APK...
call gradlew.bat assembleRelease
if errorlevel 1 (
    echo.
    echo ERROR: release build failed.
    exit /b 1
)

set "APK=%CD%\app\build\outputs\apk\release\sleepmon.apk"
if not exist "%APK%" (
    echo.
    echo ERROR: APK was not created:
    echo %APK%
    exit /b 1
)

echo.
echo [2/3] Verifying APK signature...
set "SDK=%ANDROID_SDK_ROOT%"
if not defined SDK set "SDK=%ANDROID_HOME%"
if not defined SDK (
    echo.
    echo ERROR: ANDROID_SDK_ROOT or ANDROID_HOME is not set.
    exit /b 1
)

for /f "delims=" %%B in ('powershell -NoProfile -Command "(Get-ChildItem -Directory '%SDK%\build-tools' | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1).FullName"') do set "BUILD_TOOLS=%%B"

if not defined BUILD_TOOLS (
    echo.
    echo ERROR: Android Build Tools not found in %SDK%\build-tools
    exit /b 1
)

if not exist "%BUILD_TOOLS%\apksigner.bat" (
    echo.
    echo ERROR: apksigner.bat not found in:
    echo %BUILD_TOOLS%
    exit /b 1
)

call "%BUILD_TOOLS%\apksigner.bat" verify --verbose "%APK%"
if errorlevel 1 (
    echo.
    echo ERROR: APK signature verification failed.
    exit /b 1
)

echo.
echo [3/3] Installing APK on the connected phone...
where adb >nul 2>&1
if errorlevel 1 (
    echo.
    echo ERROR: adb was not found in PATH.
    exit /b 1
)

adb install -r "%APK%"
if errorlevel 1 (
    echo.
    echo ERROR: APK installation failed.
    exit /b 1
)

echo.
echo SUCCESS: release APK built, signature verified, and APK installed.
echo APK: %APK%
exit /b 0
