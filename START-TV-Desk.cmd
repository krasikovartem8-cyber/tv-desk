@echo off
setlocal
cd /d "%~dp0"
"Laptop\runtime\python.exe" -B -X utf8 "Laptop\desk_server.py"
if errorlevel 1 pause
