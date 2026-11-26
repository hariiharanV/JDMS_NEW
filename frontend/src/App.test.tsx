import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import i18n from './i18n';
import { App } from './App';
import { AuthContext, type AuthState } from './auth/AuthContext';

vi.mock('./components/DocumentViewer', () => ({ DocumentViewer: () => null }));

function renderApp(auth: Partial<AuthState> = {}) {
  const state: AuthState = { user: null, loading: false, login: vi.fn(async () => undefined),
    logout: vi.fn(async () => undefined), ...auth };
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>
        <AuthContext.Provider value={state}>
          <App />
        </AuthContext.Provider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return state;
}

describe('App shell', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en');
  });

  it('asks signed-out users to sign in, in English, left to right', async () => {
    const auth = renderApp();

    expect(screen.getByRole('heading', { name: 'Welcome to JDMS Next' })).toBeInTheDocument();
    expect(document.documentElement).toHaveAttribute('dir', 'ltr');
    expect(document.documentElement).toHaveAttribute('lang', 'en');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(auth.login).toHaveBeenCalled();
  });

  it('switches to Arabic and right to left', async () => {
    renderApp();

    await userEvent.click(screen.getByText('العربية'));

    expect(await screen.findByRole('heading', { name: 'مرحبًا بك في JDMS Next' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'تسجيل الدخول' })).toBeInTheDocument();
    expect(document.documentElement).toHaveAttribute('dir', 'rtl');
    expect(document.documentElement).toHaveAttribute('lang', 'ar');
  });
});
