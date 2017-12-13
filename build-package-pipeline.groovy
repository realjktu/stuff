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

def uploadAptly
try {
  uploadAptly = UPLOAD_APTLY.toBoolean()
} catch (MissingPropertyException e) {
  uploadAptly = true
}

def buildPackage
try {
  buildPackage = BUILD_PACKAGE.toBoolean()
} catch (MissingPropertyException e) {
  buildPackage = true
}


def timestamp = common.getDatetime()

node("docker") {
  if (buildPackage) {
    stage("checkout") {
      wrap([$class: 'BuildUser']) {
        if (env.BUILD_USER_ID) {
          buidDescr = "${env.BUILD_USER_ID}-${JOB_NAME}-${BUILD_NUMBER}"
        } else {
          buidDescr = "jenkins-${JOB_NAME}-${BUILD_NUMBER}"
        }
      }
      currentBuild.description = buidDescr
      sh("rm -rf src || true")
      sh('git init src')
      dir("src") {
          sh("git fetch --tags --progress ${SOURCE_URL} +refs/heads/*:refs/remotes/origin/*")
          sh("git config remote.origin.url ${SOURCE_URL}")
          sh("git fetch --tags --progress ${SOURCE_URL} ${SOURCE_REFSPEC}")
          sh("git checkout FETCH_HEAD")
          sh("git merge origin/${DEBIAN_BRANCH} -m 'Merge with ${DEBIAN_BRANCH}'")
      }
/*
      dir("src") {
        def pollBranches = [[name:'FETCH_HEAD']]
        if (debian_branch) {
          pollBranches.add([name:DEBIAN_BRANCH])
      }
        checkout changelog: true, poll: false,
          scm: [$class: 'GitSCM', branches: pollBranches, doGenerateSubmoduleConfigurations: false,
          extensions: [[$class: 'CleanCheckout']],  submoduleCfg: [], 
          userRemoteConfigs: [[credentialsId: SOURCE_CREDENTIALS, url: SOURCE_URL, refspec: SOURCE_REFSPEC]]]

        if (debian_branch){
          sh("git merge remotes/origin/${DEBIAN_BRANCH} -m 'Merge with ${DEBIAN_BRANCH}'")
        }
      }
*/
      debian.cleanup(OS+":"+DIST)
    }
    stage("build-source") {
      //debian.buildSource("src", OS+":"+DIST, snapshot, 'Jenkins', 'autobuild@mirantis.com', revisionPostfix)
      buildSourceGbp("src", OS+":"+DIST, snapshot, 'Jenkins', 'autobuild@mirantis.com', revisionPostfix)
      archiveArtifacts artifacts: "build-area/*.dsc"
      archiveArtifacts artifacts: "build-area/*_source.changes"
      archiveArtifacts artifacts: "build-area/*.tar.*"
    }
    stage("build-binary") {
      dsc = sh script: "ls build-area/*.dsc", returnStdout: true
      if(common.validInputParam("PRE_BUILD_SCRIPT")) {
        writeFile([file:"pre_build_script.sh", text: env['PRE_BUILD_SCRIPT']])
      }
      debian.buildBinary(
        dsc.trim(),
        OS+":"+DIST,
        EXTRA_REPO_URL,
        EXTRA_REPO_KEY_URL
      )
      archiveArtifacts artifacts: "build-area/*.deb"
    }
  }
    if (lintianCheck && buildPackage) {
      stage("lintian") {
        changes = sh script: "ls build-area/*_"+ARCH+".changes", returnStdout: true
        try {
          debian.runLintian(changes.trim(), OS, OS+":"+DIST)
        } catch (Exception e) {
          println "[WARN] Lintian returned non-zero exit status"
          currentBuild.result = 'UNSTABLE'
        }
      }
    }
}