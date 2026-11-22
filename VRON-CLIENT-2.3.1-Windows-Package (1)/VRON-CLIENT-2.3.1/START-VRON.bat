@echo off
setlocal
cd /d "%~dp0"
title VRON CLIENT Black Edition 2.3.1
if not exist "dist\VRON-CLIENT.jar" (
  echo VRON CLIENT JAR not found.
  echo Please extract the complete ZIP archive first.
  pause
  exit /b 1
)
where javaw >nul 2>nul
if errorlevel 1 (
  where java >nul 2>nul
  if errorlevel 1 (
    echo Java 21 or newer is required.
    echo Install a Java 21 runtime, then double-click START-VRON.bat again.
    start "" "https://adoptium.net/temurin/releases/?version=21"
    pause
    exit /b 1
  )
  java -version
  java -jar "dist\VRON-CLIENT.jar"
) else (
  start "" javaw -jar "dist\VRON-CLIENT.jar"
)
endlocal
