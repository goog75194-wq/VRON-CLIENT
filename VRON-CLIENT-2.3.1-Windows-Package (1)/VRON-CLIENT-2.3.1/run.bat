@echo off
cd /d %~dp0
if not exist dist\VRON-CLIENT.jar call build.bat
java -jar dist\VRON-CLIENT.jar
