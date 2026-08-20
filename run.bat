@echo off
rem Launch the ADB GUI desktop app (./gradlew :desktop:run) from any directory.
rem If you changed :core source and the app behaves like the old build, run
rem `gradlew.bat clean` once first (or pass `clean`: run.bat clean) to force a
rem rebuild of core.jar/desktop.jar.
setlocal
cd /d "%~dp0"
if /i "%~1"=="clean" (
    call gradlew.bat clean :desktop:run %2 %3
) else (
    call gradlew.bat :desktop:run %*
)
if errorlevel 1 (
    echo.
    echo Build/run failed (exit code %errorlevel%). Pausing so you can read the log.
    pause
)
endlocal
