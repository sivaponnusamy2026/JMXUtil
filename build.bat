@echo off
REM Build jmxdiag with the plain JDK (no Maven required).
setlocal
set SRC=src\main\java\com\example\jmxutil\JmxDiagnostic.java
set OUT=out

if not exist "%OUT%" mkdir "%OUT%"

echo Compiling...
javac -d "%OUT%" "%SRC%"
if errorlevel 1 (
    echo Build FAILED.
    exit /b 1
)

echo Packaging jmxdiag.jar...
jar --create --file jmxdiag.jar --main-class com.example.jmxutil.JmxDiagnostic -C "%OUT%" .
if errorlevel 1 (
    echo Jar FAILED.
    exit /b 1
)

echo Done. Run:  java -jar jmxdiag.jar --host ^<host^> --port ^<port^>
endlocal
