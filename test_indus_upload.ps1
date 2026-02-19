# Test Indus App Store Upload
# Load environment variables
if (Test-Path .env.local) {
    Get-Content .env.local | ForEach-Object {
        if ($_ -match '^([^=]+)=(.*)$') {
            [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
        }
    }
}

$INDUS_API_KEY = $env:INDUS_API_KEY
$PACKAGE_NAME = "com.yourname.pdftoolkit"
$AAB_FILE = "app\build\outputs\bundle\release\app-release.aab"
$KEYSTORE_FILE = "release\pdftoolkit-release.jks"

# Check if files exist
if (-not (Test-Path $AAB_FILE)) {
    Write-Host "Error: AAB file not found at $AAB_FILE" -ForegroundColor Red
    Write-Host "Please build the release AAB first: .\gradlew.bat bundleRelease"
    exit 1
}

if (-not (Test-Path $KEYSTORE_FILE)) {
    Write-Host "Error: Keystore file not found at $KEYSTORE_FILE" -ForegroundColor Red
    exit 1
}

Write-Host "Testing Indus App Store Upload..." -ForegroundColor Cyan
Write-Host "Package: $PACKAGE_NAME"
Write-Host "AAB File: $AAB_FILE"
Write-Host "Keystore: $KEYSTORE_FILE"
Write-Host ""

# Read keystore credentials from keystore.properties
$keystoreProps = @{}
if (Test-Path "keystore.properties") {
    Get-Content "keystore.properties" | ForEach-Object {
        if ($_ -match '^([^=]+)=(.*)$') {
            $keystoreProps[$matches[1]] = $matches[2]
        }
    }
}

$KEY_PASSWORD = $keystoreProps['keyPassword']
$KEYSTORE_ALIAS = $keystoreProps['keyAlias']
$KEYSTORE_PASSWORD = $keystoreProps['storePassword']

Write-Host "Uploading to Indus App Store API..." -ForegroundColor Yellow

# Use curl to upload
$url = "https://developer-api.indusappstore.com/devtools/aab/upgrade/$PACKAGE_NAME"

$response = curl.exe -X POST $url `
    -H "Authorization: $INDUS_API_KEY" `
    -F "file=@$AAB_FILE" `
    -F "file=@$KEYSTORE_FILE" `
    -F "keyPassword=$KEY_PASSWORD" `
    -F "keystoreAlias=$KEYSTORE_ALIAS" `
    -F "keystorePassword=$KEYSTORE_PASSWORD" `
    -w "`n%{http_code}" `
    -s

$lines = $response -split "`n"
$httpCode = $lines[-1]
$body = $lines[0..($lines.Length-2)] -join "`n"

Write-Host ""
Write-Host "HTTP Status Code: $httpCode" -ForegroundColor $(if ($httpCode -ge 200 -and $httpCode -lt 300) { 'Green' } else { 'Red' })
Write-Host "Response Body:" -ForegroundColor Cyan
Write-Host $body

if ($httpCode -ge 200 -and $httpCode -lt 300) {
    Write-Host "`nSuccess! AAB uploaded to Indus App Store" -ForegroundColor Green
} else {
    Write-Host "`nFailed to upload to Indus App Store" -ForegroundColor Red
    Write-Host "Check the error message above for details" -ForegroundColor Yellow
}
