common = new com.mirantis.mk.Common()

def getSnapshotByAPI(server, distribution, prefix, component) {
    http = new com.mirantis.mk.Http()
    def list_published = http.restGet(server, '/api/publish')
    def storage
    for (items in list_published) {
        for (row in items) {
            if (prefix.tokenize(':')[1]) {
                storage = prefix.tokenize(':')[0] + ':' + prefix.tokenize(':')[1]
            } else {
                storage = ''
            }

            if (row.key == 'Distribution' && row.value == distribution && items['Prefix'] == prefix.tokenize(':').last() && items['Storage'] == storage) {
                for (source in items['Sources']){
                    if (source['Component'] == component) {
                        if(env.getEnvironment().containsKey('DEBUG') && env['DEBUG'] == "true"){
                            println ('Snapshot belongs to ' + distribution + '/' + prefix + ': ' + source['Name'])
                        }
                        return source['Name']
                    }
                }
            }
        }
    }
    return false
}

/**
 * Returns list of the packages matched to pattern and
 * belonged to particular snapshot
 *
 * @param server        URI of the server insluding port and protocol
 * @param snapshot      Snapshot to check
 * @param packagesList  Pattern of the components to be compared
 **/
def snapshotPackagesByAPI(server, snapshot, packagesList) {
    http = new com.mirantis.mk.Http()
    def pkgs = http.restGet(server, "/api/snapshots/${snapshot}/packages")
    def openstack_packages = []

    for (package_pattern in packagesList.tokenize(',')) {
        def pkg = pkgs.find { item -> item.contains(package_pattern) }
        openstack_packages.add(pkg)
    }

    return openstack_packages
}


/**
 * Creates snapshot of the repo or package refs
 * @param server        URI of the server insluding port and protocol
 * @param repo          Local repo name
 * @param packageRefs   List of the packages are going to be included into the snapshot
 **/
def snapshotCreateByAPI(server, repo, snapshotName, snapshotDescription = null, packageRefs = null) {
    http = new com.mirantis.mk.Http()
    def data  = [:]
    data['Name'] = snapshotName
    if (snapshotDescription) {
        data['Description'] = snapshotDescription
    } else {
        data['Description'] = "Snapshot of ${repo} repo"
    }
    if (packageRefs) {
        String listString = packageRefs.join('\",\"')
        data['PackageRefs'] = packageRefs
        http.restPost(server, '/api/snapshots', data)
    } else {
        http.restPost(server + "/api/repos/${repo}/snapshots", data)
    }
    return snapshot
}

/**
 * Publishes the snapshot accodgin to distribution, components and prefix
 * @param server        URI of the server insluding port and protocol
 * @param snapshot      Snapshot is going to be published
 * @param distribution  Distribution for the published repo
 * @param components    Component for the published repo
 * @param prefix        Prefix for thepubslidhed repo including storage
 **/
def snapshotPublishByAPI(server, snapshot, distribution, components, prefix) {
    http = new com.mirantis.mk.Http()
    def data = [:]
    data['SourceKind'] = 'snapshot'
    def source = [:]
    source['Name'] = snapshot
    source['Component'] = components
    data['Sources'] = [source]
    data['Architectures'] = ['amd64']
    data['Distribution'] = distribution  
    return http.restPost(server, "/api/publish/${prefix}", data)
}

/**
 * Unpublish Aptly repo by REST API
 *
 * @param aptlyServer Aptly connection object
 * @param aptlyPrefix Aptly prefix where need to delete a repo
 * @param aptlyRepo  Aptly repo name
 */
def aptlyUnpublishByAPI(aptlyServer, aptlyPrefix, aptlyRepo){
    http = new com.mirantis.mk.Http()
    http.restDel(aptlyServer, "/api/publish/${aptlyPrefix}/${aptlyRepo}")
}

/**
 * Delete Aptly repo by REST API
 *
 * @param aptlyServer Aptly connection object
 * @param aptlyRepo  Aptly repo name
 */
def deleteRepoByAPI(aptlyServer, aptlyRepo){
    http = new com.mirantis.mk.Http()
    http.restDel(aptlyServer, "/api/repos/${aptlyRepo}")
}


node {

def server = [
        'url': 'http://172.16.48.254:8084',
]
def components = 'salt'
def OPENSTACK_COMPONENTS_LIST = 'salt-formula-nova,salt-formula-cinder,salt-formula-glance,salt-formula-keystone,salt-formula-horizon,salt-formula-neutron,salt-formula-designate,salt-formula-heat,salt-formula-ironic,salt-formula-barbican'
def nightlySnapshot = getSnapshotByAPI(server, 'nightly', 'xenial', components)
def repo = 'ubuntu-xenial-salt'
def DISTRIBUTION = 'dev-os-salt-formulas'

print(nightlySnapshot)
def snapshotpkglist = snapshotPackagesByAPI(server, nightlySnapshot, OPENSTACK_COMPONENTS_LIST)
print(snapshotpkglist)

def now = new Date()
def ts = now.format('yyyyMMddHHmmss', TimeZone.getTimeZone('UTC'))
def snapshot = "os-salt-formulas-${ts}-oscc-dev"
def snapshotDescription = 'OpenStack Core Components salt formulas CI'
snapshot = snapshotCreateByAPI(server, repo, snapshot, snapshotDescription, snapshotpkglist)


common.successMsg("Snapshot ${snapshot} has been created for packages: ${snapshotpkglist}")
def now = new Date()
def ts = now.format('yyyyMMddHHmmss', TimeZone.getTimeZone('UTC'))
def distribution = "${DISTRIBUTION}-${ts}"
def prefix = 'oscc-dev'
//def prefix = 's3:aptcdn:oscc-dev'
snapshotPublishByAPI(server, snapshot, distribution, components, prefix)
common.successMsg("Snapshot ${snapshot} has been published for prefix ${prefix}")

}
