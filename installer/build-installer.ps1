# Builds installer\build\Tracker-Rust-Setup-<version>.exe: a single installer with
# the Java runtime, Xuggle, tracker-native.jar and tmnative.dll inside.
#
# Needs: build.ps1 run first (tracker-native\tracker-native.jar and tmnative.dll),
# and Tracker 6.3.5 installed in C:\Program Files\Tracker the first time
# (its OpenJDK runtime and Xuggle are copied to installer\runtime).
# Uses the C# compiler that comes with Windows (.NET Framework 4).
param([string]$Version = "1.1.0")

$here = $PSScriptRoot
$root = Split-Path $here
$rt = Join-Path $here "runtime"
$out = Join-Path $here "build"
$csc = "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
$fw = Split-Path $csc

foreach ($f in "tracker-native.jar", "tmnative.dll") {
    if (-not (Test-Path (Join-Path $root "tracker-native\$f"))) { throw "tracker-native\$f is missing: run build.ps1 first" }
}

if (-not (Test-Path "$rt\jre\bin\javaw.exe")) {
    $t = "C:\Program Files\Tracker"
    if (-not (Test-Path "$t\OpenJDK-21.0.5.jre\bin\javaw.exe")) { throw "Install Tracker 6.3.5 in $t first (its Java runtime and Xuggle are bundled)" }
    New-Item -ItemType Directory -Force $rt | Out-Null
    robocopy "$t\OpenJDK-21.0.5.jre" "$rt\jre" /E /NFL /NDL /NJH /NJS /NP | Out-Null
    robocopy "$t\Xuggle" "$rt\Xuggle" /E /NFL /NDL /NJH /NJS /NP | Out-Null
    Copy-Item "$t\fontconfig.properties", "$t\tracker.ico", "$t\trk.ico" $rt
}

if (Test-Path $out) { Remove-Item $out -Recurse -Force }
$obj = Join-Path $out "obj"
$pay = Join-Path $out "payload"
New-Item -ItemType Directory -Force $obj, $pay | Out-Null

Set-Content (Join-Path $obj "Version.cs") "static partial class Build { public const string Version = `"$Version`"; }" -Encoding UTF8

$common = @("/nologo", "/optimize+", "/platform:anycpu", "/target:winexe",
    "/win32icon:$rt\tracker.ico", "/win32manifest:$here\app.manifest",
    "/r:System.Windows.Forms.dll", "/r:System.Drawing.dll", "/r:Microsoft.CSharp.dll", "/r:System.Core.dll",
    "/r:$fw\System.IO.Compression.dll", "/r:$fw\System.IO.Compression.FileSystem.dll")

function Compile($exe, [string[]]$sources, [string[]]$extra) {
    & $csc @common @extra "/out:$exe" @sources
    if ($LASTEXITCODE -ne 0) { throw "csc failed for $exe" }
}

Compile "$pay\Tracker-Rust.exe" @("$here\Launcher.cs", "$obj\Version.cs") @()
Compile "$pay\Uninstall.exe" @("$here\Uninstall.cs", "$here\Common.cs", "$obj\Version.cs") @()

Copy-Item "$root\tracker-native\tracker-native.jar", "$root\tracker-native\tmnative.dll", "$root\LICENSE", "$rt\fontconfig.properties" $pay
(Get-Content "$here\README.txt" -Raw).Replace("@VERSION@", $Version) | Set-Content "$pay\README.txt" -Encoding UTF8
robocopy "$rt\jre" "$pay\jre" /E /NFL /NDL /NJH /NJS /NP | Out-Null
robocopy "$rt\Xuggle" "$pay\Xuggle" /E /NFL /NDL /NJH /NJS /NP | Out-Null

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = Join-Path $obj "payload.zip"
[IO.Compression.ZipFile]::CreateFromDirectory($pay, $zip, [IO.Compression.CompressionLevel]::Optimal, $false)

$setup = Join-Path $out "Tracker-Rust-Setup-$Version.exe"
Compile $setup @("$here\Setup.cs", "$here\Common.cs", "$obj\Version.cs") @("/resource:$zip,payload.zip")

"{0}  {1:N1} MB" -f $setup, ((Get-Item $setup).Length / 1MB)
