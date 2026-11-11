# Builds everything: tmnative.dll (Rust), the patched Java classes and
# tracker-native\tracker-native.jar (tracker.jar + patched classes).
#
#   powershell -ExecutionPolicy Bypass -File build.ps1
#
# Paths handed to javac / jar / the MinGW linker are kept relative or ASCII:
# those tools cannot open paths with Thai (non-ANSI) characters such as "เอกสาร".
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$jdk = @("$env:JAVA_HOME", 'C:\Program Files\Java\jdk-21', 'C:\Program Files\Java\latest') |
	Where-Object { $_ -and (Test-Path "$_\bin\javac.exe") } | Select-Object -First 1
if (-not $jdk) { throw 'JDK not found (set JAVA_HOME to a JDK 17+)' }

# A running Tracker holds tracker-native.jar open: overwriting it under the JVM can crash it.
$running = Get-CimInstance Win32_Process -Filter "Name like 'java%'" |
	Where-Object { $_.CommandLine -match 'tracker-native\.jar' }
if ($running) { throw "Tracker-Rust is running (PID $($running.ProcessId -join ', ')): save and close it first" }

# 0. stock tracker.jar from the installed Tracker 6.3.5 (not kept in the repository)
if (-not (Test-Path java\lib\tracker.jar)) {
	New-Item -ItemType Directory -Force java\lib | Out-Null
	Copy-Item 'C:\Program Files\Tracker\tracker.jar' java\lib\tracker.jar
}

# 1. Rust kernel; the target dir must have an ASCII path for the linker
$env:CARGO_TARGET_DIR = Join-Path $env:TEMP 'tmnative-target'
# keep local paths (user profile, cargo registry, toolchain) out of the DLL's panic messages
$env:RUSTFLAGS = "--remap-path-prefix=$($env:USERPROFILE)=~"
Push-Location tmnative
cargo build --release
if ($LASTEXITCODE) { throw 'cargo build failed' }
Pop-Location
$dll = Join-Path $env:CARGO_TARGET_DIR 'release\tmnative.dll'

# 2. patched classes, compiled against the stock tracker.jar
Push-Location java
$xuggle = 'C:\Program Files\Tracker\Xuggle\xuggle-xuggler-server-all.jar'
# or the copy kept for the installer (relative: the repository path may not be ASCII)
if (-not (Test-Path $xuggle)) { $xuggle = '..\installer\runtime\Xuggle\xuggle-xuggler-server-all.jar' }
# every source under java\src (relative paths: javac cannot open the Thai folder name)
$root = (Resolve-Path src).Path
$sources = Get-ChildItem src -Recurse -Filter *.java | ForEach-Object { 'src' + $_.FullName.Substring($root.Length) }
& "$jdk\bin\javac.exe" --release 17 -nowarn -cp "lib\tracker.jar;$xuggle" -d patched @sources
if ($LASTEXITCODE) { throw 'javac failed' }
& "$jdk\bin\javac.exe" --release 17 -nowarn -cp "patched;lib\tracker.jar;$xuggle" -d bench\out `
	bench\Bench.java bench\AccBench.java bench\DecodeBench.java `
	bench\org\opensourcephysics\media\xuggle\PrefetchCheck.java
if ($LASTEXITCODE) { throw 'javac (bench) failed' }
Copy-Item $dll patched\tmnative.dll -Force

# 3. tracker-native.jar = tracker.jar + patched classes, built aside and then moved in
$tmpJar = Join-Path $env:TEMP 'tracker-native-build.jar'
Copy-Item lib\tracker.jar $tmpJar -Force
# every class under patched\org (inner classes included), as "-C patched <relative path>"
$root = (Resolve-Path patched).Path
$classes = Get-ChildItem patched\org -Recurse -Filter *.class |
	ForEach-Object { '-C'; 'patched'; $_.FullName.Substring($root.Length + 1).Replace('\', '/') }
& "$jdk\bin\jar.exe" uf $tmpJar @classes
if ($LASTEXITCODE) { throw 'jar failed' }
Pop-Location
Move-Item $tmpJar tracker-native\tracker-native.jar -Force
Copy-Item $dll tracker-native\tmnative.dll -Force
Write-Host 'Built tracker-native\tracker-native.jar and tmnative.dll'
