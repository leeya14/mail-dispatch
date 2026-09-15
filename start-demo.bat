@echo off
cd /d "%~dp0"
where java >nul 2>nul || (echo Java 17 or newer is required. & pause & exit /b 1)
where python >nul 2>nul || (echo Python 3 is required for the local SMTP inbox. & pause & exit /b 1)
start "Mail Dispatch - local test inbox" python tools\demo_smtp.py
echo Open http://127.0.0.1:8081 after the application starts.
java -jar run\mail-dispatch-1.0.0.jar
pause
