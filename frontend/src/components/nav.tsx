import type { ComponentType, HTMLAttributes } from 'react';
import {
  AuditOutlined, CheckSquareOutlined, ClockCircleOutlined, ContainerOutlined, DeleteOutlined, FileTextOutlined,
  FolderOutlined, GlobalOutlined, HomeOutlined, SafetyOutlined, SearchOutlined, TeamOutlined, UploadOutlined,
  UserOutlined,
} from '@ant-design/icons';
import type { Action } from '../api/types';

export type NavGroupKey = 'work' | 'find' | 'governance' | 'admin' | 'security';

export interface NavItem {
  key: string;
  to: string;
  /** i18n key of the label. */
  label: string;
  icon: ComponentType<HTMLAttributes<HTMLSpanElement>>;
  /** Mirrored in right-to-left layouts (directional icons only). */
  flipRtl?: boolean;
  badge?: number;
  /** Matches only the exact path (home). */
  end?: boolean;
}

export interface NavGroup {
  key: NavGroupKey;
  items: NavItem[];
}

/**
 * The side menu for the caller (UI handoff §3.1): sections grouped by kind of work, and only those the caller may
 * use. Driven by the tenant-wide actions the server decides (GET /me/permissions), not by role names, so a bank
 * that changes what a role may do changes the menu with it; the server still checks every request.
 */
export function buildNav(can: (action: Action) => boolean, counts: { tasks?: number } = {}): NavGroup[] {
  const records = can('MANAGE_RETENTION') || can('MANAGE_HOLDS') || can('DISPOSE');
  const groups: NavGroup[] = [
    {
      key: 'work',
      items: [
        { key: 'home', to: '/', label: 'nav.home', icon: HomeOutlined, end: true },
        { key: 'tasks', to: '/tasks', label: 'nav.tasks', icon: CheckSquareOutlined, badge: counts.tasks },
        ...(can('UPLOAD') ? [{ key: 'upload', to: '/upload', label: 'nav.upload', icon: UploadOutlined }] : []),
      ],
    },
    {
      key: 'find',
      items: [
        { key: 'browse', to: '/browse', label: 'nav.browse', icon: FolderOutlined },
        { key: 'search', to: '/search', label: 'nav.search', icon: SearchOutlined, flipRtl: true },
      ],
    },
    {
      key: 'governance',
      items: [
        ...(records ? [{ key: 'records', to: '/records', label: 'nav.records', icon: ContainerOutlined }] : []),
        ...(can('RESTORE') ? [{ key: 'recycleBin', to: '/recycle-bin', label: 'nav.recycleBin', icon: DeleteOutlined }]
          : []),
      ],
    },
    {
      key: 'admin',
      items: can('ADMIN_METADATA') ? [
        { key: 'documentTypes', to: '/admin/types', label: 'nav.documentTypes', icon: FileTextOutlined },
        { key: 'folders', to: '/admin/folders', label: 'nav.folders', icon: FolderOutlined },
        { key: 'committees', to: '/admin/committees', label: 'nav.committees', icon: TeamOutlined },
        { key: 'languages', to: '/admin/languages', label: 'nav.languages', icon: GlobalOutlined },
        { key: 'pendingChanges', to: '/admin/changes', label: 'nav.pendingChanges', icon: ClockCircleOutlined },
      ] : [],
    },
    {
      key: 'security',
      items: [
        // pending changes sits under Admin when the caller has both
        ...(can('ADMIN_ACCESS') && !can('ADMIN_METADATA')
          ? [{ key: 'pendingChanges', to: '/admin/changes', label: 'nav.pendingChanges', icon: ClockCircleOutlined }]
          : []),
        ...(can('ADMIN_ACCESS') ? [{ key: 'accessRules', to: '/admin/access', label: 'nav.accessRules', icon: SafetyOutlined },
          { key: 'users', to: '/admin/users', label: 'nav.users', icon: UserOutlined }] : []),
        ...(can('VIEW_AUDIT') ? [{ key: 'audit', to: '/audit', label: 'nav.audit', icon: AuditOutlined }] : []),
      ],
    },
  ];
  return groups.filter((group) => group.items.length > 0);
}

/** Where /admin leads: the first administration screen the caller may use. */
export function adminHome(can: (action: Action) => boolean): string {
  if (can('ADMIN_METADATA')) return '/admin/types';
  if (can('ADMIN_ACCESS')) return '/admin/access';
  return '/';
}
