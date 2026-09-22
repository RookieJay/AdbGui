@echo off
rem Release helper wrapper: runs release.ps1 with Windows PowerShell (pwsh not required).
rem Works from cmd, Windows Terminal, or the Android Studio built-in terminal.
rem The version is read from gradle.properties (single source of truth); an argument
rem is optional and only used to sanity-check that it matches.
rem
rem Usage: packaging\release.bat
rem   (or) packaging\release.bat 1.2.1   -- must match gradle.properties
setlocal
set ARGS=
if not "%~1"=="" set ARGS=-Version %~1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0release.ps1" %ARGS%
exit /b %ERRORLEVEL%
