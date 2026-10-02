@echo off
SET adb=\bin\adb\adb.exe
SET apksigner=%appdata%\..\local\Android\Sdk\build-tools\36.0.0\apksigner.bat
SET APK=app\build\outputs\sleepmon\sleepmon.apk

call gradlew assembleRelease 
if %ERRORLEVEL% GTR 0 (echo error apk build && goto :EOF)
call %apksigner% verify --verbose %APK%
if %ERRORLEVEL% GTR 0 (echo error sign verify && goto :EOF)
%adb% install -r %APK%


