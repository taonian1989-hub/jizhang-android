param([string]$ToolRoot)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
if (-not $ToolRoot) { $ToolRoot=Join-Path $projectRoot 'work\toolchain' }
$jdk=Get-ChildItem -LiteralPath (Join-Path $ToolRoot 'jdk') -Directory | Select-Object -First 1 -ExpandProperty FullName
$buildTools=Get-ChildItem -LiteralPath (Join-Path $ToolRoot 'build-tools') -Directory | Select-Object -First 1 -ExpandProperty FullName
$platform=Get-ChildItem -LiteralPath (Join-Path $ToolRoot 'platform') -Directory | Select-Object -First 1 -ExpandProperty FullName
$androidJar=Join-Path $platform 'android.jar'
$buildDir=Join-Path $projectRoot 'work\apk_build'
$outputDir=Join-Path $projectRoot 'outputs'
New-Item -ItemType Directory -Path $buildDir,$outputDir,(Join-Path $buildDir 'classes'),(Join-Path $buildDir 'dex') -Force | Out-Null
function Invoke-Checked([string]$Executable,[string[]]$Arguments) { & $Executable @Arguments; if ($LASTEXITCODE -ne 0) { throw ('Build command failed: '+$Executable) } }
Invoke-Checked (Join-Path $buildTools 'aapt2.exe') @('compile','--dir',(Join-Path $projectRoot 'app\src\main\res'),'-o',(Join-Path $buildDir 'resources.zip'))
[xml]$manifestXml=Get-Content -LiteralPath (Join-Path $projectRoot 'app\src\main\AndroidManifest.xml') -Raw
$manifestXml.DocumentElement.SetAttribute('package','io.jizhang.assistant')
$manifestPath=Join-Path $buildDir 'AndroidManifest.xml'
$manifestXml.Save($manifestPath)
Invoke-Checked (Join-Path $buildTools 'aapt2.exe') @('link','-o',(Join-Path $buildDir 'unsigned.apk'),'--manifest',$manifestPath,'-I',$androidJar,'-A',(Join-Path $projectRoot 'app\src\main\assets'),(Join-Path $buildDir 'resources.zip'))
$javaSources=@(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'app\src\main\java') -Filter '*.java' -Recurse | Select-Object -ExpandProperty FullName)
Invoke-Checked (Join-Path $jdk 'bin\javac.exe') (@('-encoding','UTF-8','-source','8','-target','8','-classpath',$androidJar,'-d',(Join-Path $buildDir 'classes'))+$javaSources)
Invoke-Checked (Join-Path $jdk 'bin\jar.exe') @('cf',(Join-Path $buildDir 'classes.jar'),'-C',(Join-Path $buildDir 'classes'),'.')
Invoke-Checked (Join-Path $jdk 'bin\java.exe') @('-cp',(Join-Path $buildTools 'lib\d8.jar'),'com.android.tools.r8.D8','--lib',$androidJar,'--min-api','26','--output',(Join-Path $buildDir 'dex'),(Join-Path $buildDir 'classes.jar'))
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apk=[IO.Compression.ZipFile]::Open((Join-Path $buildDir 'unsigned.apk'),[IO.Compression.ZipArchiveMode]::Update)
try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($apk,(Join-Path $buildDir 'dex\classes.dex'),'classes.dex',[IO.Compression.CompressionLevel]::Optimal) | Out-Null } finally { $apk.Dispose() }
Invoke-Checked (Join-Path $buildTools 'zipalign.exe') @('-f','4',(Join-Path $buildDir 'unsigned.apk'),(Join-Path $buildDir 'aligned.apk'))
$signDir=Join-Path $projectRoot 'work\signing'
New-Item -ItemType Directory -Path $signDir -Force | Out-Null
$keystore=Join-Path $signDir 'personal-preview.keystore'
if (-not (Test-Path -LiteralPath $keystore)) { Invoke-Checked (Join-Path $jdk 'bin\keytool.exe') @('-genkeypair','-keystore',$keystore,'-storepass','android','-keypass','android','-alias','personal-preview','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=Jizhang Personal Preview,O=Personal Use,C=CN') }
$apkPath=Join-Path $outputDir '记账_0.1.1_预览版.apk'
Invoke-Checked (Join-Path $jdk 'bin\java.exe') @('-jar',(Join-Path $buildTools 'lib\apksigner.jar'),'sign','--v4-signing-enabled','false','--ks',$keystore,'--ks-key-alias','personal-preview','--ks-pass','pass:android','--key-pass','pass:android','--out',$apkPath,(Join-Path $buildDir 'aligned.apk'))
Invoke-Checked (Join-Path $jdk 'bin\java.exe') @('-jar',(Join-Path $buildTools 'lib\apksigner.jar'),'verify','--verbose','--print-certs',$apkPath)
Invoke-Checked (Join-Path $buildTools 'aapt2.exe') @('dump','badging',$apkPath)
Get-FileHash -LiteralPath $apkPath -Algorithm SHA256 | Format-List
