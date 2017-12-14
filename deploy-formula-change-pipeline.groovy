def common = new com.mirantis.mk.Common()
def aptly = new com.mirantis.mk.Aptly()
def debian = new com.mirantis.mk.Debian()

def snapshot
try {
  snapshot = DEBIAN_SNAPSHOT
} catch (MissingPropertyException e) {
  snapshot = true
}
def debian_branch
try {
  debian_branch = DEBIAN_BRANCH
} catch (MissingPropertyException e) {
  debian_branch = null
}
def revisionPostfix
try {
  revisionPostfix = REVISION_POSTFIX
} catch (MissingPropertyException e) {
  revisionPostfix = ''
}

def lintianCheck
try {
  lintianCheck = LINTIAN_CHECK.toBoolean()
} catch (MissingPropertyException e) {
  lintianCheck = true
}

def buildPackage
try {
  buildPackage = BUILD_PACKAGE.toBoolean()
} catch (MissingPropertyException e) {
  buildPackage = true
}

def aptlyRepo
try {
  aptlyRepo = APTLY_REPO
} catch (MissingPropertyException e) {
  aptlyRepo = null
}

def uploadAptly
try {
  uploadAptly = UPLOAD_APTLY.toBoolean()
} catch (MissingPropertyException e) {
  uploadAptly = true
}


def timestamp = common.getDatetime()


node('python') {

    wrap([$class: 'BuildUser']) {
        if (env.BUILD_USER_ID) {
          buidDescr = "${env.BUILD_USER_ID}-${JOB_NAME}-${BUILD_NUMBER}"
        } else {
          buidDescr = "jenkins-${JOB_NAME}-${BUILD_NUMBER}"
        }
    }
    currentBuild.description = buidDescr
      if (aptlyRepo == '')
        aptlyRepo = buidDescr

    stage("Build packages") {    	
    	sh("rm -rf build-area || true")
        for (source in SOURCES.tokenize('\n')) {
        	sourceArr=source.tokenize(' ')
            deployBuild = build(job: "oscore-ci-build-formula-change", propagate: false, parameters: [                
                [$class: 'StringParameterValue', name: 'SOURCE_URL', value: "${sourceArr[0]}"],
                [$class: 'StringParameterValue', name: 'SOURCE_REFSPEC', value: "${sourceArr[1]}"],
            ])
            if (deployBuild.result == 'SUCCESS'){
                common.infoMsg("${source} has been build successfully ${deployBuild}")
            } else {
                error("Cannot build ${source}, please check ${deployBuild.absoluteUrl}")
            }

            step ([$class: 'CopyArtifact',
          		projectName: "${deployBuild.getProjectName()}",
          		filter: 'build-area/*.deb',
          		selector: [$class: 'SpecificBuildSelector', buildNumber: "${deployBuild.getId()}"],          		
          		]);
            archiveArtifacts artifacts: "build-area/*.deb"
        }
    }

    if (uploadAptly && buildPackage) {
        stage("upload to Aptly") {
          buildSteps = [:]
          sh("curl -X POST -H 'Content-Type: application/json' --data '{\"Name\": \"${aptlyRepo}\"}' ${APTLY_URL}/api/repos")
          debFiles = sh script: "ls build-area/*.deb", returnStdout: true          
          for (file in debFiles.tokenize()) {
            workspace = common.getWorkspace()
            def fh = new File((workspace+"/"+file).trim())
            buildSteps[fh.name.split('_')[0]] = aptly.uploadPackageStep(
                  "build-area/"+fh.name,
                  APTLY_URL,
                  aptlyRepo,
                  true
              )
          }
          parallel buildSteps
        }

        stage("publish to Aptly") {
          sh("curl -X POST -H 'Content-Type: application/json' --data '{\"SourceKind\": \"local\", \"Sources\": [{\"Name\": \"${aptlyRepo}\"}], \"Architectures\": [\"amd64\"], \"Distribution\": \"${aptlyRepo}\"}' ${APTLY_URL}/api/publish/:.")
        }
    }


}

