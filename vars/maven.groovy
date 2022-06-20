def call(Map params) {
    THEJOB="${JOB_NAME.substring(JOB_NAME.lastIndexOf('/') + 1, JOB_NAME.length())}"
    currentBuild.displayName = "$THEJOB # " +currentBuild.number
    try{
        pipeline {
            agent{
                label "${params.agent}"
            }
            environment{
                MVN_HOME = tool name: "${params.MVN_HOME_MASTER}"
                JDK_HOME = tool name: "${params.JDK_HOME_MASTER}"
                SQ_SCANNER = tool name: "${params.SQ_SCANNER}"
                SQ_ENV = "${params.SQ_ENV}"
                NEXUS_CREDENTIAL_ID = "${params.NEXUS_CREDENTIAL_ID}"
                NEXUS_URL = "${params.NEXUS_URL}"
                artifactType = "${params.artifactType}"
                def version = ""
                def repo = ""
                def tag_version = ""
            }
            stages {
                stage('SCM') {
                    steps {
                        cleanWs()
                        git branch: "$params.branch", credentialsId: "${params.GIT_PWD}", url: "$params.repoUrl"
                        script {
                            def pom = readMavenPom(file: "mavenJava/pom.xml")
                            version = pom.getVersion()
                            parts = "${version}".toString().split("-");
                            version = parts[0];
                        }
                    }
                }
                stage('Build'){
                    steps{
                        withEnv(["JAVA_HOME=$JDK_HOME"]) {
                            sh "cd mavenJava && $MVN_HOME/bin/mvn clean validate compile"
                        }

                    }
                }
                stage('Test'){
                    steps{
                        withEnv(["JAVA_HOME=$JDK_HOME"]) {
                            sh "cd mavenJava && $MVN_HOME/bin/mvn test"
                        }
                    }
                    post{
                        always{
                            junit 'mavenJava/target/surefire-reports/*.xml'
                        }
                    }
                }
                stage('SonarQube analysis with vulnerability test') {
                    steps {
                       script {

                                withEnv(["JAVA_HOME=$JDK_HOME"]) {
                                    sh "export https_proxy=<proxy server> && cd mavenJava && $MVN_HOME/bin/mvn dependency-check:check"
                                    withSonarQubeEnv("$SQ_ENV") {
                                        //sh "cd mavenJava && $SQ_SCANNER/bin/sonar-scanner scan -Dsonar.projectName=pipelines-java_PIV -Dsonar.projectKey=PIV -Dsonar.sources=. -Dsonar.java.binaries=target/classes  -Dsonar.java.test.binaries=target/test-classes -Dsonar.junit.reportPaths=target/surefire-reports -Dsonar.junit.reportsPath=target/surefire-reports -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml -Dsonar.coverage.exclusions=src/test/java/** -Dsonar.projectVersion=$BUILD_ID"
                                        sh "cd mavenJava && $SQ_SCANNER/bin/sonar-scanner scan -Dsonar.projectName=pipelines-java_PIV -Dsonar.projectKey=PIV -Dsonar.sources=. -Dsonar.java.binaries=target/classes  -Dsonar.java.test.binaries=target/test-classes -Dsonar.junit.reportPaths=target/surefire-reports -Dsonar.junit.reportsPath=target/surefire-reports -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml -Dsonar.coverage.exclusions=src/test/java/** -Dsonar.dependencyCheck.htmlReportPath=target/dependency-check-report.html -Dsonar.dependencyCheck.jsonReportPath=target/dependency-check-report.json -Dsonar.dependencyCheck.summarize=true -Dsonar.projectVersion=$BUILD_ID"
                                    }
                            }
                        }
                      //  dependencyCheckPublisher failedTotalCritical: 1, failedTotalHigh: 1, failedTotalMedium: 1, pattern: '**/dependency-check-report.xml'
                     /*   script{
                            if(currentBuild.result == 'FAILURE'){
                                error('FAILED: Dependency Checker found vulnerable dependency of Critical/High/Medium Severity.')
                            }
                        }*/
                        publishCodeCoverage jacocoPathPattern: '**/jacoco/jacoco.xml', lcovPathPattern: '**/coverage/lcov.info'
                    }
                }
                stage("Quality Gate") {
                    steps {
                        script{
                            sleep(10)
                            withEnv(["JAVA_HOME=$JDK_HOME"]) {
                                def qualitygate = waitForQualityGate()
                                if (qualitygate.status != "OK") {
                                    sh "echo Not Qualified"
                                    error("Build Has Not Qualified due to QA gate condition has failed")
                                } else {
                                    sh "echo Qualified"
                                }
                            }
                        }
                    }
                }
                stage('Artefact'){
                    steps{
                        withEnv(["JAVA_HOME=$JDK_HOME"]) {
                            sh "cd mavenJava && $MVN_HOME/bin/mvn package -Dmaven.test.skip=true"
                        }
                    }
                }
                stage('Cert Sign'){
                    steps{
                        withCredentials([string(credentialsId: "$params.CODE_SIGN_LOGIN", variable: 'pwd')]) {

                            sh "$JDK_HOME/bin/jarsigner  -J-Dhttp.proxyHost=<proxy server name> -J-Dhttp.proxyPort=80  -J-Dhttps.proxyHost=<proxy server name> -J-Dhttps.proxyPort=80 -tsa http://timestamp.digicert.com -keystore $params.keystore_master -storepass $pwd mavenJava/target/helloworld.war <alias name of code sign ssl>"
                        }
                    }
                }
                stage('Verify Signed Artifact'){
                    steps{
                        sh "$JDK_HOME/bin/jarsigner -verify -strict -verbose -keystore $params.keystore_master mavenJava/target/helloworld.war"
                    }
                }
                stage("Upload artefact to Nexus") {
                    steps{
                        echo "**************** Uploading artefact to Nexus**************"

                        nexusArtifactUploader artifacts: [[artifactId: "HelloWorld", classifier: '', file: "mavenJava/target/helloworld.war", type: "$artifactType"]], credentialsId: "$NEXUS_CREDENTIAL_ID", groupId: "PIV_maven", nexusUrl: "$NEXUS_URL", nexusVersion: 'nexus3', protocol: 'https', repository: "PIV", version: "$version"
                    }
                }
                stage("Create Tag"){
                    steps{
                        script {
                            if ("$Tag" == 'Yes') {
                                withCredentials([usernamePassword(credentialsId: "${params.GIT_PWD}", passwordVariable: 'pwd', usernameVariable: 'user')]) {
                                    sh "git describe --tags \$(git rev-list --tags --max-count=1)"
                                    sh "git show-ref --tags -d"
                                    sh "cp ${params.scriptPath}/git_* ${WORKSPACE} && ./git_tag $user $pwd ${params.gitreponame} && ./git_auto_increament $Upgrade && git remote set-url origin ''"
                                    sh "git describe --tags \$(git rev-list --tags --max-count=1)"
                                    sh "git show-ref --tags -d"
                                }
                            } else {
                                echo "Tag has selected as 'No'"
                            }
                        }
                    }
                }
                stage("Download artefact from Nexus") {
                    steps{
                        echo "**************** Downloading Artefact from Nexus**************"
                        withCredentials([usernamePassword(credentialsId: "$NEXUS_CREDENTIAL_ID", passwordVariable: 'pwd', usernameVariable: 'user')]) {
                            sh "${params.scriptPath}/nx_download $user $pwd $NEXUS_URL PIV PIV_maven HelloWorld $version HelloWorld-$version.$artifactType"
                        }
                    }
                }
                stage("Validate shasum"){
                    steps {
                        withCredentials([usernamePassword(credentialsId: params.NEXUS_CREDENTIAL_ID, passwordVariable: 'pwd', usernameVariable: 'user')]) {
                            sh "${params.scriptPath}/nx_download $user $pwd $NEXUS_URL PIV PIV_maven HelloWorld $version HelloWorld-$version.${params.artifactType}.sha1"
                        }
                        script {
                            aSha = sh(script: '''a=$(echo $(sha1sum *$version.war)|awk \'{print $1}\')
	                        echo $a''', returnStdout: true).trim();
                            bSha = sh(script: '''b=$(cat *.sha1)
	                        echo $b''', returnStdout: true).trim();

                            if ("$aSha" == "$bSha") {
                                echo "equal"
                                sh label: '', script: 'echo "Shasum is Equal" >> ConsoleLog.txt'
                            } else {
                                echo "Not equal"
                                sh label: '', script: 'echo "Shasum is Not equal. Deployment cannot be done." >> ConsoleLog.txt'
                                error("Build failed because of SHASUM checked failed")
                            }
                        }
                    }
                }
                stage('Verify Again Signed Artefact'){
                    steps{
                        sh "$JDK_HOME/bin/jarsigner -verify -strict -verbose -keystore $params.keystore_master HelloWorld-$version.$artifactType"
                    }
                }
                stage('Verify Signed Artifact in Windows'){
                    agent{
                        label "$params.agent_Win"
                    }
                    steps{
                        cleanWs()
                        bat "copy ${params.scriptPath_win}\\nx_download.bat ."
                        withCredentials([usernamePassword(credentialsId: "$NEXUS_CREDENTIAL_ID", passwordVariable: 'pwd', usernameVariable: 'user')]){
                            bat "./nx_download.bat $user $pwd $version PIV PIV_maven HelloWorld . .$artifactType"
                        }
                        bat "ren HelloWorld-$version.$artifactType helloworld.$artifactType"
                       // bat "$JDK_HOME_windows\\bin\\jarsigner' -verify -strict -verbose -keystore E:\\CodeSigning\\devopsCodeSigning.jks helloworld.$artifactType"
                        bat "${params.JarSigner_windows} -verify -strict -verbose -keystore ${params.keystore_windows} helloworld.$artifactType"
                    }
                }
                stage('Deploy into Tomcat9 Windows'){
                    agent{
                        label "$params.agent_Win"
                    }
                    steps{
                       // deploy adapters: [tomcat9(credentialsId: "$params.TOMCAT_DEPLOY_ID", path: '', url: "$params.tomcatUrl")], contextPath: 'helloworld', war: "helloworld.$artifactType"
                    echo "Tomcat is down.... due to missing of ssl configuration"
                    }
                }
                stage('Run functional Test'){
                    steps{
                        build 'functionalTest_mvn'
                    }
                }
                stage("Create release") {
                    steps{
                        script {
                            if ("$Tag" == 'Yes') {
                                withCredentials([usernamePassword(credentialsId: "${params.GIT_PWD}", passwordVariable: 'pwd', usernameVariable: 'user')]) {
                                    withEnv(["gitreponame=${params.gitreponame}"]) {
                                        repo=sh (script:'''echo $(basename $gitreponame .git)''',returnStdout:true).trim()
                                    }
                                    tag_version=sh (script:'''echo $(git tag | tail -1)''',returnStdout:true).trim()
                                    sh """export https_proxy=<proxy server>:<port> && curl \
                                            -X POST \
                                            -H "Accept: application/vnd.github.v3+json" \
                                            -H "Authorization: bearer $pwd" \
                                            https://api.github.com/repos/rahul-das4/$repo/releases \
                                            -d '{"tag_name":"$tag_version","target_commitish":"${params.branch}","name":"release-$params.branch-$tag_version","body":"This release has been created from Maven CICD pipeline for PIV only...$BUILD_URL","draft":false,"prerelease":false,"generate_release_notes":true}'
                                        """
                                }
                            } else {
                                echo "Tag has selected as 'No'"
                            }
                        }
                    }
                } //end of stage
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
                    script {

                        sh "curl -k -u  $user:$pwd \"${BUILD_URL}/consoleText\" --output consoleLog_CD.txt"
                        def pom = readMavenPom(file: "mavenJava/pom.xml")
                        version = pom.getVersion()
                        parts = "${version}".toString().split("-");
                        version = parts[0];
                    }
                }
                nexusArtifactUploader artifacts: [[artifactId: "HelloWorld_Log", classifier: '', file: "consoleLog_CD.txt", type: "txt"]], credentialsId: "$params.NEXUS_CREDENTIAL_ID", groupId: "PIV_maven", nexusUrl: "$params.NEXUS_URL", nexusVersion: 'nexus3', protocol: 'https', repository: "PIV", version: "$version-$BUILD_ID"

            }
        }
    }
}
