"""Create local configuration once; keep existing credentials and data."""
import os
import secrets
import shutil
import subprocess
from pathlib import Path

root = Path('/workspace')
owner = root.stat()


def directory(path):
    if not path.exists():
        directory(path.parent)
        path.mkdir()
        os.chown(path, owner.st_uid, owner.st_gid)


def create(path, content):
    directory(path.parent)
    if not path.exists():
        path.write_text(content, encoding='utf-8')
        os.chown(path, owner.st_uid, owner.st_gid)


create(root / 'Task1/.env',
       f'BIONICPRO_CLIENT_SECRET={secrets.token_urlsafe(32)}\n'
       f'LDAP_ADMIN_PASSWORD={secrets.token_urlsafe(32)}\n'
       'YANDEX_ENABLED=false\nYANDEX_CLIENT_ID=\nYANDEX_CLIENT_SECRET=\n')
create(root / 'Task3/.env', ''.join(f'{name}={secrets.token_hex(32)}\n' for name in (
    'MINIO_ROOT_USER', 'MINIO_ROOT_PASSWORD', 'REPORTS_S3_ACCESS_KEY', 'REPORTS_S3_SECRET_KEY')))
create(root / 'Task4/.env', f'CDC_PASSWORD={secrets.token_hex(32)}\n')
create(root / 'Task1/.local/token-encryption.key', secrets.token_urlsafe(32))

tls = root / 'Task1/.local/tls'
directory(tls)
certificate = tls / 'tls.crt'
key = tls / 'tls.key'
if certificate.exists() != key.exists():
    raise RuntimeError('Both tls.crt and tls.key must exist; refusing to replace an existing file')
if not certificate.exists():
    subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-sha256',
                    '-days', '365', '-keyout', str(key), '-out', str(certificate),
                    '-subj', '/CN=localhost',
                    '-addext', 'subjectAltName=DNS:localhost,DNS:openldap,DNS:keycloak,IP:127.0.0.1',
                    '-addext', 'basicConstraints=critical,CA:TRUE'],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    os.chown(key, owner.st_uid, owner.st_gid)
    os.chown(certificate, owner.st_uid, owner.st_gid)
if not (tls / 'ca.crt').exists():
    shutil.copyfile(certificate, tls / 'ca.crt')
    os.chown(tls / 'ca.crt', owner.st_uid, owner.st_gid)

lines = (root / 'Task4/.env').read_text(encoding='utf-8-sig').splitlines()
password = next((line.partition('=')[2] for line in lines if line.startswith('CDC_PASSWORD=')), '')
if not password:
    raise RuntimeError('CDC_PASSWORD is missing in Task4/.env')
credentials = root / 'Task4/.local/credentials.properties'
content = f'password={password}\n'
if credentials.exists() and credentials.read_text(encoding='utf-8') != content:
    raise RuntimeError('Task4/.env and credentials.properties differ; existing credentials preserved')
create(credentials, content)
print('Local configuration and TLS ready. Existing files and data preserved.')
