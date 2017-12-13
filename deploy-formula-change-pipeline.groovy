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


def timestamp = common.getDatetime()


node('python') {

    stage("Build packages") {
        for (source in SOURCES.tokenize('\n')) {
        	sourceArr=source.tokenize(' ')
        	/*
            deployBuild = build(job: "oscore-ci-build-formula-change", propagate: false, parameters: [                
                [$class: 'StringParameterValue', name: 'SOURCE_URL', value: "${sourceArr[0]}"],
                [$class: 'StringParameterValue', name: 'SOURCE_REFSPEC', value: "${sourceArr[1]}"],
            ])
            if (deployBuild.result == 'SUCCESS'){
                common.infoMsg("${source} has been build successfully ${deployBuild}")
            } else {
                error("Cannot build ${source}, please check ${deployBuild.absoluteUrl}")
            }
            ${deployBuild.buildNumber}
*/
            step ([$class: 'CopyArtifact',
          		projectName: 'oscore-ci-build-formula-change',
          		filter: 'build-area/*.deb',
          		selector: [$class: 'SpecificBuildSelector', buildNumber: '20'],
          		]);
        }
    }

}

