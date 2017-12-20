def common = new com.mirantis.mk.Common()
def aptly = new com.mirantis.mk.Aptly()
def debian = new com.mirantis.mk.Debian()

/**
* Expected parameters:
* 
*/

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
    def aptlyServer = [
        'url': APTLY_URL
    ]
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
    	try {
	        stage("upload to Aptly") {
	          buildSteps = [:]
	          restPost(aptlyServer, '/api/repos', "{\"Name\": \"${aptlyRepo}\"}")
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
	        	restPost(aptlyServer, '/api/publish/:.', "{\"SourceKind\": \"local\", \"Sources\": [{\"Name\": \"${aptlyRepo}\"}], \"Architectures\": [\"amd64\"], \"Distribution\": \"${aptlyRepo}\"}")
	        }
		} catch (Throwable e) {
        	currentBuild.result = 'FAILURE'
        	throw e
    	} 
    }

    if (OPENSTACK_RELEASES) {
        saltOverrides="linux_system_repo: deb [ arch=amd64 trusted=yes ] ${APTLY_REPO_URL} ${aptlyRepo} main\nlinux_system_repo_priority: 1200\nlinux_system_repo_pin: origin 172.17.49.50"
        for (OPENSTACK_RELEASE in OPENSTACK_RELEASES.tokenize(',')) {
            stage("Deploy OpenStack ${OPENSTACK_RELEASE} release with changed formula") {
                deployBuild = build(job: "oscore-ci-deploy-virtual-aio-${OPENSTACK_RELEASE}", propagate: false, parameters: [
                //deployBuild = build(job: "oiurchenko_aio_test", propagate: false, parameters: [                
                    [$class: 'StringParameterValue', name: 'STACK_RECLASS_ADDRESS', value: "${STACK_RECLASS_ADDRESS}"],
                    [$class: 'StringParameterValue', name: 'STACK_RECLASS_BRANCH', value: "stable/${OPENSTACK_RELEASE}"],
                    [$class: 'StringParameterValue', name: 'TEST_TEMPEST_PATTERN', value: "set=smoke"],
                    [$class: 'StringParameterValue', name: 'TEST_TEMPEST_TARGET', value: "cfg01*"],
                    [$class: 'StringParameterValue', name: 'TEST_TEMPEST_IMAGE', value: "docker-prod-local.artifactory.mirantis.com/mirantis/oscore/rally-tempest"],
                    [$class: 'TextParameterValue', name: 'SALT_OVERRIDES', value: saltOverrides],
                    [$class: 'StringParameterValue', name: 'FORMULA_PKG_REVISION', value: 'stable'],
                ])
                if (deployBuild.result == 'SUCCESS'){
                    common.infoMsg("${OPENSTACK_RELEASE} has been deployed successfully")
                } else {
                    error("Deployment of ${OPENSTACK_RELEASE}, please check ${deployBuild.absoluteUrl}")            
                }
            }
        }
    }



}


def restCall(master, uri, method = 'GET', data = null, headers = [:]) {
    def connection = new URL("${master.url}${uri}").openConnection()
    if (method != 'GET') {
        connection.setRequestMethod(method)
    }

    connection.setRequestProperty('User-Agent', 'jenkins-groovy')
    connection.setRequestProperty('Accept', 'application/json')
    if (master.authToken) {
        // XXX: removeme
        connection.setRequestProperty('X-Auth-Token', master.authToken)
    }

    for (header in headers) {
        connection.setRequestProperty(header.key, header.value)
    }

    if (data) {
        connection.setDoOutput(true)
        connection.setRequestProperty('Content-Type', 'application/json')                    
        
        def out = new OutputStreamWriter(connection.outputStream)
        out.write(data)
        out.close()
    }

    if ( connection.responseCode >= 200 && connection.responseCode < 300 ) {
        res = connection.inputStream.text
        try {
            return new groovy.json.JsonSlurperClassic().parseText(res)
        } catch (Exception e) {
            return res
        }
    } else {
        throw new Exception(connection.responseCode + ": " + connection.inputStream.text)
    }
}

def restPost(master, uri, data = null) {
    return restCall(master, uri, 'POST', data, ['Accept': '*/*'])
}

