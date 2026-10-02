@echo off
SET adb=\bin\adb\adb.exe
SET apksigner=%appdata%\..\local\Android\Sdk\build-tools\36.0.0\apksigner.bat
SET APK=app\build\outputs\apk\release\sleepmon.apk  

call gradlew assembleRelease 
if %ERRORLEVEL% GTR 0 (echo error apk build && goto :EOF)
call %apksigner% verify --verbose app\build\outputs\apk\release\sleepmon.apk  
if %ERRORLEVEL% GTR 0 (echo error sign verify && goto :EOF)
%adb% install -r app\build\outputs\apk\release\app-release.apk


