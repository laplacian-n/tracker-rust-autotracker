# Unattended AutoTracker benchmark, without touching tracker-native\ or a running Tracker.
#
#   powershell -ExecutionPolicy Bypass -File autotest.ps1 [-File pendulum_setup.trz] [-Runs 1] [-Env @{TM_NO_ACCEL='1'}]
#
# Builds a test copy of the jar (tracker.jar + java\patched classes + the newest
# tmnative.dll) in %TEMP%\tracker-autotest, starts Tracker there with main class
# AutoTest: it opens the file (saved with Autotracker key frames), restores the key
# frames, clears the other steps, runs Search in Fast mode to the end and exits.
# Prints the timing log of each run.
param(
	[string]$File = 'pendulum_setup.trz',
	[int]$Runs = 1,
	[hashtable]$Env = @{},
	[switch]$NoBuild
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$T = 'C:\Program Files\Tracker'
$jdk = 'C:\Program Files\Java\jdk-21'
$dir = Join-Path $env:TEMP 'tracker-autotest'
New-Item -ItemType Directory -Force $dir | Out-Null

if (-not $NoBuild) {
	$dll = Join-Path $env:TEMP 'tmnative-target\release\tmnative.dll'
	Copy-Item $dll (Join-Path $dir 'tmnative.dll') -Force
	$jar = Join-Path $dir 'tracker-native.jar'
	Copy-Item java\lib\tracker.jar $jar -Force
	Push-Location java
	$root = (Resolve-Path patched).Path
	$classes = Get-ChildItem patched\org, patched\javajs -Recurse -Filter *.class |
		ForEach-Object { '-C'; 'patched'; $_.FullName.Substring($root.Length + 1).Replace('\', '/') }
	& "$jdk\bin\jar.exe" uf $jar @classes
	if ($LASTEXITCODE) { throw 'jar failed' }
	Pop-Location
}

$mem = [int][math]::Min(16384, [math]::Max(1024, (Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 2MB))
$target = (Resolve-Path $File).Path
for ($r = 1; $r -le $Runs; $r++) {
	$log = Join-Path $dir 'tracker-native-perf.log'
	Remove-Item $log -ErrorAction SilentlyContinue
	$psi = New-Object System.Diagnostics.ProcessStartInfo
	$psi.FileName = "$T\OpenJDK-21.0.5.jre\bin\java.exe"
	$psi.WorkingDirectory = $dir
	$psi.UseShellExecute = $false
	$psi.RedirectStandardOutput = $true
	$psi.Arguments = "`"-Dsun.awt.fontconfig=$T\fontconfig.properties`" -Xms64m -Xmx${mem}m -XX:+UseG1GC " +
		"-XX:+ExplicitGCInvokesConcurrent `"-Djava.io.tmpdir=$dir`" " +
		"-cp `"tracker-native.jar;$T\Xuggle\xuggle-xuggler-server-all.jar;$T\Xuggle\slf4j-api.jar`" " +
		"org.opensourcephysics.cabrillo.tracker.AutoTest"
	$psi.EnvironmentVariables['TRACKER_AUTOTEST'] = $target
	$psi.EnvironmentVariables['TRACKER_HOME'] = $T
	$psi.EnvironmentVariables['XUGGLE_HOME'] = "$T\Xuggle"
	$psi.EnvironmentVariables['MEMORY_SIZE'] = "$mem"
	$psi.EnvironmentVariables['PATH'] = "$T\Xuggle\bin;" + $env:PATH
	foreach ($k in $Env.Keys) { $psi.EnvironmentVariables[$k] = [string]$Env[$k] }
	$p = [System.Diagnostics.Process]::Start($psi)
	$out = $p.StandardOutput.ReadToEnd()
	$p.WaitForExit()
	Write-Host "---- run $r (exit $($p.ExitCode))"
	$out -split "`n" | Where-Object { $_ -match 'AutoTest' } | ForEach-Object { Write-Host $_.TrimEnd() }
	if (Test-Path $log) { Get-Content $log | Where-Object { $_ -match 'frames=|AutoTest' } | ForEach-Object { Write-Host $_ } }
}
