$user=$args[0]
$pwd=$args[1]
$server=$args[2]
$service=$args[3]
$username = "$user"
$password = ConvertTo-SecureString "$pwd" -AsPlainText -Force
$creds = New-Object System.Management.Automation.PSCredential -ArgumentList ($username, $password)
$myservice = Get-WmiObject -Class Win32_Service -ComputerName $server ` -Credential $creds -Filter "Name='$service'"
echo "*** Service Status ***"
$myservice.State