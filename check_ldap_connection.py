ldap_host = '192.168.153.201'
domain = 'domain1.local'
user = 'cyberx1'
password = 'cruvuttj@4338'
search_base = 'cn=Users,dc=domain2,dc=local'

from ldap3 import Server, \
    Connection, \
    AUTO_BIND_NO_TLS, \
    SUBTREE, \
    ALL_ATTRIBUTES, SASL, DIGEST_MD5, GSSAPI, NTLM, ALL, SUBTREE, ALL_ATTRIBUTES, ALL_OPERATIONAL_ATTRIBUTES

server = Server(ldap_host, get_info=ALL, allowed_referral_hosts=[('*', True)])
try:
    c = Connection(server,
                        version=3,
                        auto_bind=True,
                        raise_exceptions=True,
                        user=domain+'\\'+user,
                        password=password,
                        authentication=NTLM                    
    					)
except:
    print("Cannot connect to LDAP server")
    raise

server_info = server.info.raw.get('rootDomainNamingContext')
print("Root domain name context: "+str(server_info))
if not c.bind():
    print('LDAP Bind error', c.result)
    exit(1)
else:
    print('\n\nConnection is OK\n\n')

entry_list = c.search(search_base = search_base,
         search_filter = '(&(objectClass=user)(SAMAccountName=*))',
         search_scope = SUBTREE,
         attributes = [ALL_ATTRIBUTES, ALL_OPERATIONAL_ATTRIBUTES],
         )

print("Domain users:")
LDAP_USER_FORMAT = '''-----------------------------------------
Logon Name: {logon_name}
UPN: {user_principal_name}
Full Name: {full_name}
First Name: {first_name}
Last Name: {last_name}
Email: {email}
Display Name: {display_name}
Groups: {groups}'''

for entry in c.response:
    items = entry['attributes'].get('memberOf', '')   
    groups = []
    for item in items:
        groups.append(item.split(',')[0])

    logon_name = entry['attributes'].get('sAMAccountName', '')
    user_principal_name = entry['attributes'].get('userPrincipalName', '')
    full_name = entry['attributes'].get('name', '')
    first_name = entry['attributes'].get('givenName', '')
    last_name = entry['attributes'].get('sn', '')
    email = entry['attributes'].get('mail', '')
    display_name = entry['attributes'].get('displayName', '')
    print(LDAP_USER_FORMAT.format(
            logon_name=logon_name,
            user_principal_name=user_principal_name,
            full_name=full_name,
            first_name=first_name,
            last_name=last_name,
            email=email,
            display_name=display_name,
            groups=list(groups))        
        )
c.unbind()
