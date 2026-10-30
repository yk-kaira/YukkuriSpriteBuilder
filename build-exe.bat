@echo off
setlocal
cd /d "%~dp0"

rem ---- Build ysp.exe (GUI) and ysp-cli.exe (console) ----
rem Requires: JDK 17+ (with jpackage) on PATH, and Gradle (or gradlew.bat).

where jpackage >nul 2>nul
if errorlevel 1 (
  echo [ERROR] jpackage was not found. Install JDK 17 or later and add its bin folder to PATH.
  exit /b 1
)

if exist gradlew.bat (
  call gradlew.bat fatJar
) else (
  call gradle fatJar
)
if errorlevel 1 (
  echo [ERROR] Gradle build failed.
  exit /b 1
)

if exist build\jpackage-input rmdir /s /q build\jpackage-input
if exist build\dist rmdir /s /q build\dist
mkdir build\jpackage-input
copy /y build\libs\ysp-0.1.0-all.jar build\jpackage-input\ >nul

rem Icon: packaging\ysp.ico is used for the exe if it exists (replace the file to change the icon).
set "ICON_ARG="
if exist packaging\ysp.ico (
  set "ICON_ARG=--icon packaging\ysp.ico"
) else (
  echo [WARN] packaging\ysp.ico not found. The default Java icon will be used.
)

jpackage --type app-image ^
  --name ysp ^
  --app-version 0.1.0 ^
  --input build\jpackage-input ^
  --main-jar ysp-0.1.0-all.jar ^
  --main-class ysp.Gui ^
  --dest build\dist ^
  --add-modules java.desktop,java.prefs,java.logging,java.xml ^
  --java-options "-Dfile.encoding=UTF-8" ^
  %ICON_ARG% ^
  --add-launcher ysp-cli=packaging\ysp-cli.properties
if errorlevel 1 (
  echo [ERROR] jpackage failed.
  exit /b 1
)

rem Distribution documents (README.txt / LICENSE.txt)
if exist dist-docs\README.txt copy /y dist-docs\README.txt build\dist\ysp\README.txt >nul
if exist dist-docs\LICENSE.txt copy /y dist-docs\LICENSE.txt build\dist\ysp\LICENSE.txt >nul

echo.
echo Done: build\dist\ysp\ysp.exe  (GUI)
echo       build\dist\ysp\ysp-cli.exe  (console)
echo Distribute the whole build\dist\ysp folder (zip it).
endlocal
