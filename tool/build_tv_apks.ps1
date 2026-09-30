# Builds the four TV release APKs (2 ABIs x 2 renderers) and copies them into
# dist/ under their release names:
#   PureLive-TV-arm64-v8a-impeller.apk
#   PureLive-TV-arm64-v8a-skia.apk
#   PureLive-TV-armeabi-v7a-impeller.apk
#   PureLive-TV-armeabi-v7a-skia.apk
#
# Run from the repo root (flutter must be on PATH, or pass -Flutter <path>):
#   powershell -ExecutionPolicy Bypass -File tool\build_tv_apks.ps1
param(
    # Flutter executable to use. Defaults to whatever `flutter` resolves to on
    # PATH; pass an absolute path to pin a specific SDK.
    [string]$Flutter = "flutter"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# android-arm + android-arm64 cover armeabi-v7a and arm64-v8a; --split-per-abi
# turns each flavor build into one APK per ABI, so two flutter invocations
# produce all four files. (A static gradle splits block is not an option: the
# Flutter Gradle plugin injects conflicting ndk.abiFilters on plain builds, and
# reset()s a static block when the flag is passed — see app/build.gradle.kts.)
foreach ($renderer in @("impeller", "skia")) {
    & $Flutter build apk --release --flavor $renderer --split-per-abi --target-platform "android-arm,android-arm64"
    if ($LASTEXITCODE -ne 0) {
        throw "flutter build apk failed for flavor '$renderer' (exit $LASTEXITCODE)"
    }
}

$out = Join-Path $root "build\app\outputs\flutter-apk"
$dist = Join-Path $root "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null

$names = @{
    "app-armeabi-v7a-impeller-release.apk" = "PureLive-TV-armeabi-v7a-impeller.apk"
    "app-arm64-v8a-impeller-release.apk"   = "PureLive-TV-arm64-v8a-impeller.apk"
    "app-armeabi-v7a-skia-release.apk"     = "PureLive-TV-armeabi-v7a-skia.apk"
    "app-arm64-v8a-skia-release.apk"       = "PureLive-TV-arm64-v8a-skia.apk"
}

foreach ($entry in $names.GetEnumerator()) {
    $src = Join-Path $out $entry.Key
    if (-not (Test-Path $src)) {
        throw "Expected build output missing: $src"
    }
    Copy-Item $src (Join-Path $dist $entry.Value) -Force
    Write-Host ("dist/{0} <- {1}" -f $entry.Value, $entry.Key)
}
