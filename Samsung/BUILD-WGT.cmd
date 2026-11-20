@echo off
setlocal
cd /d "%~dp0"
"..\Laptop\runtime\python.exe" -B -X utf8 "build_wgt.py"
pause
