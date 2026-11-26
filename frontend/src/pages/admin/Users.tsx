import { useState } from 'react';
import { SearchOutlined } from '@ant-design/icons';
import { App, Button, Card, Form, Input, Radio, Select, Space, Table, Tag } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api, ifMatch } from '../../api/client';
import { useBranches, useMe } from '../../api/queries';
import { CLASSIFICATIONS, type Classification, type Page, type PendingChange } from '../../api/types';
import { ClassificationLabel } from '../../components/ClassificationLabel';
import { EmptyState } from '../../components/EmptyState';
import { ErrorAlert } from '../../components/ErrorAlert';
import { FactList } from '../../components/FactList';
import { Chip } from '../../components/StatusChip';

export interface UserAccess {
  id: string;
  username: string;
  displayName: string | null;
  email: string | null;
  principalType: 'USER' | 'CLIENT';
  homeBranchCode: string | null;
  allBranches: boolean;
  branchCodes: string[];
  clearance: Classification;
  status: 'ACTIVE' | 'DISABLED' | string;
  roles: string[];
  version: number;
}

interface ScopeForm {
  scope: 'ALL' | 'LIST';
  branchCodes: string[];
  clearance: Classification;
}

/**
 * Users (B-17, ADR-0007): each user's roles (from Keycloak, as at their last sign-in), branches and clearance. Branches
 * and clearance belong to JDMS Next after the first sign-in; changing them is a proposal a second security
 * administrator approves, and nobody changes their own.
 */
export function Users() {
  const { t } = useTranslation();
  const users = useQuery({ queryKey: ['admin', 'users'], queryFn: () => api<Page<UserAccess>>('/admin/users?size=200') });
  const [filter, setFilter] = useState('');
  const [selectedId, setSelectedId] = useState<string>();
  const [editing, setEditing] = useState(false);
  const needle = filter.trim().toLowerCase();
  const all = (users.data?.items ?? []).filter((user) => user.principalType === 'USER');
  const visible = all.filter((user) => !needle || [user.username, user.displayName ?? '', ...user.roles,
    ...user.branchCodes].some((value) => value.toLowerCase().includes(needle)));
  const selected = all.find((user) => user.id === selectedId) ?? visible[0];
  const select = (user: UserAccess) => { setSelectedId(user.id); setEditing(false); };

  return (
    <div className="jdms-master-detail">
      <Card className="jdms-table-card jdms-master">
        <div className="jdms-toolbar">
          <Input allowClear prefix={<SearchOutlined aria-hidden />} placeholder={t('users.filter')} aria-label={t('users.filter')}
            value={filter} onChange={(event) => setFilter(event.target.value)} />
        </div>
        <ErrorAlert error={users.error} />
        <Table<UserAccess>
          rowKey="id"
          loading={users.isLoading}
          dataSource={visible}
          pagination={false}
          scroll={{ x: 'max-content' }}
          rowClassName={(user) => (user.id === selected?.id ? 'row-selected' : '')}
          onRow={(user) => ({ onClick: () => select(user) })}
          columns={[
            {
              title: t('users.user'),
              dataIndex: 'username',
              render: (username: string, user) => (
                <span className="jdms-title-cell">
                  <a href={`#${username}`} onClick={(event) => { event.preventDefault(); select(user); }}>
                    <strong>{user.displayName ?? username}</strong>
                  </a>
                  <span className="jdms-code">{username}</span>
                </span>
              ),
            },
            { title: t('users.roles'), dataIndex: 'roles', render: (roles: string[]) => <Roles roles={roles} /> },
            { title: t('users.branches'), dataIndex: 'branchCodes', render: (_: string[], user) => <BranchScope user={user} /> },
            {
              title: t('users.clearance'),
              dataIndex: 'clearance',
              render: (value: Classification) => <ClassificationLabel classification={value} />,
            },
          ]}
        />
      </Card>
      <Card className="jdms-detail">
        {!selected ? <EmptyState title={t('users.selectOne')} />
          : editing ? <ScopeEditor key={selected.id} user={selected} onDone={() => setEditing(false)} />
            : <UserDetail user={selected} onEdit={() => setEditing(true)} />}
      </Card>
    </div>
  );
}

function Roles({ roles }: { roles: string[] }) {
  const { t } = useTranslation();
  if (roles.length === 0) return <span className="jdms-muted">{t('users.noRoles')}</span>;
  return <span className="jdms-tags">{roles.map((role) => <Tag key={role}>{t(`role.${role}`, { defaultValue: role })}</Tag>)}</span>;
}

function BranchScope({ user }: { user: UserAccess }) {
  const { t } = useTranslation();
  return user.allBranches ? <>{t('access.branches.ALL')}</> : <>{user.branchCodes.join(', ') || t('users.noBranches')}</>;
}

