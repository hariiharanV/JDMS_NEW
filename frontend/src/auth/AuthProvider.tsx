import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import type { User } from 'oidc-client-ts';
import { useNavigate } from 'react-router';
import { AuthContext, type AuthState } from './AuthContext';
import { userManager } from './oidc';

/** Keeps the signed-in user in state, completes the login redirect on /callback and follows token renewals. */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();

  useEffect(() => {
    let active = true;
    const finish = (signedIn: User | null) => {
      if (active) {
        setUser(signedIn && !signedIn.expired ? signedIn : null);
        setLoading(false);
      }
    };
    if (window.location.pathname === '/callback') {
      userManager
        .signinRedirectCallback()
        .then((signedIn) => {
          finish(signedIn);
          const returnTo = typeof signedIn.state === 'string' && signedIn.state.startsWith('/') ? signedIn.state : '/';
          void navigate(returnTo, { replace: true });
        })
        .catch(() => {
          finish(null);
          void navigate('/', { replace: true });
        });
    } else {
      userManager.getUser().then(finish, () => finish(null));
    }
    const loaded = (renewed: User) => setUser(renewed);
    const unloaded = () => setUser(null);
    userManager.events.addUserLoaded(loaded);
    userManager.events.addUserUnloaded(unloaded);
    userManager.events.addAccessTokenExpired(unloaded);
    return () => {
      active = false;
      userManager.events.removeUserLoaded(loaded);
      userManager.events.removeUserUnloaded(unloaded);
      userManager.events.removeAccessTokenExpired(unloaded);
    };
  }, [navigate]);

  const login = useCallback(async (returnTo?: string) => {
    await userManager.signinRedirect({ state: returnTo ?? '/' });
  }, []);

  const logout = useCallback(async () => {
    await userManager.signoutRedirect();
  }, []);

  const value = useMemo<AuthState>(() => ({ user, loading, login, logout }), [user, loading, login, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
