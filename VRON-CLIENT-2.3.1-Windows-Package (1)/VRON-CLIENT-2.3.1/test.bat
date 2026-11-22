@echo off
setlocal
cd /d %~dp0
if exist build rmdir /s /q build
if exist build-test rmdir /s /q build-test
mkdir build
mkdir build-test
if exist test-sources.txt del test-sources.txt
where javac >nul 2>nul || (echo Install JDK 21+ and add it to PATH.& exit /b 1)
for /r src\main\java %%f in (*.java) do @echo %%f>> test-sources.txt
javac --release 21 -encoding UTF-8 -d build @test-sources.txt || exit /b 1
del test-sources.txt
javac --release 21 -encoding UTF-8 -cp build -d build-test src\test\java\com\vron\client\LauncherCoreSelfTest.java || exit /b 1
java -cp build;build-test com.vron.client.LauncherCoreSelfTest
