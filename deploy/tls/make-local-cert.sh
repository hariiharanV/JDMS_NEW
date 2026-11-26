#!/usr/bin/env bash
# Makes a local certificate authority and an HTTPS certificate for this machine, so the local stack can be
# opened from other machines (deploy/tls/README.md). Output in deploy/tls/local (git-ignored).
#
# Usage: deploy/tls/make-local-cert.sh <ip-or-host-name> [<more names>...]
set -euo pipefail
[ $# -ge 1 ] || { echo "usage: $0 <ip-or-host-name> [<more names>...]" >&2; exit 2; }

TLS="$(cd "$(dirname "$0")" && pwd)"
OUT="$TLS/local"
HOST="$1"
mkdir -p "$OUT"
cd "$OUT"

san="DNS:localhost,IP:127.0.0.1"
for name in "$@"; do
  if [[ "$name" =~ ^[0-9.]+$ ]]; then san="$san,IP:$name"; else san="$san,DNS:$name"; fi
done

# The authority signs the server certificate; testers import ca.crt once to trust it
if [ ! -f ca.key ]; then
  openssl req -x509 -newkey rsa:3072 -nodes -days 825 -subj "/CN=JDMS Next local CA ($(hostname))" \
    -keyout ca.key -out ca.crt -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null
fi
openssl req -newkey rsa:2048 -nodes -subj "/CN=$HOST" -keyout server.key -out server.csr 2>/dev/null
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 397 -out server.crt \
  -extfile <(printf 'subjectAltName=%s\nextendedKeyUsage=serverAuth\nbasicConstraints=CA:FALSE\n' "$san") 2>/dev/null
rm -f server.csr
chmod 600 ca.key server.key

# Java trust store for jdms-core: the JDK's public authorities plus the local one ("changeit" is the JDK default)
JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
cp "$JAVA_HOME/lib/security/cacerts" truststore.p12
chmod 600 truststore.p12
keytool -importcert -noprompt -alias jdms-local-ca -file ca.crt -keystore truststore.p12 -storepass changeit >/dev/null

cat <<ENV

Certificate for: ${san//,/ }
Add these lines to deploy/.env, then restart Keycloak, jdms-core and the web front end:

JDMS_BIND_ADDRESS=0.0.0.0
JDMS_WEB_PUBLIC_URL=https://$HOST:5180
KEYCLOAK_PUBLIC_URL=https://$HOST:8543
VITE_OIDC_AUTHORITY=https://$HOST:8543/realms/jdms-local
JDMS_TLS_CERT=$OUT/server.crt
JDMS_TLS_KEY=$OUT/server.key
KC_HTTPS_CERTIFICATE_FILE=/opt/keycloak/conf/local-tls/server.crt
KC_HTTPS_CERTIFICATE_KEY_FILE=/opt/keycloak/conf/local-tls/server.key
JDK_JAVA_OPTIONS="-Djavax.net.ssl.trustStore=$OUT/truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit"

Give testers $OUT/ca.crt to import as a trusted authority (or accept the browser warning).
ENV
