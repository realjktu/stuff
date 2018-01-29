'''

1. configure Kerberos user config check_ldap_connection_krb5.ini
2. execute: export KRB5_CONFIG=<path to check_ldap_connection_krb5.ini file>
3. Run ./check_ldap_connection_2.py script

'''
import ssl, sys, os
from subprocess import Popen, PIPE
from ldap3 import Server, Connection, Tls, SASL, KERBEROS
from ldif3 import LDIFParser
from pprint import pprint

ldap_server = 'dc4.domain4.local'
userid = 'cyberx3'
realm = 'DOMAIN3.LOCAL'
password = 'cruvuttj@4338'
base_dn = 'cn=Users,dc=domain4,dc=local'
temp_file = '/tmp/check_users_ldap.tmp'


def getFirstOrEmpty(listVar):
	if len(listVar) > 0:
		return listVar[0]
	else:
		return ""

kinit = '/usr/bin/kinit'
kinit_args = [ kinit, '%s@%s' % (userid, realm) ]
kinit = Popen(kinit_args, stdin=PIPE, stdout=PIPE, stderr=PIPE)
kinit.stdin.write('%s\n' % password)
kinit.wait()
kinit_res = kinit.stderr.read()
if kinit.returncode > 0:
	print("Cannot initialize Kerberos. Error is: {}".format(kinit.stderr.read()))
	sys.exit(1)

print("Execute ldapsearch")
#server = Server(ldap_server)
#c = Connection(server, authentication=SASL, sasl_mechanism=KERBEROS)
#c.bind()
#print(c.extend.standard.who_am_i())
ldapsearch = '/usr/bin/ldapsearch'
ldapsearch_args = [ldapsearch, '-Y', 'GSSAPI', '-v', '-h', ldap_server, '-b', base_dn, '-LLL']
ldapsearch = Popen(ldapsearch_args, stdout=PIPE, stderr=PIPE)
ldapsearch.wait()
ldapsearch_res = ldapsearch.stdout.read()

if ldapsearch.returncode > 0:
	print("Cannot perform ldapsearch. STDERR is:\n{}".format(ldapsearch.stderr.read()))

os.remove(temp_file)
fh = open(temp_file,"w")
fh.write(ldapsearch_res)
fh.close()

LDAP_USER_FORMAT = '''-----------------------------------------
Logon Name: {logon_name}
UPN: {user_principal_name}
Full Name: {full_name}
First Name: {first_name}
Last Name: {last_name}
Email: {email}
Display Name: {display_name}
Groups: {groups}'''

parser = LDIFParser(open(temp_file, 'rb'))
print("Domain users:")
for dn, entry in parser.parse():
    #print('got entry record: %s' % dn)
    #pprint(entry)
    groups = {}
    logon_name = getFirstOrEmpty(entry.get('sAMAccountName', []))
    user_principal_name = getFirstOrEmpty(entry.get('userPrincipalName', []))
    full_name = getFirstOrEmpty(entry.get('name', []))
    first_name = getFirstOrEmpty(entry.get('givenName', []))
    last_name = getFirstOrEmpty(entry.get('sn', []))
    email = getFirstOrEmpty(entry.get('mail', []))
    display_name = getFirstOrEmpty(entry.get('displayName', []))
    groups = entry.get('memberOf', [])
    print(LDAP_USER_FORMAT.format(
            logon_name=logon_name.encode('utf-8'),
            user_principal_name=user_principal_name.encode('utf-8'),
            full_name=full_name.encode('utf-8'),
            first_name=first_name.encode('utf-8'),
            last_name=last_name.encode('utf-8'),
            email=email.encode('utf-8'),
            display_name=display_name.encode('utf-8'),
            groups=str(groups).encode('utf-8')
            )
        )

kdestroy = Popen(['/usr/bin/kdestroy'])
kdestroy.wait()
