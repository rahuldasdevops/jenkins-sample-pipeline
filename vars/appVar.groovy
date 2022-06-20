def maven() {
    return [
			agent : "<jenkins agent>",
			agent_Win: "<jenkins windows agent>",
			branch : "<github branch name>",
			repoUrl : "https://github.com/XX/XX.git",
			artifactType : "war",
			gitreponame : "<reponame>.git"

    ]
}
def nodejs() {
	return [
			artifactType : "tgz",
			agent : "<jenkins agent>",
			branch : "<github branch name>",
			repoUrl : "https://github.com/XX/XX.git",
			sqSource : "xx,xy",
			sqTest : "__tests__",
			gitreponame : "<reponame>.git"

	]
}

def dotnet() {
	return [
			agent : "<jenkins agent>",
			branch : "<github branch name>",
            repoUrl : "https://github.com/XY/XX.git",
			solutionFile : '"<put sln path>"',
			utfilePath : "<dll path of UT>",
			artifact : "<artifact path>",
			artifactName : "Hello-World",
			artifactType : "zip",
			signArti_exe : "<artifact exe name>",
			gitreponame : "<reponame>.git"
	]
}