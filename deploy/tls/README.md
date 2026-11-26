# Opening the local stack to other machines

By default the local stack is reachable from its own machine only (`http://localhost:5180`).
To let testers on the network use it, it has to be served over HTTPS: away from `localhost`,
browsers only allow the sign-in (authorization code with PKCE) on secure pages.

1. Make a certificate for the machine's address (IP or DNS name; several are allowed):

   ```bash
   deploy/tls/make-local-cert.sh 192.168.3.197
   ```

   It writes a local certificate authority, a server certificate and a Java trust store to
   `deploy/tls/local/` (git-ignored), and prints the lines to add to `deploy/.env`.

2. Add the printed lines to `deploy/.env`.

3. Restart with the new settings (`set -a; . deploy/.env; set +a` first, as usual):
   - Keycloak: `docker compose -f deploy/docker-compose.yml up -d --wait keycloak`
   - jdms-core: stop it and start it again; it reads the new issuer, and trusts the local
     authority through `JDK_JAVA_OPTIONS`
   - Web front end: `cd frontend && npm run build && npm run preview` (the sign-in address is
     built in)

4. Open `https://<address>:5180`. The sign-in page is `https://<address>:8543`.

Testers import `deploy/tls/local/ca.crt` as a trusted certificate authority in their browser or
operating system once; otherwise the browser warns on both addresses and they have to accept
each warning.

What changes:

| | Default | Network access |
|---|---|---|
| Web screens | `http://localhost:5180` | `https://<address>:5180` |
| Keycloak | `http://localhost:8180` | `https://<address>:8543`; `http://localhost:8180` stays for local tools |
| Token issuer | `http://localhost:8180/realms/jdms-local` | `https://<address>:8543/realms/jdms-local` |

`npm run e2e:stack` and `npm run screenshots:manual` follow `JDMS_WEB_PUBLIC_URL`. The core API
(port 8080) is not changed by these settings.

This is for test servers only. Production uses the bank's own certificates and identity
provider (Installation Guide, chapter "Production notes").
