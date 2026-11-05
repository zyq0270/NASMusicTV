# Standalone Kotlin compile check (NO Gradle): compiles the qianli port package
# plus the gate test file with kotlin-compiler-embeddable from the Gradle cache.
#
# Usage (from repo root):
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/qianli_kotlinc_check.ps1
#
# NOTE: keep this file ASCII-only -- Windows PowerShell 5.1 reads BOM-less .ps1
# files with the ANSI code page (CP936 here) and would mangle non-ASCII text.
$ErrorActionPreference = "Stop"
$g = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1"
function Jar($group, $name, $ver) {
  $p = Get-ChildItem "$g\$group\$name\$ver" -Recurse -Filter "$name-$ver.jar" -ErrorAction SilentlyContinue |
       Where-Object { $_.Name -notmatch "sources|javadoc" } | Select-Object -First 1
  if (-not $p) { throw ("missing jar: " + $group + ":" + $name + ":" + $ver) }
  return $p.FullName
}
$compiler = Jar "org.jetbrains.kotlin" "kotlin-compiler-embeddable" "2.3.10"
$stdlib   = Jar "org.jetbrains.kotlin" "kotlin-stdlib" "2.3.20"
$scriptRt = Jar "org.jetbrains.kotlin" "kotlin-script-runtime" "2.3.10"
$daemon   = Jar "org.jetbrains.kotlin" "kotlin-daemon-embeddable" "2.3.10"
$gson     = Jar "com.google.code.gson" "gson" "2.10.1"
$junit    = Jar "junit" "junit" "4.13.2"
$hamcrest = Jar "org.hamcrest" "hamcrest-core" "1.3"
$annotations = Jar "org.jetbrains" "annotations" "13.0"
$reflect  = Jar "org.jetbrains.kotlin" "kotlin-reflect" "2.3.20"
$coroutines = Jar "org.jetbrains.kotlinx" "kotlinx-coroutines-core-jvm" "1.10.2"

$out = Join-Path (Join-Path $env:TEMP "opencode") "ktqianli"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$cp = "$stdlib;$gson;$junit;$hamcrest;$annotations"

$srcs = @()
$srcs += Get-ChildItem "app/src/main/java/com/nasmusic/tv/visualizer/qianli/port" -Filter "*.kt" | ForEach-Object { $_.FullName }
$srcs += (Resolve-Path "app/src/test/java/com/nasmusic/tv/visualizer/qianli/ShanShuiPortGateTest.kt").Path

$java = "C:\Program Files\Android\Android Studio\jbr\bin\java.exe"
$kargs = @(
  "-cp", "$compiler;$stdlib;$scriptRt;$daemon;$annotations;$reflect;$coroutines",
  "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
  "-nowarn", "-jvm-target", "17", "-no-reflect",
  "-classpath", $cp,
  "-d", $out
) + $srcs

& $java @kargs
if ($LASTEXITCODE -ne 0) { Write-Output ("COMPILE FAILED (" + $LASTEXITCODE + ")"); exit $LASTEXITCODE }
Write-Output ("COMPILE OK -> " + $out)
exit 0
