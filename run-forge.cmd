@echo off
REM Launches the locally-built Forge desktop client with the correct working
REM directory so res/ is found. Run from any shell; cwd doesn't matter.

REM Find the desktop fat-jar without hard-coding the Forge version: the name
REM embeds the version (e.g. 2.0.12 -> 2.0.14 after an upstream merge), so a
REM pinned name goes stale on every sync. Glob the target dir; if several match
REM (rare), the loop keeps the last one.
set "JAR="
for %%F in ("%~dp0forge-gui-desktop\target\forge-gui-desktop-*-jar-with-dependencies.jar") do set "JAR=%%F"

if not defined JAR (
  echo Fat jar not found in:
  echo   %~dp0forge-gui-desktop\target\
  echo Build it first:  mvn -B -pl forge-gui-desktop -am install -P windows-linux -DskipTests
  pause
  exit /b 1
)

if not exist "%JAR%" (
  echo Fat jar not found at:
  echo   %JAR%
  echo Build it first:  mvn -B -pl forge-gui-desktop -am install -P windows-linux -DskipTests
  pause
  exit /b 1
)

pushd "%~dp0forge-gui"
java -Xmx4096m -Dio.netty.tryReflectionSetAccessible=true -Dfile.encoding=UTF-8 -jar "%JAR%"
popd
