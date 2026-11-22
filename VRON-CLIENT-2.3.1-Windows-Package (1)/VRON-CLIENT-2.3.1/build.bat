@echo off
setlocal
cd /d "%~dp0"
if exist build rmdir /s /q build
mkdir build
if exist sources.txt del sources.txt
where javac >nul 2>nul || (echo Java JDK 21+ not found. Install a JDK and add it to PATH.& exit /b 1)
for /r src\main\java %%f in (*.java) do @echo %%f>> sources.txt
javac --release 21 -encoding UTF-8 -d build @sources.txt
if errorlevel 1 (del sources.txt 2>nul& exit /b 1)
del sources.txt
if not exist dist mkdir dist
if exist dist\VRON-CLIENT.jar del dist\VRON-CLIENT.jar
jar --create --file dist\VRON-CLIENT.jar --main-class com.vron.client.Main -C build . -C src\main\resources .
if errorlevel 1 exit /b 1
echo.
echo Build complete: dist\VRON-CLIENT.jar
