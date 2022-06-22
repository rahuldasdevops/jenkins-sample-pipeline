def call(Map params) {
    THEJOB="${JOB_NAME.substring(JOB_NAME.lastIndexOf('/') + 1, JOB_NAME.length())}"
    currentBuild.displayName = "$THEJOB # " +currentBuild.number
    try {
        pipeline {
            agent {
                label "${params.agent}"
            }
            environment {
                SQ_SCANNER = tool name: "${params.SQ_SCANNER}"
                SQ_ENV = "${params.SQ_ENV}"
                JENKINS_JENKINSSVC_LOGIN = "${params.JENKINS_JENKINSSVC_LOGIN}"
                NEXUS_CREDENTIAL_ID = "${params.NEXUS_CREDENTIAL_ID}"
                NEXUS_URL = "${params.NEXUS_URL}"
                def name = ""
                def version = ""
                def arti = ""
                def status = ""
                def signArti = ""
                def repo = ""
                def tag_version = ""
            }
            stages {
                stage('SCM') {
                    steps {
                        cleanWs()
                        git branch: "$params.branch", credentialsId: "${params.GIT_PWD}", url: "$params.repoUrl"
                    }
                }
                stage('Install Dependencies') {
                    steps {
                        sh label: '', script: '''npm config set proxy http://<proxyserver:port>
						 cd nodeJS && npm install && npm install jest-junit npm-pack-all jest-sonar-reporter
						  npm config set proxy "" '''
                    }
                }
                stage('Scan Vulnerable Dependencies'){
                    steps{
                        dependencyCheck additionalArguments: 'dependency-check.sh -f HTML -f JSON -f XML -s nodeJS -o nodeJS --proxyserver <proxyserver> --proxyport <port> --disableAssembly', odcInstallation: '7.1.0'
                    }
                }
                stage('Publish Dependency-Check Report'){
                    steps{
                        //dependencyCheckPublisher failedTotalCritical: 1, failedTotalHigh: 1, failedTotalMedium: 1, pattern: 'nodeJS/dependency-check-report.xml'
                        dependencyCheckPublisher pattern: 'nodeJS/dependency-check-report.xml'
                      /*  script{
                            if(currentBuild.result == 'FAILURE'){
                                error('FAILED: Dependency Checker found vulnerable dependency of Critical/High/Medium Severity.')
                            }
                        } */
                    }
                }
                stage('Check Config') {
                    steps {
                        script {
                            name = sh(script: '''n=$(node -p "require(\'./nodeJS/package.json\').name") 
								echo $n''', returnStdout: true).trim()
                            version = sh(script: '''v=$(node -p "require(\'./nodeJS/package.json\').version") 
								echo $v''', returnStdout: true).trim()
                        }
                        sh "echo Application name is : $name"
                        sh "echo Application version is : $version"
                    }
                }
                stage('Test') {
                    steps {
                        //sh "cd nodeJS && npm test -- --coverage && npm test -- --ci --testResultsProcessor=jest-junit"
                        sh "cd nodeJS && npm test -- --coverage && npm test -- --ci --reporters=default --reporters=jest-junit"
                    }
                    post {
                        always {
                            junit allowEmptyResults: true, testResults: 'nodeJS/junit.xml'
                        }
                    }
                }
                stage('SonarQube analysis') {
                    steps {
                        withSonarQubeEnv("$SQ_ENV") {
                            //sh "cd nodeJS && $SQ_SCANNER/bin/sonar-scanner scan -Dsonar.projectName=pipelines-nodejs_PIV -Dsonar.projectKey=PIV_nodejs -Dsonar.sources=src -Dsonar.javascript.lcov.reportPaths=coverage/lcov.info -Dsonar.projectVersion=$BUILD_ID"
                            sh "cd nodeJS && sh $SQ_SCANNER/bin/sonar-scanner scan -Dsonar.projectName=pipelines-nodejs_PIV -Dsonar.projectKey=PIV_nodejs -Dsonar.sources=${params.sqSource} -Dsonar.tests=${params.sqTest} -Dsonar.javascript.lcov.reportPaths=coverage/lcov.info -Dsonar.testExecutionReportPaths=test-report.xml -Dsonar.dependencyCheck.jsonReportPath=\"dependency-check-report.json\" -Dsonar.dependencyCheck.htmlReportPath=\"dependency-check-report.html\" -Dsonar.dependencyCheck.summarize=true -Dsonar.projectVersion=$BUILD_ID"
                        }
                        publishCodeCoverage jacocoPathPattern: '**/target/site/*/jacoco.xml', lcovPathPattern: '**/coverage/lcov.info'
                    }
                }
                stage("Quality Gate") {
                    steps {
                        script {
                            sleep(10)
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
                /*  stage('Build'){
                steps{
                    sh "npm run build"
                }
            } */
                stage('Artefact') {
                    steps {
                        sh "cd nodeJS && node node_modules/npm-pack-all"
                        script {
                            arti = sh(script: "echo nodeJS/*$version.$params.artifactType", returnStdout: true).trim()
                        }
                       // signArti = $arti.sign
                        sh "echo Shasum: && sha1sum $arti"
                    }
                }
                stage('Cert Sign'){
                    steps{
                        withCredentials([string(credentialsId: "$params.CODE_SIGN_LOGIN", variable: 'pwd')]) {

                            sh "openssl dgst -binary -sha256 $arti > hash"
                            sh "openssl pkeyutl -sign -in hash -inkey ${params.codeSign_privateKey_master} -pkeyopt digest:sha256 -keyform PEM -out sign -passin pass:$pwd"
                        }
                    }
                }
                stage('Verify Signed Artifact'){
                    steps{
                        sh "openssl dgst -sha256 -verify ${params.codeSign_pubKey_master} -signature sign $arti"
                    }
                }
                stage("Upload artefact to Nexus") {
                    steps {
                        echo "**************** Uploading artefact to Nexus**************"
                        script{
                            sh "mkdir HelloWorld && cp sign HelloWorld && cp $arti HelloWorld && tar -cvf HelloWorld.tgz HelloWorld"
                        }

                        //nexusArtifactUploader artifacts: [[artifactId: "HelloWorld", classifier: '', file: "$arti", type: "${params.artifactType}"]], credentialsId: "$NEXUS_CREDENTIAL_ID", groupId: "PIV_nodejs", nexusUrl: "$NEXUS_URL", nexusVersion: 'nexus3', protocol: 'https', repository: "PIV", version: "$version"
                        nexusArtifactUploader artifacts: [[artifactId: "HelloWorld", classifier: '', file: "HelloWorld.tgz", type: "${params.artifactType}"]], credentialsId: "$NEXUS_CREDENTIAL_ID", groupId: "PIV_nodejs", nexusUrl: "$NEXUS_URL", nexusVersion: 'nexus3', protocol: 'https', repository: "PIV", version: "$version"
                    }
                }
                stage("Create Tag"){
                    steps{
                        script {
                            if ("$Tag" == 'Yes') {
                                withCredentials([usernamePassword(credentialsId: "${params.GIT_PWD}", passwordVariable: 'pwd', usernameVariable: 'user')]) {
                                    //sh "cp ${params.scriptPath}/git_tag ${WORKSPACE} && ./git_tag $user $pwd ${params.gitreponame} && git tag v1.0.${BUILD_ID}.${params.env}  && git push --tags && git remote set-url origin ''"
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
                    steps {
                        echo "**************** Downloading Artefact from Nexus**************"
                        withCredentials([usernamePassword(credentialsId: "$NEXUS_CREDENTIAL_ID", passwordVariable: 'pwd', usernameVariable: 'user')]) {
                            sh "${params.scriptPath}/nx_download $user $pwd $NEXUS_URL PIV PIV_nodejs HelloWorld $version HelloWorld-$version.$params.artifactType"
                        }
                    }
                }
                stage("Validate shasum"){
                    steps {
                        withCredentials([usernamePassword(credentialsId: params.NEXUS_CREDENTIAL_ID, passwordVariable: 'pwd', usernameVariable: 'user')]) {
                            sh "${params.scriptPath}/nx_download $user $pwd $NEXUS_URL PIV PIV_nodejs HelloWorld $version HelloWorld-$version.${params.artifactType}.sha1"
                        }
                        script {
                            aSha = sh(script: '''a=$(echo $(sha1sum *$version.tgz)|awk \'{print $1}\')
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
                stage('Verify downloaded Artifact'){
                    steps{
                        script{
                            sh "rm -rf HelloWorld && tar -xvf *$version.${params.artifactType}"
                        }
                        sh "cd HelloWorld && openssl dgst -sha256 -verify ${params.codeSign_pubKey_master} -signature sign *.${params.artifactType}"
                    }
                }
                stage('Run functional Test'){
                    steps{
                        build 'functionalTest_nodejs'
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
                                    sh """export https_proxy=http://<proxyserver:port> && curl \
                                            -X POST \
                                            -H "Accept: application/vnd.github.v3+json" \
                                            -H "Authorization: bearer $pwd" \
                                            https://api.github.com/repos/EdisonInternational/$repo/releases \
                                            -d '{"tag_name":"$tag_version","target_commitish":"${params.branch}","name":"release-$params.branch-$tag_version.${params.env}","body":"This release has been created from NodeJS CICD pipeline for PIV only...$BUILD_URL","draft":false,"prerelease":false,"generate_release_notes":true}'
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

                    }
                }
                nexusArtifactUploader artifacts: [[artifactId: "HelloWorld_Log", classifier: '', file: "consoleLog_CD.txt", type: "txt"]], credentialsId: "$params.NEXUS_CREDENTIAL_ID", groupId: "PIV_nodejs", nexusUrl: "$params.NEXUS_URL", nexusVersion: 'nexus3', protocol: 'https', repository: "PIV", version: "$version-$BUILD_ID"

            }
        }
    }
}
