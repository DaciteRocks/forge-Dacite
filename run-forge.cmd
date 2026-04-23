@echo off
REM Launches the locally-built Forge desktop client with the correct working
REM directory so res/ is found. Run from any shell; cwd doesn't matter.

set "JAR=%~dp0forge-gui-desktop\target\forge-gui-desktop-2.0.12-SNAPSHOT-jar-with-dependencies.jar"

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
