import { UserManager, WebStorageStateStore } from 'oidc-client-ts';

// Keycloak realm and public client (deploy/keycloak); set per environment at build time.
const authority = import.meta.env.VITE_OIDC_AUTHORITY ?? 'http://localhost:8180/realms/jdms-local';
const clientId = import.meta.env.VITE_OIDC_CLIENT_ID ?? 'jdms-web';

/**
 * Authorization code flow with PKCE. Tokens live in sessionStorage: they survive a reload but not the tab, and the
 * access token is short-lived and refreshed in the background.
 */
export const userManager = new UserManager({
  authority,
  client_id: clientId,
  redirect_uri: `${window.location.origin}/callback`,
  post_logout_redirect_uri: `${window.location.origin}/`,
  response_type: 'code',
  scope: 'openid',
  automaticSilentRenew: true,
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
});
