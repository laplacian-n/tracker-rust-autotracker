# usage: run_bench.ps1 <label> <affinity mask> <modes...>
# Runs Bench at high priority pinned to the given logical CPUs
# (0xFFF = the 6 P-cores of an i5-13450HX), so E-core scheduling and
# background load move the numbers less.
param([string]$Label, [string]$Affinity, [Parameter(ValueFromRemainingArguments)] [string[]]$Modes)
Set-Location $PSScriptRoot
$java = 'C:\Program Files\Tracker\OpenJDK-21.0.5.jre\bin\java.exe'
$cp = if ($Label -eq 'orig') { 'bench\out;lib\tracker.jar' } else { 'bench\out;patched;lib\tracker.jar' }
$out = Join-Path $env:TEMP "bench_$Label.txt"
$argList = @('-Djava.library.path=patched', '-cp', $cp, 'Bench', $Label)
if ($Modes) { $argList += $Modes }
$p = Start-Process -FilePath $java -ArgumentList $argList -NoNewWindow -PassThru -RedirectStandardOutput $out
$p.ProcessorAffinity = [IntPtr][Convert]::ToInt64($Affinity, 16)
$p.PriorityClass = 'High'
$p.WaitForExit()
Get-Content $out
