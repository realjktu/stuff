/**
*
* Build deb package by Gerrit refspec.
*
* Expected parameters:
*  DEBIAN_BRANCH           Debian branch to be merged.
*  LINTIAN_CHECK           This boolean sets whether need to perform Lintian check
*  SOURCE_CREDENTIALS      Credentials to Git access
*  SOURCE_URL              Git repo URL
*  SOURCE_REFSPEC          Git refspec
*  OS                      Operation System name
*  DIST                    Operation System distributive
*  EXTRA_REPO_URL          Extra repo URL to be used during package build
*  EXTRA_REPO_KEY_URL      Extra repo GPG key URL to be used during package build
*
**/

def common = new com.mirantis.mk.Common()
def debian = new com.mirantis.mk.Debian()
def gerrit = new com.mirantis.mk.Gerrit()

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

def arch
try {
  arch = ARCH
} catch (MissingPropertyException e) {
  arch = 'amd64'
}

node('docker') {
    stage('checkout') {
      def componentArr = SOURCE_URL.tokenize('/')
      def refspecArr = SOURCE_REFSPEC.tokenize('/')
      def descrSuffix = componentArr[componentArr.size() - 2] + '/' + componentArr.last() + '/' + refspecArr[refspecArr.size() - 2] + '/' + refspecArr.last() + '-' + BUILD_NUMBER


      wrap([$class: 'BuildUser']) {
        if (env.BUILD_USER_ID) {
          buidDescr = "${env.BUILD_USER_ID}-${descrSuffix}"
        } else {
          buidDescr = "jenkins-${JOB_NAME}-${descrSuffix}"
        }
      }
      currentBuild.description = buidDescr
      def srcDir = new File("${env.WORKSPACE}/src")
      if (srcDir.exists()){
        srcDir.deleteDir()
      }
      dir('src') {
        /*
        def pollBranches = [[name: 'FETCH_HEAD']]
        checkout changelog: true, poll: false,
          scm: [$class: 'GitSCM', branches: pollBranches, doGenerateSubmoduleConfigurations: false,
                extensions: [[$class: 'CleanCheckout']],  submoduleCfg: [],
                userRemoteConfigs: [[credentialsId: SOURCE_CREDENTIALS, url: SOURCE_URL, refspec: SOURCE_REFSPEC]]]
                */
        //sh("git merge origin/${DEBIAN_BRANCH} -m 'Merge with ${DEBIAN_BRANCH}' || exit 0")
        //gerrit.gerritPatchsetCheckout("https://oiurchenko@gerrit.mcp.mirantis.net:443/a/salt-formulas/keystone", "refs/changes/90/11490/13", 'master', 'test')
        def pollBranches = [[name: 'master']]
        //def pollBranches = [[name: DEBIAN_BRANCH]]
        //pollBranches.add([name:DEBIAN_BRANCH])
        def scmExtensions = [
            [$class: 'CleanCheckout'],
            //[$class: 'BuildChooserSetting', buildChooser: [$class: 'GerritTriggerBuildChooser']],
            //[$class: 'CheckoutOption', timeout: 20],
            //[$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: 0 > 0, timeout: 20]
        ]        
        //checkout (
        //  scm: [$class: 'GitSCM', 
        //        branches: pollBranches, 
        //        extensions: scmExtensions,  
                //userRemoteConfigs: [[credentialsId: SOURCE_CREDENTIALS, url: SOURCE_URL, refspec: SOURCE_REFSPEC]]
         //       userRemoteConfigs: [[credentialsId: SOURCE_CREDENTIALS, url: SOURCE_URL]]
        //        ]
        //)
        checkout changelog: true, poll: false,
          scm: [$class: 'GitSCM', branches: pollBranches, doGenerateSubmoduleConfigurations: false,
          extensions: [[$class: 'CleanCheckout']],  submoduleCfg: [], userRemoteConfigs: [[credentialsId: SOURCE_CREDENTIALS, url: SOURCE_URL, refspec: SOURCE_REFSPEC]]]
        //sh("git merge e3619c9 -m 'Merge with saaa' || exit 0")
        //sh("git checkout "+DEBIAN_BRANCH)
      }
      debian.cleanup(OS + ':' + DIST)
    }


    stage('build-source') {
      //buildSourceGbp('src', OS + ':' + DIST, snapshot, 'Jenkins', 'autobuild@mirantis.com', revisionPostfix)
      debian.buildSourceGbp('src', OS + ':' + DIST, true, 'Jenkins', 'autobuild@mirantis.com', revisionPostfix)
      archiveArtifacts artifacts: 'build-area/*.dsc'
      archiveArtifacts artifacts: 'build-area/*_source.changes'
      archiveArtifacts artifacts: 'build-area/*.tar.*'
    }

    stage('build-binary') {
      def dsc = sh script: 'ls build-area/*.dsc', returnStdout: true
      if (common.validInputParam('PRE_BUILD_SCRIPT')) {
        writeFile([file: 'pre_build_script.sh', text: env['PRE_BUILD_SCRIPT']])
      }
      debian.buildBinary(
        dsc.trim(),
        OS + ':' + DIST,
        EXTRA_REPO_URL,
        EXTRA_REPO_KEY_URL
      )
      archiveArtifacts artifacts: 'build-area/*.deb'
    }

  if (lintianCheck) {
    stage('lintian') {
      def changes = sh script: 'ls build-area/*_' + arch + '.changes', returnStdout: true
      try {
        debian.runLintian(changes.trim(), OS, OS + ':' + DIST)
      } catch (Exception e) {
        println '[WARN] Lintian returned non-zero exit status'
        currentBuild.result = 'UNSTABLE'
      }
    }
  }
}

