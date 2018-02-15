http = new com.mirantis.mk.Http()

def getnightlySnapshot(server, distribution, prefix, component) {
    def list_published = http.sendHttpGetRequest(server, '/api/publish')
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


node {

def server = 'http://172.16.48.254:8084'
def components = 'salt'
def nightlySnapshot = getnightlySnapshot(server, 'nightly', 'xenial', components)
print(nightlySnapshot)

}
