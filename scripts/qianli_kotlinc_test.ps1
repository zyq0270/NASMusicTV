# Standalone JUnit runner for the qianli port gate test (NO Gradle).
# Requires scripts/qianli_kotlinc_check.ps1 to have compiled the classes first.
#
# Usage (from repo root):
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/qianli_kotlinc_test.ps1
#
# NOTE: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less .ps1
# files with the ANSI code page and would mangle non-ASCII text).
$ErrorActionPreference = "Stop"
$g = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1"
function Jar($group, $name, $ver) {
  $p = Get-ChildItem "$g\$group\$name\$ver" -Recurse -Filter "$name-$ver.jar" -ErrorAction SilentlyContinue |
       Where-Object { $_.Name -notmatch "sources|javadoc" } | Select-Object -First 1
  if (-not $p) { throw ("missing jar: " + $group + ":" + $name + ":" + $ver) }
  return $p.FullName
}
$stdlib   = Jar "org.jetbrains.kotlin" "kotlin-stdlib" "2.3.20"
$gson     = Jar "com.google.code.gson" "gson" "2.10.1"
$junit    = Jar "junit" "junit" "4.13.2"
$hamcrest = Jar "org.hamcrest" "hamcrest-core" "1.3"

$classes = Join-Path (Join-Path $env:TEMP "opencode") "ktqianli"
if (-not (Test-Path $classes)) { throw ("no compiled classes: " + $classes) }
$res = (Resolve-Path "app/src/test/resources").Path

$java = "C:\Program Files\Android\Android Studio\jbr\bin\java.exe"
$cp = "$classes;$res;$stdlib;$gson;$junit;$hamcrest"
& $java -cp $cp org.junit.runner.JUnitCore com.nasmusic.tv.visualizer.qianli.ShanShuiPortGateTest
exit $LASTEXITCODE
