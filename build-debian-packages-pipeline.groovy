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

def aptlyRepo
try {
  aptlyRepo = APTLY_REPO
} catch (MissingPropertyException e) {
  aptlyRepo = null
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

def deployOS
try {
  deployOS = DEPLOY_OS.toBoolean()
} catch (MissingPropertyException e) {
  deployOS = false
}

def buildPackage
try {
  buildPackage = BUILD_PACKAGE.toBoolean()
} catch (MissingPropertyException e) {
  buildPackage = true
}


def timestamp = common.getDatetime()

node("docker") {
//  try{
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
      if (aptlyRepo == '')
        aptlyRepo = buidDescr
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

    if (uploadAptly && buildPackage) {
//      lock("aptly-api") {
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
//      }
    }
    if (uploadPpa && buildPackage) {
      stage("upload launchpad") {
        debian.importGpgKey("launchpad-private")
        debian.uploadPpa(PPA, "build-area", "launchpad-private")
      }
    }
    if (deployOS) {
      stage("Deploy OpenStack with changed package") {
        for (TARGET_OS_RELEASE in params.TARGET_OS_RELEASES.split(',')) {
          deployBuild = build (job: "oscore-ci-deploy-virtual-aio-${TARGET_OS_RELEASE}", propagate: true,
            parameters: [
              [$class: 'StringParameterValue', name: 'FORMULA_PKG_REVISION', value: 'stable'],
              [$class: 'TextParameterValue', name: 'SALT_OVERRIDES', value: "linux_system_repo: deb [ arch=amd64 trusted=yes ] ${APTLY_REPO_URL} ${aptlyRepo} main\nlinux_system_repo_priority: 1200\nlinux_system_repo_pin: origin 172.17.49.50"]
            ])
        }
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


/*
 * Build source package using git-buildpackage
 *
 * @param dir   Tree to build
 * @param image Image name to use for build (default debian:sid)
 * @param snapshot Generate snapshot version (default false)
 */
def buildSourceGbp(dir, image="debian:sid", snapshot=false, gitName='Jenkins', gitEmail='jenkins@dummy.org', revisionPostfix="") {
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



/*
            parameters: [
            [$class: 'StringParameterValue', name: 'FORMULA_PKG_REVISION', value: 'stable'],
            [$class: 'StringParameterValue', name: 'HEAT_STACK_ENVIRONMENT', value: 'devcloud'],
            [$class: 'StringParameterValue', name: 'HEAT_STACK_PUBLIC_NET', value: 'public'],
            [$class: 'StringParameterValue', name: 'HEAT_STACK_ZONE', value: 'mcp-oscore'],
            [$class: 'StringParameterValue', name: 'OPENSTACK_API_CLIENT', value: ''],
            [$class: 'StringParameterValue', name: 'OPENSTACK_API_CREDENTIALS', value: 'openstack-devcloud-credentials'],
            [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT', value: 'mcp-oscore'],
            [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT_DOMAIN', value: 'default'],
            [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT_ID', value: ''],
            [$class: 'StringParameterValue', name: 'OPENSTACK_API_URL', value: 'https://cloud-cz.bud.mirantis.net:5000'],
            [$class: 'StringParameterValue', name: 'OPENSTACK_API_USER_DOMAIN', value: 'default'],
            [$class: 'StringParameterValue', name: 'OPENSTACK_API_VERSION', value: '3'],
            [$class: 'StringParameterValue', name: 'OPENSTACK_USER_DOMAIN', value: 'default'],
            [$class: 'StringParameterValue', name: 'SALT_MASTER_CREDENTIALS', value: 'salt-qa-credentials'],
            [$class: 'StringParameterValue', name: 'SALT_MASTER_URL', value: ''],
            [$class: 'StringParameterValue', name: 'STACK_CLEANUP_JOB', value: 'deploy-stack-cleanup'],
            [$class: 'StringParameterValue', name: 'STACK_INSTALL', value: 'core,openstack,ovs'],
            [$class: 'StringParameterValue', name: 'STACK_NAME', value: ''],
            [$class: 'StringParameterValue', name: 'STACK_TEMPLATE', value: 'virtual_mcp11_aio'],
            [$class: 'StringParameterValue', name: 'STACK_TEMPLATE_BRANCH', value: 'master'],
            [$class: 'StringParameterValue', name: 'STACK_TEMPLATE_CREDENTIALS', value: 'gerrit'],
            [$class: 'StringParameterValue', name: 'STACK_TEMPLATE_URL', value: 'ssh://jenkins-mk@gerrit.mcp.mirantis.net:29418/mk/heat-templates'],
            [$class: 'StringParameterValue', name: 'STACK_TEST', value: 'openstack'],
            [$class: 'StringParameterValue', name: 'STACK_TYPE', value: 'heat'],
            [$class: 'StringParameterValue', name: 'TEST_K8S_API_SERVER', value: 'http://127.0.0.1:8080'],
            [$class: 'StringParameterValue', name: 'TEST_K8S_CONFORMANCE_IMAGE', value: 'docker-dev-virtual.docker.mirantis.net/mirantis/kubernetes/k8s-conformance:v1.5.1-3_1482332392819'],
            [$class: 'StringParameterValue', name: 'TEST_TEMPEST_IMAGE', value: 'sandbox-docker-prod-local.docker.mirantis.net/mirantis/rally_tempest:0.1'],
            [$class: 'StringParameterValue', name: 'TEST_TEMPEST_PATTERN', value: ''],
            [$class: 'StringParameterValue', name: 'TEST_TEMPEST_TARGET', value: ''],
            [$class: 'StringParameterValue', name: 'STACK_RECLASS_ADDRESS', value: 'https://gerrit.mcp.mirantis.net/salt-models/mcp-virtual-aio'],
            [$class: 'StringParameterValue', name: 'STACK_RECLASS_BRANCH', value: 'stable/ocata'],
            [$class: 'BooleanParameterValue', name: 'ASK_ON_ERROR', value: false],
            [$class: 'BooleanParameterValue', name: 'STACK_DELETE', value: false],
            [$class: 'BooleanParameterValue', name: 'STACK_REUSE', value: false],
            [$class: 'BooleanParameterValue', name: 'TEST_DOCKER_INSTALL', value: false],
            [$class: 'TextParameterValue', name: 'SALT_OVERRIDES', value: "linux_system_repo: deb [ arch=amd64 trusted=yes ] ${APTLY_REPO_URL} ${aptlyRepo} main\nlinux_system_repo_priority: 1200\nlinux_system_repo_pin: origin 172.17.49.50"]            
          ]
*/          