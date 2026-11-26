import { Suspense, useEffect, useRef, useState } from 'react';
import { DownOutlined, MenuOutlined } from '@ant-design/icons';
import { Button, Dropdown, Layout, Skeleton } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { NavLink, Outlet, useLocation } from 'react-router';
import { api } from '../api/client';
import { useMe, usePermissions } from '../api/queries';
import { useAuth } from '../auth/AuthContext';
import type { CurrentUser, Page, Task } from '../api/types';
import { initials } from '../i18n/format';
import { GlobalSearch } from './GlobalSearch';
import { LanguageSwitcher } from './LanguageSwitcher';
import { buildNav } from './nav';
import { Notifications } from './Notifications';

const KNOWN_ROLES = ['MAKER', 'CHECKER', 'COMMITTEE_MEMBER', 'VIEWER', 'RECORDS_OFFICER', 'TENANT_ADMIN', 'SECURITY_ADMIN'];

/** "Maker · Branch 001": the caller's roles and branches under their name. */
function useUserLine(me: CurrentUser | undefined): string {
  const { t } = useTranslation();
  if (!me) return '';
  const roles = me.roles.filter((role) => KNOWN_ROLES.includes(role)).map((role) => t(`role.${role}`));
  // TODO(backend-gap: G2) branch names; the code stands in for now
  const branches = me.allBranches ? t('user.allBranches')
    : me.branchCodes.length > 0 ? t('user.branch', { branches: me.branchCodes.join(', ') }) : '';
  return [roles.join(', '), branches].filter(Boolean).join(' · ');
}

/**
 * The signed-in frame (UI handoff §3.1): navy header with the product, the bank, global search, language,
 * notifications and the user; a side menu grouped by kind of work holding only the sections the caller may use;
 * the page in <main>. Below the lg breakpoint the menu folds away behind a Menu button.
 */
export function AppShell() {
  const { t, i18n } = useTranslation();
  const location = useLocation();
  const { logout } = useAuth();
  const me = useMe();
  // a bank without Arabic gets neither the switch nor Arabic screens (ADR-0023); the remembered choice is kept
  const arabic = me.data?.languages.includes('ar') ?? false;
  const englishOnly = me.data !== undefined && !arabic;
  useEffect(() => {
    if (englishOnly && i18n.resolvedLanguage !== 'en') {
      void i18n.changeLanguage('en');
    }
  }, [englishOnly, i18n]);
  const { can } = usePermissions();
  const tasks = useQuery({ queryKey: ['tasks', 'count'], queryFn: () => api<Page<Task>>('/tasks?size=1'),
    refetchInterval: 60_000 });
  const userLine = useUserLine(me.data);
  const [narrow, setNarrow] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [openedAt, setOpenedAt] = useState(location.pathname);
  // following a link on a phone closes the menu
  if (menuOpen && openedAt !== location.pathname) {
    setMenuOpen(false);
  }
  const collapsed = narrow && !menuOpen;
  const toggle = useRef<HTMLButtonElement>(null);
  const closeMenu = () => {
    setMenuOpen(false);
    toggle.current?.focus();
  };
  const groups = buildNav(can, { tasks: tasks.data?.total });
  const governed = groups.some((group) => ['governance', 'admin', 'security'].includes(group.key));
  const name = me.data?.displayName ?? me.data?.username ?? '';

  return (
    <Layout className="jdms-shell">
      <a className="jdms-skip-link" href="#main">{t('app.skipToContent')}</a>
      <Layout.Header className="jdms-header">
        <div className="jdms-header-brand">
          {narrow ? (
            <Button ref={toggle} type="text" className="jdms-header-icon" icon={<MenuOutlined aria-hidden="true" />}
              aria-label={t('nav.menu')} aria-expanded={menuOpen} aria-controls="jdms-nav"
              onClick={() => { setMenuOpen(!menuOpen); setOpenedAt(location.pathname); }} />
          ) : null}
          <span className="jdms-wordmark"><b>{t('app.mark')}</b> {t('app.markSuffix')}</span>
          {/* TODO(backend-gap: G1) the bank's display name; the tenant code stands in for now */}
          <span className="jdms-header-bank">{me.data?.tenantCode}</span>
        </div>
        <GlobalSearch />
        <div className="jdms-header-tools">
          {arabic ? <LanguageSwitcher onDark compact /> : null}
          <Notifications />
          <Dropdown trigger={['click']}
            menu={{ items: [{ key: 'logout', label: t('auth.signOut') }], onClick: () => void logout() }}>
            <Button type="text" className="jdms-user">
              <span className="jdms-avatar" aria-hidden="true">{initials(name).toUpperCase()}</span>
              <span className="jdms-user-text">
                <span className="jdms-user-name">{name}</span>
                {userLine ? <span className="jdms-user-line">{userLine}</span> : null}
              </span>
              <DownOutlined aria-hidden="true" className="jdms-user-caret" />
            </Button>
          </Dropdown>
        </div>
      </Layout.Header>
      <Layout hasSider className="jdms-body">
        <Layout.Sider width={220} theme="light" breakpoint="lg" collapsedWidth={0} collapsed={collapsed}
          trigger={null} onBreakpoint={setNarrow}
          className={narrow && menuOpen ? 'jdms-sider jdms-sider-overlay' : 'jdms-sider'}>
          {/* eslint-disable-next-line jsx-a11y-x/no-noninteractive-element-interactions -- Escape closes the phone menu */}
          <nav id="jdms-nav" aria-label={t('nav.label')} className="jdms-nav" inert={collapsed}
            onKeyDown={(event) => { if (event.key === 'Escape' && narrow && menuOpen) closeMenu(); }}>
            {groups.map((group) => (
              <div key={group.key} className="jdms-nav-group" role="group" aria-labelledby={`nav-${group.key}`}>
                <div id={`nav-${group.key}`} className="jdms-nav-group-title">{t(`nav.group.${group.key}`)}</div>
                <ul>
                  {group.items.map((item) => (
                    <li key={item.key}>
                      <NavLink to={item.to} end={item.end}
                        className={({ isActive }) => (isActive ? 'jdms-nav-item jdms-nav-active' : 'jdms-nav-item')}>
                        <item.icon aria-hidden="true" className={item.flipRtl ? 'jdms-flip-rtl' : undefined} />
                        <span className="jdms-nav-label">{t(item.label)}</span>
                        {item.badge ? (
                          <span className="jdms-nav-badge" aria-label={t('nav.waiting', { count: item.badge })}>
                            {item.badge}
                          </span>
                        ) : null}
                      </NavLink>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
            {governed ? null : <p className="jdms-nav-note">{t('nav.moreForRoles')}</p>}
          </nav>
        </Layout.Sider>
        <Layout.Content id="main" tabIndex={-1} className="jdms-content">
          <Suspense fallback={<Skeleton active paragraph={{ rows: 6 }} />}>
            <Outlet />
          </Suspense>
        </Layout.Content>
      </Layout>
    </Layout>
  );
}
