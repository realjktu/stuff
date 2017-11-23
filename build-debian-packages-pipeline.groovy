def common = new com.mirantis.mk.Common()
def aptly = new com.mirantis.mk.Aptly()
def debian = new com.mirantis.mk.Debian()

def snapshot
try {
  snapshot = DEBIAN_SNAPSHOT
} catch (MissingPropertyException e) {
  snapshot = false
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
  revisionPostfix = null
}

def uploadPpa
try {
  uploadPpa = UPLOAD_PPA.toBoolean()
} catch (MissingPropertyException e) {
  uploadPpa = null
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

def timestamp = common.getDatetime()
node("docker") {
//  try{
    stage("checkout") {
      sh("rm -rf src || true")
      dir("src") {
        def pollBranches = [[name:SOURCE_BRANCH]]
        if (debian_branch) {
          pollBranches.add([name:DEBIAN_BRANCH])
        }
/*        
        checkout changelog: true, poll: false,
          scm: [$class: 'GitSCM', branches: pollBranches, doGenerateSubmoduleConfigurations: false,
          extensions: [[$class: 'CleanCheckout']],  submoduleCfg: [], userRemoteConfigs: [[credentialsId: SOURCE_CREDENTIALS, url: SOURCE_URL]],
          refspec: '+refs/changes/90/11490/9']
*/          
        checkout changelog: true, poll: false,
          scm: [$class: 'GitSCM', branches: pollBranches, doGenerateSubmoduleConfigurations: false,
          extensions: [[$class: 'CleanCheckout']],  submoduleCfg: [], 
          userRemoteConfigs: [[credentialsId: SOURCE_CREDENTIALS, url: SOURCE_URL, refspec: 'refs/changes/90/11490/9']]]
        //checkout([$class: 'GitSCM', branches: [[name: "$GERRIT_PATCHSET_REVISION"]], 
        //  doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], 
        // userRemoteConfigs: [[credentialsId: 'planets_gerrit_id_rsa', name: "", refspec: "$GERRIT_REFSPEC", url: 'ssh://gerrit@gerrit.datenkollektiv.de:12345/planets-simulation']]])

        if (debian_branch){
          //sh("git checkout "+DEBIAN_BRANCH)
          sh("git merge remotes/origin/debian/xenial -m 'Merge with debian/xenial'")
        }
      }
      debian.cleanup(OS+":"+DIST)
    }
    stage("build-source") {
      debian.buildSource("src", OS+":"+DIST, snapshot, 'Jenkins', 'autobuild@mirantis.com', revisionPostfix)
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

    if (lintianCheck) {
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

    if (uploadAptly) {
//      lock("aptly-api") {
        /*
        Create repo:
        curl -X POST -H 'Content-Type: application/json' --data '{"Name": "test-api-01"}' http://localhost:8084/api/repos
        Upload package:
        curl -X POST -F file=@/root/salt-formula-dogtag_0.1+201711150842.167219c~xenial1_all.deb http://localhost:8084/api/files/salt-formula-dogtag
        Add to repo:
        curl -X POST http://localhost:8084/api/repos/test-api-01/file/salt-formula-dogtag
        Publish:
        curl -X POST -H 'Content-Type: application/json' --data '{"SourceKind": "local", "Sources": [{"Name": "test-api-01"}], "Architectures": ["amd64"], "Distribution": "test-api-01"}' http://localhost:8084/api/publish/:.

        */
        stage("upload") {
          buildSteps = [:]
          sh("curl -X POST -H 'Content-Type: application/json' --data '{\"Name\": \"${APTLY_REPO}\"}' ${APTLY_URL}/api/repos")
          debFiles = sh script: "ls build-area/*.deb", returnStdout: true          
          for (file in debFiles.tokenize()) {
            workspace = common.getWorkspace()
            def fh = new File((workspace+"/"+file).trim())
            buildSteps[fh.name.split('_')[0]] = aptly.uploadPackageStep(
                  "build-area/"+fh.name,
                  APTLY_URL,
                  APTLY_REPO,
                  true
              )
          }
          parallel buildSteps
        }

        stage("publish") {
          sh("curl -X POST -H 'Content-Type: application/json' --data '{\"SourceKind\": \"local\", \"Sources\": [{\"Name\": \"${APTLY_REPO}\"}], \"Architectures\": [\"amd64\"], \"Distribution\": \"${APTLY_REPO}\"}' ${APTLY_URL}/api/publish/:.")
          //aptly.snapshotRepo(APTLY_URL, APTLY_REPO, timestamp)
          //aptly.publish(APTLY_URL)
        }
//      }
    }
    if (uploadPpa) {
      stage("upload launchpad") {
        debian.importGpgKey("launchpad-private")
        debian.uploadPpa(PPA, "build-area", "launchpad-private")
      }
    }
//  } catch (Throwable e) {
     // If there was an error or exception thrown, the build failed
//     currentBuild.result = "FAILURE"
//     currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
//     throw e
//  } /* finally {
//     common.sendNotification(currentBuild.result,"",["slack"])
//  } */
}
