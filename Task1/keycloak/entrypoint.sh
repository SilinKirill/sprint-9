#!/bin/bash
set -euo pipefail
# Keep the development CA trust local to Keycloak; hostname checks remain enabled.
if [ ! -f /opt/keycloak/conf/truststore.p12 ]; then
  java_root="$(dirname "$(dirname "$(readlink -f "$(command -v keytool)")")")"
  keytool -importkeystore -noprompt \
    -srckeystore "$java_root/lib/security/cacerts" -srcstorepass changeit \
    -destkeystore /opt/keycloak/conf/truststore.p12 \
    -deststorepass changeit -deststoretype PKCS12 >/dev/null 2>&1
  keytool -importcert -noprompt -alias bionicpro-local \
    -file /certs/tls.crt -keystore /opt/keycloak/conf/truststore.p12 \
    -storepass changeit -storetype PKCS12
fi
export KC_SPI_TRUSTSTORE_FILE_FILE=/opt/keycloak/conf/truststore.p12
export KC_SPI_TRUSTSTORE_FILE_PASSWORD=changeit
export KC_SPI_TRUSTSTORE_FILE_HOSTNAME_VERIFICATION_POLICY=STRICT
exec /opt/keycloak/bin/kc.sh "$@"
