#!/usr/bin/env python
'''

1. configure Kerberos user config check_ldap_connection_krb5.ini
2. execute: export KRB5_CONFIG=<path to check_ldap_connection_krb5.ini file>
3. Run ./check_ldap_connection_3.py script

'''
import ldap, sys, os
import ldap.sasl
from subprocess import Popen, PIPE

def main():
    domain = 'DOMAIN3.LOCAL'
    address = 'ldap://dc4.domain4.local:389'
    base_dn = 'dc=domain4,dc=local'
    username = 'cyberx3'
    password = 'cruvuttj@4338'
    krb5_config_path = '/home/jktu/stuff/check_ldap_connection_krb5.ini'
    #krb5_config_path = None
    krb = KerberosClient(username=username, realm=domain, password=password, krb5_config_path=krb5_config_path)
    with LDAPClient(host=address, username=username, password=password, domain=domain, authentication_mechanism='GSSAPI', base_dn=base_dn) as ldap_client:
        users = ldap_client.get_user(username=username)
        users = list(users)

        for user in users:
            print user

    krb.destroy()

# ------------------------------------------------------------
class KerberosClient(object):
    def __init__(self, username, realm, password, krb5_config_path=None):
        if krb5_config_path != None:
            os.environ["KRB5_CONFIG"] = krb5_config_path
        kinit = '/usr/bin/kinit'
        kinit_args = [ kinit, '%s@%s' % (username, realm) ]
        kinit = Popen(kinit_args, stdin=PIPE, stdout=PIPE, stderr=PIPE)
        kinit.stdin.write('%s\n' % password)
        kinit.wait()
        kinit_res = kinit.stderr.read()
        if kinit.returncode > 0:
	    print("Cannot initialize Kerberos. Error is: {}".format(kinit.stderr.read()))
	    sys.exit(kinit.returncode)

    def destroy(self):
        kdestroy = Popen(['/usr/bin/kdestroy'])
        kdestroy.wait()


class LDAPClient(object):
    def __init__(self, host, username, password, domain, distinguished_name=None, authentication_mechanism='DIGEST-MD5', base_dn=None):
        self.host = host
        self.username = username
        self.password = password
        self.domain = domain
        self.authentication_mechanism = authentication_mechanism
        self.base_dn = base_dn

        self.distinguished_name = distinguished_name if distinguished_name else self._domain_to_distinguished_name(domain)
        self._session = None

    def connect(self):
        session = ldap.initialize(self.host, trace_level=0)
        session.set_option(ldap.OPT_REFERRALS, 0)

        sasl_auth = ldap.sasl.sasl({
            ldap.sasl.CB_AUTHNAME: self.username,
            ldap.sasl.CB_PASS: self.password,
        },
            self.authentication_mechanism
        )

        session.sasl_interactive_bind_s("", sasl_auth)

        self._session = session
        return self

    def disconnect(self):
        if self._session:
            self._session.unbind_s()

        self._session = None

    def __enter__(self):
        return self.connect()

    def __exit__(self, type, value, traceback):
        return self.disconnect()

    def execute_query(self, query):

        if not self._session:
            raise RuntimeError('LDAP session is not opened')

#        ldap_result_id = self._session.search(self.distinguished_name, ldap.SCOPE_SUBTREE, query)
        ldap_result_id = self._session.search(self.base_dn, ldap.SCOPE_SUBTREE, query)
        result_all_type, result_all_data = self._session.result(ldap_result_id, 1)
        for result_type, result_data in result_all_data:
            if result_type is not None:
                yield result_data

    def get_user(self, username=None):
        if not username:
            username = self.username

        #query = '(SAMAccountName={username})'
        query = '(SAMAccountName=*)'
        results = self.execute_query(query.format(username=username))

        for result in results:
            yield LDAPUser(result)

    @staticmethod
    def _domain_to_distinguished_name(domain):
        return ','.join(map(lambda x: 'dc=' + x, domain.split('.')))


LDAP_USER_FORMAT = '''------------------------------------------------
Logon Name: {logon_name}
UPN: {user_principal_name}
Full Name: {full_name}
First Name: {first_name}
Last Name: {last_name}
Email: {email}
Display Name: {display_name}
Groups: {groups}'''


class LDAPUser(object):
    def __init__(self, raw_user):

        self._raw_user = raw_user

        self.groups = list(self._parse_groups())

        self.logon_name = self._parse_string('sAMAccountName', '')
        self.user_principal_name = self._parse_string('userPrincipalName', '')
        self.full_name = self._parse_string('name', '')
        self.first_name = self._parse_string('givenName', '')
        self.last_name = self._parse_string('sn', '')
        self.email = self._parse_string('mail', '')
        self.display_name = self._parse_string('displayName', '')

    def __str__(self):
        return LDAP_USER_FORMAT.format(
            logon_name=self.logon_name,
            user_principal_name=self.user_principal_name,
            full_name=self.full_name,
            first_name=self.first_name,
            last_name=self.last_name,
            email=self.email,
            display_name=self.display_name,
            groups=self.groups)

    def _parse_groups(self):

        member_of = set()
        items = self._raw_user.get('memberOf', [])
        for item in items:
            member_of = member_of.union(map(lambda x: x.lower().strip(), item.split(',')))

        for item in member_of:
            if not item.startswith('cn='):
                continue

            yield item[3:]

    def _parse_string(self, key, default=None, multi=False):
        result = self._raw_user.get(key, [])
        if len(result):
            return result if multi else result[0]
        return default


if __name__ == '__main__':
    main()

