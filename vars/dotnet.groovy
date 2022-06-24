def call(Map params) {
    THEJOB = "${JOB_NAME.substring(JOB_NAME.lastIndexOf('/') + 1, JOB_NAME.length())}"
    currentBuild.displayName = "$THEJOB # " + currentBuild.number
    try {

        pipeline {
            agent {
                label "${params.agent}"
            }
            environment {
                MSBUILD_2017 = tool name: "${params.MSBUILD_2017}"
                jdk_home = tool name: "${params.jdk_home_win}"
                SQ_MSBUILD_SCANNNER = tool name: "${params.SQ_MSBUILD_SCANNNER}"
            }
            stages {
                stage('SCM') {
                    steps {
                        cleanWs()
                        git branch: "$params.branch", credentialsId: "$params.GIT_PWD", url: "$params.repoUrl"

                    }
                }
                stage("Install Dependencies") {
                    steps {
                        script {
                            try {
                                bat "$params.nugetExePath config -set http_proxy=http://<proxyserver:port>"
                                bat "$params.nugetExePath config -set https_proxy=http://<proxyserver:port>"
                                bat "$params.nugetExePath restore $params.solutionFile"
                            }
                            catch (err) {
                                bat 'echo "Either this project has 0 dependencies or Issue with Nuget "'
                            }
                        }
                    }
                }
                stage('Scan Vulnerable Dependencies'){
                    steps{
                        dependencyCheck additionalArguments: 'dependency-check.bat -f HTML -f JSON -f XML -s dotNet -o dotNet --proxyserver <proxyserver> --proxyport <port> --disableAssembly', odcInstallation: '7.1.0'
                    }
                }
                stage('Publish Dependency-Check Report'){
                    steps{
                        dependencyCheckPublisher pattern: 'dotNet/dependency-check-report.xml'
                    }
                }
                stage('SonarQube Analysis , Build & UT') {
                    steps {
                        withEnv(["JAVA_HOME=$jdk_home_win"]) {
                            withSonarQubeEnv("$params.SQ_ENV") {
                                //bat "$SQ_MSBUILD_SCANNNER begin /k:PIV_dotNet /n:PIV_dotNet /d:sonar.verbose=true /v:$BUILD_ID /d:sonar.vbnet.nunit.reportsPaths=dotNet\\TestResult.xml /d:sonar.sourceEncoding=windows-1252 /d:sonar.vbnet.opencover.reportsPaths=\"dotNet\\OpenCover.xml"
                                bat "$SQ_MSBUILD_SCANNNER begin /k:PIV_dotNet /n:PIV_dotNet /d:sonar.verbose=true /v:$BUILD_ID /d:sonar.vbnet.nunit.reportsPaths=dotNet\\TestResult.xml /d:sonar.sourceEncoding=windows-1252 /d:sonar.vbnet.opencover.reportsPaths=\"dotNet\\OpenCover.xml\" /d:sonar.dependencyCheck.jsonReportPath=\"${WORKSPACE}\\dotNet\\dependency-check-report.json\" /d:sonar.dependencyCheck.htmlReportPath=\"${WORKSPACE}\\dotNet\\dependency-check-report.html\" /d:sonar.dependencyCheck.summarize=true"
                                bat "$MSBUILD_2017 $params.solutionFile /t:Rebuild"
                                bat "$params.OPEN_COVER_EXE -target:$params.NUNIT -targetargs:\"$params.utfilePath --result=dotNet\\TestResult.xml;format=nunit2\" -output:\"dotNet\\OpenCover.xml\" -register:user"
                                bat "$SQ_MSBUILD_SCANNNER end"
                            }
                        }
                    }
                }
                stage('Publish Unit test Result') {
                    steps {
                        nunit testResultsPattern: 'dotNet\\TestResult.xml'
                    }
                }
                stage("Quality Gate") {
                    steps {
                        script{
                            sleep(10)
                            def qualitygate = waitForQualityGate()

                            if(qualitygate.status != "OK")
                            {
                                sh "echo Not Qualified"
                                error("Build Has Not Qualified due to QA gate condition has failed")
                            }
                            else{
                                sh "echo Qualified"
                            }
                        }
                    }
                }
                stage('Sign and Create Artefact') {
                    steps {
                        bat "mkdir ${params.artifactName}"
                        bat "copy ${params.artifact}\\*.* ${params.artifactName}\\ /y"

                        // ********** Only ignoring [CONFIG and JSON] files
                        bat "cd Hello-World & del *.config,*.json"
                        withCredentials([string(credentialsId: "${params.CODE_SIGN_LOGIN}", variable: 'pwd')]) {
                            //bat "${params.signtool_windows} sign /f ${params.keystore_windows_pfx} /p $pwd /t http://timestamp.digicert.com /fd SHA256 $WORKSPACE\\${params.artifactName}\\${params.signArti_exe}"
                            bat "${params.signtool_windows} sign /f ${params.keystore_windows_pfx} /p $pwd /fd SHA256 $WORKSPACE\\${params.artifactName}\\${params.signArti_exe}"
                        }
                        bat "${params.JAR_PATH} -cMf ${params.artifactName}.${params.artifactType} -C ${params.artifactName}\\ ."

                    }
                }
                stage('Verify the artifact') {
                    steps {
                        bat "${params.signtool_windows} verify /pa $WORKSPACE\\${params.artifactName}\\${params.signArti_exe}"
                    }
                }
                stage('Upload Artefact') {
                    steps {
                        script {
                            def sha1 = powershell label: '', returnStdout: true, script: "((Get-FileHash -algorithm sha1 ${params.artifactName}.${params.artifactType}).Hash).ToLower()"
                            echo "ShaSum for ${params.artifactName}.${params.artifactType} is $sha1"
                        }
                        nexusArtifactUploader artifacts: [[artifactId: "HelloWorld", classifier: '', file: "${params.artifactName}.${params.artifactType}", type: "${params.artifactType}"]], credentialsId: "${params.NEXUS_CREDENTIAL_ID}", groupId: "PIV_dotNet", nexusUrl: "${params.NEXUS_URL}", nexusVersion: 'nexus3', protocol: 'https', repository: "PIV", version: "$BUILD_ID"
                    }
                }
                stage("Create Tag"){
                    steps{
                        script {
                            if ("$Tag" == 'Yes') {
                                withCredentials([usernamePassword(credentialsId: "${params.GIT_PWD}", passwordVariable: 'pwd', usernameVariable: 'user')]) {

                                    bat "git show-ref --tags -d"
                                    bat "copy ${params.scriptPath_win}\\git_tag.bat ${WORKSPACE}"
                                    bat "./git_tag.bat $user $pwd ${params.gitreponame}"
                                    bat "git config --global http.proxy http://<proxyserver:port>"
                                    bat "git config --global https.proxy http://<proxyserver:port>"
                                    bat "copy ${params.scriptPath_win}\\git_auto_increament.ps1 ${WORKSPACE}"
                                    powershell ".\\git_auto_increament.ps1 $Upgrade"
                                    bat "git remote set-url origin ''"
                                    bat "git show-ref --tags -d"
                                }
                            } else {
                                echo "Tag has selected as 'No'"
                            }
                        }
                    }
                }
                stage('Download Artefact') {
                    steps {
                        bat "copy ${params.scriptPath_win}\\nx_download.bat ."
                        withCredentials([usernamePassword(credentialsId: "${params.NEXUS_CREDENTIAL_ID}", passwordVariable: 'pwd', usernameVariable: 'user')]) {
                            bat "./nx_download.bat $user $pwd $BUILD_ID PIV PIV_dotNet HelloWorld . .${params.artifactType}"
                        }
                        bat "mkdir ${params.artifactName}-$BUILD_ID"
                        bat "cd ${params.artifactName}-$BUILD_ID & ${params.JAR_PATH} -xf \"$WORKSPACE\\HelloWorld-$BUILD_ID.${params.artifactType}\""
                    }
                }
                stage('Validate Shasum') {
                    steps {
                        withCredentials([usernamePassword(credentialsId: "${params.NEXUS_CREDENTIAL_ID}", passwordVariable: 'pwd', usernameVariable: 'user')]) {
                            script {
                                bat "./nx_download.bat $user $pwd $BUILD_ID PIV PIV_dotNet HelloWorld . .${params.artifactType}.sha1"
                                def aSha = powershell label: '', returnStdout: true, script: "((Get-FileHash -algorithm sha1 HelloWorld-${BUILD_ID}.zip).Hash).ToLower()"
                                echo "$aSha"
                                def bSha = powershell label: '', returnStdout: true, script: "(Get-Content  HelloWorld-${BUILD_ID}.zip.sha1).ToLower()"
                                echo "$bSha"
                                if ("$aSha" == "$bSha") {
                                    echo "equal"
                                } else {
                                    echo "Not equal"
                                    error("Build failed because of SHASUM checked failed")
                                }
                            }
                        }
                    }
                }
                stage('Verify Again downloaded Artifact') {
                    steps {
                        bat "${params.signtool_windows} verify /pa $WORKSPACE\\${params.artifactName}-$BUILD_ID\\${params.signArti_exe}"
                    }
                }
                stage('Run functional Test'){
                    steps{
                        build 'functionalTest_dotnet'
                    }
                }
                stage("Create release") {
                    steps{
                        script {
                            if ("$Tag" == 'Yes') {
                                withCredentials([usernamePassword(credentialsId: "${params.GIT_PWD}", passwordVariable: 'pwd', usernameVariable: 'user')]) {
                                    withEnv(["token=$pwd" , "REPONAME=${params.gitreponame}", "buildurl=$BUILD_URL", "environment=${params.env}", "branch=${params.branch}"]) {

                                        powershell '''
                                        $tag_version=git tag | Select-Object -Last 1
                                        $basicAuthValue = "Bearer $env:token"
                                        $headers = @{
                                            Authorization = $basicAuthValue
                                            \'Accept\' = \'application/vnd.github.v3+json\'
                                        }
                                        $body = @{
                                            "tag_name" = "$tag_version"
                                            "target_commitish" = "$env:branch"
                                            "name" = "release-$env:branch-$tag_version.$env:environment"
                                            "body" = "This release has been created from DotNet CICD Test pipeline for PIV only ...$env:buildurl"
                                            "generate_release_notes"= [bool]("true")
                                            }
                                        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
                                    
                                        $gitreponame="$env:REPONAME"
                                        $repo = echo $([IO.Path]::GetFileNameWithoutExtension("$gitreponame"))
                                        
                                        echo "******** Params ******"
                                        echo "$env:REPONAME, $env:buildurl, $env:environment , $env:branch, $repo"
                                        echo "$body|ConvertTo-Json"   
                                        
									    Invoke-WebRequest "https://api.github.com/repos/EdisonInternational/$repo/releases" -Method \'POST\' -Headers $headers -Body ($body|ConvertTo-Json) -ContentType "application/json" | Select-Object -Expand Content
									'''
                                    }

                                }
                            } else {
                                echo "Tag has selected as 'No'"
                            }
                        }
                    }
                } //end stage
            }
        }
    }//End of try block
    catch (getaddrinfo) {
        throw (getaddrinfo)
    }
    finally {
        node("$params.agent") {
            stage("Upload the log to Nexus") {
                echo "**************** Uploading Log to Nexus**************"
                withCredentials([usernamePassword(credentialsId: "$params.JENKINS_JENKINSSVC_LOGIN", passwordVariable: 'pwd', usernameVariable: 'user')]) {
                    bat "curl.exe -k -u  $user:$pwd ${BUILD_URL}\\consoleText --output consoleLog_CD.txt"

                }

                nexusArtifactUploader artifacts: [[artifactId: "HelloWorld_Log", classifier: '', file: "consoleLog_CD.txt", type: "txt"]], credentialsId: "$params.NEXUS_CREDENTIAL_ID", groupId: "PIV_dotNet", nexusUrl: "$params.NEXUS_URL", nexusVersion: 'nexus3', protocol: 'https', repository: "PIV", version: "$BUILD_ID"

            }
        }
    }
}