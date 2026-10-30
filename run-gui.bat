@echo off
rem Start the GUI without building an exe (needs Java 17+ and build\libs\ysp-0.1.0-all.jar).
cd /d "%~dp0"
start "" javaw -Dfile.encoding=UTF-8 -cp build\libs\ysp-0.1.0-all.jar ysp.Gui
