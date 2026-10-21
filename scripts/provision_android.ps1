param([string]$ToolRoot)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
if(-not $ToolRoot){$ToolRoot=Join-Path $projectRoot 'work\toolchain'}
New-Item -ItemType Directory -Path $ToolRoot -Force | Out-Null
$metadata=Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
$jdkPackage=$metadata[0].binary.package
$repositoryPath=Join-Path $ToolRoot 'android_repository.xml'
Invoke-WebRequest 'https://dl.google.com/android/repository/repository2-1.xml' -OutFile $repositoryPath
[xml]$repository=Get-Content -LiteralPath $repositoryPath -Raw
$nodes=$repository.SelectNodes('//*[local-name()="remotePackage"]')
$platform=@($nodes|Where-Object {$_.path -eq 'platforms;android-35'})[-1]
$buildTools=@($nodes|Where-Object {$_.path -eq 'build-tools;35.0.0'})[0]
$buildArchive=@($buildTools.archives.archive|Where-Object {$_.'host-os' -eq 'windows'})[0].complete
$platformArchive=$platform.archives.archive.complete
$packages=@(
 @{Name='jdk';Url=$jdkPackage.link;Hash=[string]$jdkPackage.checksum;Algorithm='SHA256'},
 @{Name='platform';Url=('https://dl.google.com/android/repository/'+$platformArchive.url);Hash=[string]$platformArchive.checksum;Algorithm='SHA1'},
 @{Name='build-tools';Url=('https://dl.google.com/android/repository/'+$buildArchive.url);Hash=[string]$buildArchive.checksum;Algorithm='SHA1'}
)
$packages|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $ToolRoot 'downloads.json') -Encoding utf8
foreach($package in $packages){
 if(-not $package.Hash){throw ('Missing official checksum for '+$package.Name)}
 $zip=Join-Path $ToolRoot ($package.Name+'.zip')
 if(-not(Test-Path -LiteralPath $zip)){Invoke-WebRequest -Uri $package.Url -OutFile $zip}
 if((Get-FileHash -LiteralPath $zip -Algorithm $package.Algorithm).Hash -ne $package.Hash){throw ('Checksum mismatch: '+$package.Name)}
 $destination=Join-Path $ToolRoot $package.Name
 if(-not(Test-Path -LiteralPath $destination)){Expand-Archive -LiteralPath $zip -DestinationPath $destination}
 Write-Output ($package.Name+' verified and ready')
}