/*
 * Build source package using git-buildpackage
 *
 * @param dir   Tree to build
 * @param image Image name to use for build (default debian:sid)
 * @param snapshot Generate snapshot version (default false)
 */
def buildSourceGbp(dir, image='debian:sid', snapshot=false, gitName='Jenkins', gitEmail='jenkins@dummy.org', revisionPostfix='') {
    def common = new com.mirantis.mk.Common()
    def jenkinsUID = common.getJenkinsUid()
    def jenkinsGID = common.getJenkinsGid()
    def workspace = common.getWorkspace()
    def dockerLib = new com.mirantis.mk.Docker()
    def imageArray = image.split(':')
    def os = imageArray[0]
    def dist = imageArray[1]
    def img = dockerLib.getImage("tcpcloud/debian-build-${os}-${dist}", image)

    img.inside('-u root:root') {

        withEnv(['DEBIAN_FRONTEND=noninteractive', "DEBFULLNAME='${gitName}'", "DEBEMAIL='${gitEmail}'"]) {
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
                    UPSTREAM_REV=`git rev-parse --short HEAD` &&
                    NEW_UPSTREAM_VERSION="\$UPSTREAM_VERSION+\$TIMESTAMP.\$UPSTREAM_REV" &&
                    NEW_UPSTREAM_VERSION_TAG=`echo \$NEW_UPSTREAM_VERSION | sed 's/.*://'` &&
                    NEW_VERSION=\$NEW_UPSTREAM_VERSION-\$REVISION$revisionPostfix &&
                    echo "Generating new upstream version \$NEW_UPSTREAM_VERSION_TAG" &&
                    sudo -H -E -u jenkins git tag \$NEW_UPSTREAM_VERSION_TAG HEAD
                else
                    NEW_VERSION=\$VERSION+\$TIMESTAMP.`git rev-parse --short HEAD`$revisionPostfix
                fi &&
                echo "NEW_VERSION: \$NEW_VERSION"&&
                sudo -H -E -u jenkins gbp dch --auto --multimaint-merge --ignore-branch --new-version=\$NEW_VERSION --distribution `lsb_release -c -s` --force-distribution &&
                sudo -H -E -u jenkins git add -u debian/changelog &&
                sudo -H -E -u jenkins git commit -m "New snapshot version \$NEW_VERSION"
            ) &&
            sudo -H -E -u jenkins gbp buildpackage -nc --git-force-create --git-notify=false --git-ignore-branch --git-ignore-new --git-verbose --git-export-dir=../build-area -sa -S -uc -us '""")
        }
    }
}
