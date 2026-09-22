@echo off
rem Release helper wrapper: runs release.ps1 with Windows PowerShell (pwsh not required).
rem Works from cmd, Windows Terminal, or the Android Studio built-in terminal.
rem
rem Usage: packaging\release.bat <VERSION>
rem   e.g.  packaging\release.bat 1.2.1
setlocal
if "%~1"=="" (
    echo Usage: packaging\release.bat ^<VERSION^>
    echo   e.g. packaging\release.bat 1.2.1
    exit /b 2
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0release.ps1" -Version %~1
exit /b %ERRORLEVEL%
