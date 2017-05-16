$ErrorActionPreference = "Stop"

$PSVersionTable.PSVersion

function InstallAppveyorTools
{
	$travisUtilsVersion = "33"
	$localPath = "$env:USERPROFILE\.local"
	$tmp = [IO.Path]::GetTempFileName() | Rename-Item -NewName { [IO.Path]::ChangeExtension($_,".tmp.zip") } -PassThru
	wget "https://github.com/SonarSource/travis-utils/archive/v$travisUtilsVersion.zip" -OutFile $tmp
	
	Expand-Archive -Force -Path $tmp -DestinationPath $localPath
    
	$mavenLocal = "$env:USERPROFILE\.m2"
	if (-not(Test-Path $mavenLocal))
	{
		mkdir $mavenLocal | Out-Null
	}
	echo "Installing Travis Utils public Maven settings.xml into $mavenLocal"
	Copy-Item "$localPath\travis-utils-$travisUtilsVersion\m2\settings-public.xml" "$mavenLocal\settings.xml"

	if (!(Test-Path env:\APPVEYOR_HTTP_PROXY_PORT)) {
		# No proxy port set in environment
		Write-Host "Skip writing proxy in maven settings file, no proxy set" -ForegroundColor Yellow
		return
	}

	Get-Content "$mavenLocal\settings.xml" | select-string -pattern '</settings>' -notmatch | Out-File "$mavenLocal\settings.xml"

	$mavenProxyConfig = '
	<proxies>
		<proxy>
		<active>true</active>
		<protocol>http</protocol>
		<host>' + $env:APPVEYOR_HTTP_PROXY_IP + '</host>
		<port>' + $env:APPVEYOR_HTTP_PROXY_PORT + '</port>
		</proxy>
	</proxies>
	</settings>'

	Add-Content "$mavenLocal\settings.xml" $mavenProxyConfig
}

function CheckLastExitCode
{
    param ([int[]]$SuccessCodes = @(0))

    if ($SuccessCodes -notcontains $LastExitCode)
	{
        $msg = @"
EXE RETURNED EXIT CODE $LastExitCode
CALLSTACK:$(Get-PSCallStack | Out-String)
"@
        throw $msg
    }
}

InstallAppveyorTools

mvn verify "-B" "-e" "-V" "-Dtycho.disableP2Mirrors=true"
CheckLastExitCode
