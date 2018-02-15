http = new com.mirantis.mk.Http()
common = new com.mirantis.mk.Common()

def getSnapshot(server, distribution, prefix, component) {
    def list_published = http.sendHttpGetRequest(server + '/api/publish')
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
                        println ('Snapshot belongs to ' + distribution + '/' + prefix + ': ' + source['Name'])
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

def snapshotPackages(server, snapshot, packagesList) {
    def pkgs = http.sendHttpGetRequest(server + "/api/snapshots/${snapshot}/packages")
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
def snapshotCreate(server, repo, packageRefs = null) {
    def now = new Date()
    def ts = now.format('yyyyMMddHHmmss', TimeZone.getTimeZone('UTC'))
    def snapshot = "os-salt-formulas-${ts}-oscc-dev"

    if (packageRefs) {
        String listString = packageRefs.join('\",\"')
//        println ("LISTSTRING: ${listString}")
//        String data = "{\"Name\":\"${snapshot}\", \"Description\": \"OpenStack Core Components salt formulas CI\", \"PackageRefs\": [\"${listString}\"]}"
        data  = [:]
        data['Name'] = snapshot
        data['Description'] = 'OpenStack Core Components salt formulas CI'
        data['PackageRefs'] = listString
        echo "HTTP body is going to be sent: ${data}"
        def resp = http.sendHttpPostRequest(server + '/api/snapshots', data)
        echo "Response: ${resp}"
    } else {
        String data = "{\"Name\": \"${snapshot}\", \"Description\": \"OpenStack Core Components salt formulas CI\"}"
        echo "HTTP body is going to be sent: ${data}"
//        def resp = http.sendHttpPostRequest(server + "/api/repos/${repo}/snapshots", data)
//        echo "Response: ${resp}"
    }

    return snapshot
}



node {

def server = 'http://172.16.48.254:8084'
def components = 'salt'
def OPENSTACK_COMPONENTS_LIST = 'salt-formula-nova,salt-formula-cinder,salt-formula-glance,salt-formula-keystone,salt-formula-horizon,salt-formula-neutron,salt-formula-designate,salt-formula-heat,salt-formula-ironic,salt-formula-barbican'
def nightlySnapshot = getSnapshot(server, 'nightly', 'xenial', components)
def repo = 'ubuntu-xenial-salt'

print(nightlySnapshot)
def snapshotpkglist = snapshotPackages(server, nightlySnapshot, OPENSTACK_COMPONENTS_LIST)
print(snapshotpkglist)
snapshot = snapshotCreate(server, repo, snapshotpkglist)
common.successMsg("Snapshot ${snapshot} has been created for packages: ${snapshotpkglist}")

}
