#get highest tag number

$VERSION=git tag | Select-Object -Last 1

#replace . with space so can split into an array
$VERSION_BITS=$VERSION -replace '\.',' ' -replace 'v'
$VERSION_BITS=$VERSION_BITS.Split(" ")

#get number parts and increase last one by 1
$VNUM1=$VERSION_BITS[0] -as [int]
$VNUM2=$VERSION_BITS[1] -as [int]
$VNUM3=$VERSION_BITS[2] -as [int]

$param = $args[0]
echo "You have selected $param "

if($param -eq "MAJOR"){
    echo "Update major version"
	
    $VNUM1=(($VNUM1)+1)
    $VNUM2=0
    $VNUM3=0
} elseif($param -eq "MINOR"){
	echo "Update minor version"
    $VNUM2=($VNUM2+1)
    $VNUM3=0
} else{
	echo "Update patch version"
    $VNUM3=($VNUM3+1)
}	

#create new tag
$NEW_TAG="v$VNUM1.$VNUM2.$VNUM3"

echo "Updating $VERSION to $NEW_TAG"

git tag $NEW_TAG
#git push --tags