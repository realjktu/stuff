http = new com.mirantis.mk.Http()

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


node {

def server = 'http://172.16.48.254:8084'
def components = 'salt'
def OPENSTACK_COMPONENTS_LIST = 'salt-formula-nova,salt-formula-cinder,salt-formula-glance,salt-formula-keystone,salt-formula-horizon,salt-formula-neutron,salt-formula-designate,salt-formula-heat,salt-formula-ironic,salt-formula-barbican'
def nightlySnapshot = getSnapshot(server, 'nightly', 'xenial', components)
print(nightlySnapshot)
def snapshotpkglist = snapshotPackages(server, nightlySnapshot, OPENSTACK_COMPONENTS_LIST)
print(snapshotpkglist)
//snapshot = snapshotCreate(server, repo, snapshotpkglist)


}
