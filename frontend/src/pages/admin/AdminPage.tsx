import { useState } from 'react';
import { PlusOutlined } from '@ant-design/icons';
import { Button } from 'antd';
import { Navigate } from 'react-router';
import { useTranslation } from 'react-i18next';
import { usePermissions } from '../../api/queries';
import { LinkButton } from '../../components/LinkButton';
import { PageHeader } from '../../components/PageHeader';
import { adminHome } from '../../components/nav';
import { AccessRules } from './AccessRules';
import { Committees } from './Committees';
import { Folders } from './Folders';
import { DocumentTypeList, TypeEditor } from './DocumentTypes';
import { Languages } from './Languages';
import { PendingChanges } from './PendingChanges';
import { Users } from './Users';

// Administration (spec §8): every change is a proposal another administrator approves (maker-checker).
// Each part is its own menu entry and route (UI handoff §3.1); /admin leads to the first one the caller may use.

export function AdminHome() {
  const { can, isPending } = usePermissions();
  return isPending ? null : <Navigate to={adminHome(can)} replace />;
}

export function DocumentTypesPage() {
  const { t } = useTranslation();
  return (
    <section className="page">
      <PageHeader title={t('admin.documentTypes')} description={t('admin.typesDescription')}
        actions={<LinkButton type="primary" icon={<PlusOutlined aria-hidden  />} to="/admin/types/new">{t('admin.newType')}</LinkButton>} />
      <DocumentTypeList />
    </section>
  );
}

export function TypeEditorPage() {
  return <TypeEditor />;
}

export function LanguagesPage() {
  const { t } = useTranslation();
  return (
    <section className="page">
      <PageHeader title={t('admin.languages')} description={t('admin.languagesDescription')} />
      <Languages />
    </section>
  );
}

export function PendingChangesPage() {
  const { t } = useTranslation();
  return (
    <section className="page">
      <PageHeader title={t('admin.pendingChanges')} description={t('admin.pendingDescription')} />
      <PendingChanges />
    </section>
  );
}

export function AccessRulesPage() {
  const { t } = useTranslation();
  const [proposing, setProposing] = useState(false);
  return (
    <section className="page">
      <PageHeader title={t('admin.accessRules')} description={t('access.description')}
        actions={proposing ? undefined : <Button type="primary" onClick={() => setProposing(true)}>{t('access.propose')}</Button>} />
      <AccessRules proposing={proposing} onProposing={setProposing} />
    </section>
  );
}

export function CommitteesPage() {
  const { t } = useTranslation();
  const [creating, setCreating] = useState(false);
  return (
    <section className="page">
      <PageHeader title={t('committees.title')} description={t('committees.description')}
        actions={creating ? undefined : <Button type="primary" icon={<PlusOutlined aria-hidden />} onClick={() => setCreating(true)}>{t('committees.new')}</Button>} />
      <Committees creating={creating} onCreated={() => setCreating(false)} />
    </section>
  );
}

export function UsersPage() {
  const { t } = useTranslation();
  return (
    <section className="page">
      <PageHeader title={t('users.title')} description={t('users.description')} />
      <Users />
    </section>
  );
}

export function FoldersPage() {
  const { t } = useTranslation();
  return (
    <section className="page">
      <PageHeader title={t('folders.title')} description={t('folders.description')} />
      <Folders />
    </section>
  );
}
