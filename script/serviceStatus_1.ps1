$user=$args[0]
$pwd=$args[1]
$server=$args[2]
$service=$args[3]
$username = "$user"
$password = ConvertTo-SecureString "$pwd" -AsPlainText -Force
$creds = New-Object System.Management.Automation.PSCredential -ArgumentList ($username, $password)

Invoke-Command -ComputerName $server -Authentication Negotiate -ScriptBlock {param($p1) Get-Service -Name "$p1" | Format-List} -Credential $creds -ArgumentList $service