$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path "$PSScriptRoot\..").Path
$assetPath = "$repoRoot\app\src\main\assets\providers\whatsmyname\wmn-data.json"
$licensePath = "$repoRoot\app\src\main\assets\providers\whatsmyname\LICENSE.md"

$expectedDataHash = "779922223756F47D1512F81A5A2D0C69D19418FE5DF1A2A9406C7CF18CF68F34"
$expectedLicenseHash = "3EAB49AA5CABC24918C11AAB97DFE8873E0641317B898D989C993C4283A4D84B"

if (-Not (Test-Path $assetPath)) { Write-Host "Error: Asset file not found"; exit 1 }
if (-Not (Test-Path $licensePath)) { Write-Host "Error: License file not found"; exit 1 }

function Get-Sha256 {
    param([string]$FilePath)
    $bytes = [System.IO.File]::ReadAllBytes((Resolve-Path $FilePath).Path)
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    $hashBytes = $sha256.ComputeHash($bytes)
    return [System.BitConverter]::ToString($hashBytes).Replace("-", "").ToUpper()
}

$dataHash = Get-Sha256 $assetPath
$licenseHash = Get-Sha256 $licensePath

if ($dataHash -ne $expectedDataHash) { Write-Host "Error: Data Hash mismatch!"; exit 1 }
if ($licenseHash -ne $expectedLicenseHash) { Write-Host "Error: License Hash mismatch!"; exit 1 }

$fileInfo = Get-Item $assetPath
if ($fileInfo.Length -ne 258615) { Write-Host "Error: Expected exactly 258615 bytes."; exit 1 }

$jsonContent = Get-Content -Raw $assetPath
$json = $jsonContent | ConvertFrom-Json -Depth 20

# Verify top level
if (
    @($json.license).Count -eq 0 -or
    @($json.authors).Count -eq 0 -or
    @($json.categories).Count -eq 0 -or
    @($json.sites).Count -eq 0
) {
    Write-Host "Error: Missing top-level metadata."
    exit 1
}

$total = $json.sites.Count
$executable = 0

foreach ($site in $json.sites) {
    if ($site.name -eq $null) { continue }
    if ($site.valid -eq $false) { continue }
    if ($site.post_body -ne $null -and $site.post_body.ToString().Trim() -ne "") { continue }
    if ($site.cat -ne $null -and $site.cat.ToString().ToLower().Contains("nsfw")) { continue }
    if ($site.uri_check -eq $null -or -not $site.uri_check.Contains("{account}")) { continue }
    if ($site.uri_check.IndexOf("{account}") -ne $site.uri_check.LastIndexOf("{account}")) { continue }

    $testUri = $site.uri_check.Replace("{account}", "probe")
    if (-not $testUri.ToLower().StartsWith("https://")) { continue }

    try {
        $uri = [System.Uri]::new($testUri)
        if ([string]::IsNullOrWhiteSpace($uri.Host)) { continue }
    } catch {
        continue
    }

    $protection = @()
    if ($site.protection -ne $null) {
        foreach ($p in $site.protection) {
            $protection += $p.ToString().ToLower()
        }
    }
    if ("captcha" -in $protection -or "user-auth" -in $protection -or "anubis" -in $protection) { continue }

    if ($site.e_code -eq $null -or $site.m_code -eq $null) { continue }
    if ($site.e_code -lt 100 -or $site.e_code -gt 599) { continue }
    if ($site.m_code -lt 100 -or $site.m_code -gt 599) { continue }
    if ($site.e_code -eq $site.m_code -and [string]::IsNullOrWhiteSpace($site.e_string) -and [string]::IsNullOrWhiteSpace($site.m_string)) { continue }

    $executable++
}

Write-Host "Data Hash: $dataHash"
Write-Host "License Hash: $licenseHash"
Write-Host "Total sites: $total"
Write-Host "Executable sites: $executable"

if ($total -ne 716 -or $executable -ne 644) {
    Write-Host "Error: Count mismatch!"
    exit 1
}

Write-Host "All checks passed."
exit 0
