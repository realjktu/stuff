ldap_host = '192.168.153.203'
domain = 'domain4.local'
user = 'cyberx3'
password = 'cruvuttj@4338'
search_base = 'cn=Users,dc=domain4,dc=local'

from ldap3 import Server, Connection, ALL
s = Server(ldap_host, get_info=ALL)  # define an unsecure LDAP server, requesting info on DSE and schema
c = Connection(s)
c.open()  # establish connection without performing any bind (equivalent to ANONYMOUS bind)
print(s.info.supported_sasl_mechanisms)

	
from ldap3 import Server, Connection, Tls, SASL, KERBEROS
import ssl
tls = Tls(validate=ssl.CERT_NONE, version=ssl.PROTOCOL_TLSv1_2)
server = Server(ldap_host, use_ssl=False, tls=tls)
c = Connection(
    server, authentication=SASL, sasl_mechanism=KERBEROS)
c.bind()
print(c.extend.standard.who_am_i())