function UserDetail({ user, onEdit }: { user: UserAccess; onEdit: () => void }) {
  const { t } = useTranslation();
  const me = useMe();
  const branches = useBranches();
  const self = me.data?.userId === user.id;
  const branchName = (code: string) => branches.data?.find((branch) => branch.code === code)?.name;
  return (
    <section aria-label={t('users.detail')} className="jdms-type-detail">
      <div className="jdms-type-head">
        <div>
          <div className="jdms-muted jdms-small"><span className="jdms-code">{user.username}</span></div>
          <h2 className="jdms-properties-title">{user.displayName ?? user.username}</h2>
        </div>
        <Button onClick={onEdit} disabled={self}>{t('users.changeAccess')}</Button>
      </div>
      {self ? <p className="jdms-muted">{t('users.selfNote')}</p> : null}
      <FactList headingLevel={3} sections={[{ key: 'access', facts: [
        { key: 'roles', label: t('users.roles'), value: <Roles roles={user.roles} /> },
        { key: 'branches', label: t('users.branches'), value: user.allBranches ? t('access.branches.ALL')
          : user.branchCodes.map((code) => (branchName(code) ? `${code} ${branchName(code)}` : code)).join(', ') || t('users.noBranches') },
        { key: 'home', label: t('users.homeBranch'), value: user.homeBranchCode ?? '—' },
        { key: 'clearance', label: t('users.clearance'), value: <ClassificationLabel classification={user.clearance} /> },
        { key: 'status', label: t('records.status'), value: user.status === 'ACTIVE'
          ? <Chip tone="success">{t('admin.status.ACTIVE')}</Chip> : <Chip tone="neutral" marker="bar">{user.status}</Chip> },
        { key: 'email', label: t('users.email'), value: user.email ?? '—' },
      ] }]} />
      <p className="jdms-muted">{t('users.rolesNote')}</p>
    </section>
  );
}

function ScopeEditor({ user, onDone }: { user: UserAccess; onDone: () => void }) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const branches = useBranches();
  const [form] = Form.useForm<ScopeForm>();
  const scope = Form.useWatch('scope', form);
  const save = useMutation({
    mutationFn: async (values: ScopeForm) => {
      // the change needs the user's current version (If-Match), read fresh so a stale list cannot overwrite
      const current = await api<UserAccess>(`/admin/users/${user.id}`);
      return api<PendingChange>(`/admin/users/${user.id}/scope`, {
        method: 'PUT', idempotent: true, headers: ifMatch(current.version),
        body: { allBranches: values.scope === 'ALL', branchCodes: values.scope === 'ALL' ? [] : values.branchCodes,
          clearance: values.clearance },
      });
    },
    onSuccess: () => {
      void message.success(t('users.proposed'));
      void queryClient.invalidateQueries({ queryKey: ['pending-changes'] });
      onDone();
    },
  });
  return (
    <section aria-label={t('users.changeAccess')} className="jdms-type-detail">
      <h2 className="jdms-properties-title">{t('users.changeAccessOf', { user: user.displayName ?? user.username })}</h2>
      <Form<ScopeForm> form={form} layout="vertical" onFinish={(values) => save.mutate(values)}
        initialValues={{ scope: user.allBranches ? 'ALL' : 'LIST', branchCodes: user.branchCodes, clearance: user.clearance }}>
        <Form.Item name="scope" label={t('users.branches')}>
          <Radio.Group options={[{ value: 'LIST', label: t('users.theseBranches') }, { value: 'ALL', label: t('access.branches.ALL') }]} />
        </Form.Item>
        {scope !== 'ALL' ? (
          <Form.Item name="branchCodes" label={t('users.branchList')}
            rules={[{ required: true, message: t('upload.required', { field: t('users.branchList') }) }]}>
            <Select mode="multiple" aria-label={t('users.branchList')} loading={branches.isLoading} optionFilterProp="label"
              options={(branches.data ?? []).map((branch) => ({ value: branch.code, label: `${branch.code} ${branch.name}` }))} />
          </Form.Item>
        ) : null}
        <Form.Item name="clearance" label={t('users.clearance')} extra={t('users.clearanceHint')}>
          <Select aria-label={t('users.clearance')}
            options={CLASSIFICATIONS.map((value) => ({ value, label: <ClassificationLabel classification={value} /> }))} />
        </Form.Item>
        <p className="jdms-muted">{t('users.approvalNote')}</p>
        <ErrorAlert error={save.error} />
        <Space wrap>
          <Button type="primary" htmlType="submit" loading={save.isPending}>{t('admin.propose')}</Button>
          <Button onClick={onDone}>{t('common.cancel')}</Button>
        </Space>
      </Form>
    </section>
  );
}
