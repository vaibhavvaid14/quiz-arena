@echo off
rem Double-click to launch Quiz Arena in your browser.
cd /d "%~dp0"
python serve.py --open
if errorlevel 1 pause
