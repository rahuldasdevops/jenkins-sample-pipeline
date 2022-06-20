def azureTest() {
    return [
            MVN_HOME_MASTER : "<maven tool name>",
            NODE_HOME : "<npm home>",
            JDK_HOME_MASTER : "jdk tool",
            JarSigner_windows : '"C:\\Program Files\\Java\\jdk1.8.0_251\\bin\\jarsigner"',
            keystore_windows : "<codesign jks path>",
            keystore_master : "<codesign jks path>",
            codeSign_privateKey_master : "<codesign private key pem path>",
            codeSign_pubKey_master : "<codesign public key pem path>",
            CODE_SIGN_LOGIN : "code sign keystore pwd",
            TOMCAT_DEPLOY_ID: "",
            tomcatUrl: "",
            SQ_SCANNER : "<SQ scanner tool name>",
            SQ_ENV : "<SQ tool name>",
            GIT_PWD: "<github cred from jenkins>",
            NEXUS_CREDENTIAL_ID : "<nexus cred from jenkins>",
            NEXUS_URL : "<nexus url:port>",
            scriptPath : "<custom script path>",
            scriptPath_win : "<custom script path>",
            nugetExePath : "<nuget path>",
            MSBUILD_2017 : '<MSBuild tool name>',
            OPEN_COVER_EXE : '"<opencover exe path>"',
            NUNIT : '"C:\\Program Files (x86)\\NUnit.org\\nunit-console\\nunit3-console.exe"',
            JAR_PATH : '"C:\\Program Files\\Java\\jdk1.8.0_144\\bin\\jar.exe"',
            signtool_windows : '"C:\\Program Files (x86)\\Windows Kits\\10\\bin\\10.0.19041.0\\x64\\signtool.exe"',
            keystore_windows_pfx : "<code sign pfxs>"
    ]
}