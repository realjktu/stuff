/*

This is a copy of build-debian-pipeline.groovy with the folloiwng changes:
1. New parameter SOURCE_REFSPEC is added.
2. New checkout command is defined in case of SOURCE_REFSPEC exists. Lines 71-84
3. buildSourceGbp procedure is redefined in order to be able specify origin for branches.

This pipeline have to be deleted when related patches will be merged to origin build-debian-pipeline.groovy
and Debian.groovy lib.

*/
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

def refspec
try {
  refspec = SOURCE_REFSPEC
} catch (MissingPropertyException e) {
  refspec = null
}


def timestamp = common.getDatetime()
node('docker') {
  try{
    stage('checkout') {
      sh('rm -rf src || true')
      dir('src') {
        def pollBranches = [[name: SOURCE_BRANCH]]
        if (debian_branch) {
          pollBranches.add([name: DEBIAN_BRANCH])
        }
        def extensions = [[$class: 'CleanCheckout']]
        def userRemoteConfigs = [[credentialsId: SOURCE_CREDENTIALS, url: SOURCE_URL]]
        if (refspec) {
          println('Add refspec')
          extensions.add([$class: 'BuildChooserSetting', buildChooser: [$class: 'GerritTriggerBuildChooser']])
          extensions.add([$class: 'LocalBranch', localBranch: SOURCE_BRANCH])
          userRemoteConfigs.add(refspec: refspec)
        }
        checkout changelog: true, poll: false,
          scm: [$class: 'GitSCM', branches: pollBranches, doGenerateSubmoduleConfigurations: false,
          extensions: extensions,  submoduleCfg: [], userRemoteConfigs: userRemoteConfigs]
        if (debian_branch){
          sh('git checkout ' + DEBIAN_BRANCH)
        }
      }      
      debian.cleanup(OS + ':' + DIST)
    }    
    stage('build-source') {
      //debian.buildSource("src", OS+":"+DIST, snapshot, 'Jenkins', 'autobuild@mirantis.com', revisionPostfix)
      buildSourceGbp('src', OS + ':' + DIST, snapshot, 'Jenkins', 'autobuild@mirantis.com', revisionPostfix, '')
      archiveArtifacts artifacts: 'build-area/*.dsc'
      archiveArtifacts artifacts: 'build-area/*_source.changes'
      archiveArtifacts artifacts: 'build-area/*.tar.*'
    }
    stage('build-binary') {
      dsc = sh script: 'ls build-area/*.dsc', returnStdout: true
      if(common.validInputParam('PRE_BUILD_SCRIPT')) {
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
      lock("aptly-api") {
        stage("upload") {
          buildSteps = [:]
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
          aptly.snapshotRepo(APTLY_URL, APTLY_REPO, timestamp)
          aptly.publish(APTLY_URL)
        }
      }
    }
    if (uploadPpa) {
      stage("upload launchpad") {
        debian.importGpgKey("launchpad-private")
        debian.uploadPpa(PPA, "build-area", "launchpad-private")
      }
    }
  } catch (Throwable e) {
     // If there was an error or exception thrown, the build failed
     currentBuild.result = "FAILURE"
     currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
     throw e
  } finally {
     common.sendNotification(currentBuild.result,"",["slack"])
  }
}

def buildSourceGbp(dir, image="debian:sid", snapshot=false, gitName='Jenkins', gitEmail='jenkins@dummy.org', revisionPostfix="", origin="origin/") {
    def common = new com.mirantis.mk.Common()
    def jenkinsUID = common.getJenkinsUid()
    def jenkinsGID = common.getJenkinsGid()

    if (! revisionPostfix) {
        revisionPostfix = ""
    }

    def workspace = common.getWorkspace()
    def dockerLib = new com.mirantis.mk.Docker()
    def imageArray = image.split(":")
    def os = imageArray[0]
    def dist = imageArray[1]
    def img = dockerLib.getImage("tcpcloud/debian-build-${os}-${dist}", image)

    img.inside("-u root:root") {

        withEnv(["DEBIAN_FRONTEND=noninteractive", "DEBFULLNAME='${gitName}'", "DEBEMAIL='${gitEmail}'"]) {
            sh("""bash -c 'cd ${workspace} && (which eatmydata || (apt-get update && apt-get install -y eatmydata)) &&
            export LD_LIBRARY_PATH=\${LD_LIBRARY_PATH:+"\$LD_LIBRARY_PATH:"}/usr/lib/libeatmydata &&
            export LD_PRELOAD=\${LD_PRELOAD:+"\$LD_PRELOAD "}libeatmydata.so &&
            apt-get update && apt-get install -y build-essential git-buildpackage dpkg-dev sudo &&
            groupadd -g ${jenkinsGID} jenkins &&
            useradd -s /bin/bash --uid ${jenkinsUID} --gid ${jenkinsGID} -m jenkins &&
            chown -R ${jenkinsUID}:${jenkinsGID} /home/jenkins &&
            cd ${dir} &&
            sudo -H -E -u jenkins git config --global user.name "${gitName}" &&
            sudo -H -E -u jenkins git config --global user.email "${gitEmail}" &&
            [[ "${snapshot}" == "false" ]] || (
                VERSION=`dpkg-parsechangelog --count 1 --show-field Version` &&
                UPSTREAM_VERSION=`echo \$VERSION | cut -d "-" -f 1` &&
                REVISION=`echo \$VERSION | cut -d "-" -f 2` &&
                TIMESTAMP=`date +%Y%m%d%H%M` &&
                if [[ "`cat debian/source/format`" = *quilt* ]]; then
                    UPSTREAM_BRANCH=`(grep upstream-branch debian/gbp.conf || echo master) | cut -d = -f 2 | tr -d " "` &&                    
                    UPSTREAM_REV=`git rev-parse --short ${origin}\$UPSTREAM_BRANCH` &&
                    NEW_UPSTREAM_VERSION="\$UPSTREAM_VERSION+\$TIMESTAMP.\$UPSTREAM_REV" &&
                    NEW_UPSTREAM_VERSION_TAG=`echo \$NEW_UPSTREAM_VERSION | sed 's/.*://'` &&
                    NEW_VERSION=\$NEW_UPSTREAM_VERSION-\$REVISION$revisionPostfix &&
                    echo "Generating new upstream version \$NEW_UPSTREAM_VERSION_TAG" &&
                    sudo -H -E -u jenkins git tag \$NEW_UPSTREAM_VERSION_TAG ${origin}\$UPSTREAM_BRANCH &&
                    sudo -H -E -u jenkins git merge -X theirs \$NEW_UPSTREAM_VERSION_TAG
                else
                    NEW_VERSION=\$VERSION+\$TIMESTAMP.`git rev-parse --short HEAD`$revisionPostfix
                fi &&
                sudo -H -E -u jenkins gbp dch --auto --multimaint-merge --ignore-branch --new-version=\$NEW_VERSION --distribution `lsb_release -c -s` --force-distribution &&
                sudo -H -E -u jenkins git add -u debian/changelog &&
                sudo -H -E -u jenkins git commit -m "New snapshot version \$NEW_VERSION"
            ) &&
            sudo -H -E -u jenkins gbp buildpackage -nc --git-force-create --git-notify=false --git-ignore-branch --git-ignore-new --git-verbose --git-export-dir=../build-area -sa -S -uc -us '""")
        }
    }
}

