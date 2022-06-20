$user=$args[0]
$pwd=$args[1]
$server=$args[2]
$username = "$user"
$password = ConvertTo-SecureString "$pwd" -AsPlainText -Force
$creds = New-Object System.Management.Automation.PSCredential -ArgumentList ($username, $password)
echo "*** IIS Status ***"
invoke-command -computername  "$server" -Credential $creds -scriptblock {iisreset /status}
#Invoke-Command -ComputerName $server -Authentication Negotiate -ScriptBlock {iisreset /status} -Credential $creds