@echo off
setlocal
cd /d "%~dp0"
echo [1/3] Building VRON CLIENT...
call build.bat
if errorlevel 1 exit /b 1

echo [2/3] Checking Windows packaging tools...
where jpackage >nul 2>nul || (
  echo ERROR: JDK 21+ with jpackage is required.
  exit /b 1
)
where candle >nul 2>nul || (
  echo ERROR: WiX Toolset 3.14 candle.exe was not found in PATH.
  echo Install WiX Toolset 3.14 and add its bin folder to PATH.
  exit /b 1
)
where light >nul 2>nul || (
  echo ERROR: WiX Toolset 3.14 light.exe was not found in PATH.
  exit /b 1
)

if not exist release mkdir release
del /q "release\VRON CLIENT Black Edition-2.3.1.exe" 2>nul

echo [3/3] Creating Windows installer with bundled Java runtime...
jpackage --type exe ^
  --name "VRON CLIENT Black Edition" ^
  --app-version 2.3.1 ^
  --vendor "VRON Studio" ^
  --description "VRON CLIENT Black Edition Minecraft Launcher" ^
  --input dist ^
  --main-jar VRON-CLIENT.jar ^
  --main-class com.vron.client.Main ^
  --dest release ^
  --icon assets\vron.ico ^
  --win-menu ^
  --win-menu-group "VRON CLIENT" ^
  --win-shortcut ^
  --win-dir-chooser ^
  --win-per-user-install ^
  --java-options "-Xmx2G" ^
  --java-options "-Dfile.encoding=UTF-8"
if errorlevel 1 (
  echo ERROR: jpackage failed. Check JDK and WiX installation.
  exit /b 1
)
echo.
echo SUCCESS: release\VRON CLIENT Black Edition-2.3.1.exe
