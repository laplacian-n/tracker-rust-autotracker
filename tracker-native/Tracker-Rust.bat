@echo off
rem Starts Tracker with the Rust autotracker (tmnative.dll next to this file).
rem Uses the JRE and Xuggle that come with the installed Tracker 6.3.5.
rem
rem Memory: half of the physical RAM (1-16 GB) so long / HD / 4K videos fit.
rem Override with e.g.  set TRACKER_MEMORY_MB=4096  before running this file.
setlocal
set "T=C:\Program Files\Tracker"
cd /d "%~dp0"

if not defined TRACKER_MEMORY_MB (
  for /f %%m in ('powershell -NoProfile -Command "[int][math]::Min(16384,[math]::Max(1024,(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory/2MB))"') do set "TRACKER_MEMORY_MB=%%m"
)
if not defined TRACKER_MEMORY_MB set "TRACKER_MEMORY_MB=2048"

rem Tracker relaunches itself with its own (1 GB) memory setting unless the
rem environment already matches; these make it keep this JVM and its heap.
set "TRACKER_HOME=%T%"
set "XUGGLE_HOME=%T%\Xuggle"
set "MEMORY_SIZE=%TRACKER_MEMORY_MB%"
set "PATH=%XUGGLE_HOME%\bin;%PATH%"

rem The jar is given by a relative path on purpose: Java cannot read folder
rem names with Thai characters (e.g. the Documents folder) from its command line.
start "" "%T%\OpenJDK-21.0.5.jre\bin\javaw.exe" "-Dsun.awt.fontconfig=%T%\fontconfig.properties" ^
  -Xms64m -Xmx%TRACKER_MEMORY_MB%m -XX:+UseG1GC -XX:+ExplicitGCInvokesConcurrent ^
  -cp "tracker-native.jar;%T%\Xuggle\xuggle-xuggler-server-all.jar;%T%\Xuggle\slf4j-api.jar" ^
  org.opensourcephysics.cabrillo.tracker.Tracker %*
