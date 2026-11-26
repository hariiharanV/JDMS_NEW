import { useState } from 'react';
import { DeleteOutlined, PlusOutlined, WarningOutlined } from '@ant-design/icons';
import { App, Button, Card, Col, Form, Input, InputNumber, Row, Select, Space, Table } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api, ifMatch } from '../../api/client';
import type { Page, PendingChange } from '../../api/types';
import { EmptyState } from '../../components/EmptyState';
import { ErrorAlert } from '../../components/ErrorAlert';
import { FactList } from '../../components/FactList';
import { Chip } from '../../components/StatusChip';

export interface CommitteeMember {
  userId: string;
  designation: string | null;
}

export interface Committee {
  id: string;
  code: string;
  name: string;
  minQuorum: number;
  status: 'ACTIVE' | 'INACTIVE';
  members: CommitteeMember[];
  version: number;
}

export interface Candidate {
  id: string;
  username: string;
  displayName: string | null;
  roles: string[];
}

/** Roles whose holders may approve, and so vote (the standard access rules). */
const VOTING_ROLES = ['COMMITTEE_MEMBER', 'CHECKER'];
/** The committee the shipped routing table sends Committee-route types to (ADR-0013). */
export const ROUTED_COMMITTEE = 'CREDIT_COMMITTEE';

interface CommitteeForm {
  code: string;
  name: string;
  minQuorum: number;
  members: { userId?: string; designation?: string }[];
}

function useCandidates() {
  return useQuery({ queryKey: ['admin', 'committee-candidates'],
    queryFn: () => api<Candidate[]>('/admin/committees/candidates'), staleTime: 60_000 });
}

/**
 * Committees (B-17, ADR-0013): who votes on Committee-route documents and how many approvals decide. Every change
 * (new committee, members, quorum, deactivation) is a proposal a second tenant administrator approves.
 */
export function Committees({ creating, onCreated }: { creating: boolean; onCreated: () => void }) {
  const { t } = useTranslation();
  const committees = useQuery({ queryKey: ['admin', 'committees'],
    queryFn: () => api<Page<Committee>>('/admin/committees?size=200') });
  const candidates = useCandidates();
  const [selectedId, setSelectedId] = useState<string>();
  const [editing, setEditing] = useState(false);
  const all = committees.data?.items ?? [];
  const selected = all.find((committee) => committee.id === selectedId) ?? all[0];
  const nameOf = (userId: string) => {
    const user = candidates.data?.find((candidate) => candidate.id === userId);
    return user ? user.displayName ?? user.username : userId;
  };

  if (creating) {
    return <CommitteeEditor candidates={candidates.data ?? []} onDone={onCreated} />;
  }
  return (
    <div className="jdms-master-detail">
      <Card className="jdms-table-card jdms-master">
        <ErrorAlert error={committees.error} />
        <Table<Committee>
          rowKey="id"
          loading={committees.isLoading}
          dataSource={all}
          pagination={false}
          scroll={{ x: 'max-content' }}
          locale={{ emptyText: <EmptyState title={t('committees.none')} description={t('committees.noneHint')} /> }}
          rowClassName={(committee) => (committee.id === selected?.id ? 'row-selected' : '')}
          onRow={(committee) => ({ onClick: () => { setSelectedId(committee.id); setEditing(false); } })}
          columns={[
            {
              title: t('committees.committee'),
              dataIndex: 'code',
              render: (code: string, committee) => (
                <span className="jdms-title-cell">
                  <a href={`#${code}`} onClick={(event) => { event.preventDefault(); setSelectedId(committee.id); setEditing(false); }}>
                    <strong>{committee.name}</strong>
                  </a>
                  <span className="jdms-code">{code}</span>
                </span>
              ),
            },
            { title: t('committees.members'), dataIndex: 'members', render: (members: CommitteeMember[]) => members.length },
            { title: t('committees.quorum'), dataIndex: 'minQuorum' },
            {
              title: t('records.status'),
              dataIndex: 'status',
              render: (value: Committee['status']) => (value === 'ACTIVE' ? <Chip tone="success">{t('admin.status.ACTIVE')}</Chip>
                : <Chip tone="neutral" marker="bar">{t('admin.status.INACTIVE')}</Chip>),
            },
          ]}
        />
      </Card>
      <Card className="jdms-detail">
        {!selected ? <EmptyState title={t('committees.selectOne')} />
          : editing ? <CommitteeEditor key={selected.id} committee={selected} candidates={candidates.data ?? []}
            onDone={() => setEditing(false)} />
            : <CommitteeDetail committee={selected} nameOf={nameOf} candidates={candidates.data ?? []}
              onEdit={() => setEditing(true)} />}
      </Card>
    </div>
  );
}

function CommitteeDetail({ committee, nameOf, candidates, onEdit }: {
  committee: Committee;
  nameOf: (userId: string) => string;
  candidates: Candidate[];
  onEdit: () => void;
}) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const deactivate = useMutation({
    mutationFn: () => api<PendingChange>(`/admin/committees/${committee.id}`, {
      method: 'DELETE', idempotent: true, headers: ifMatch(committee.version) }),
    onSuccess: () => {
      void message.success(t('admin.proposed'));
      void queryClient.invalidateQueries({ queryKey: ['pending-changes'] });
    },
  });
  return (
    <section aria-label={t('committees.detail')} className="jdms-type-detail">
      <div className="jdms-type-head">
        <div>
          <div className="jdms-muted jdms-small"><span className="jdms-code">{committee.code}</span></div>
          <h2 className="jdms-properties-title">{committee.name}</h2>
        </div>
        <Space wrap>
          <Button onClick={onEdit}>{t('committees.proposeChange')}</Button>
          {committee.status === 'ACTIVE' ? (
            <Button danger loading={deactivate.isPending} onClick={() => deactivate.mutate()}>{t('committees.deactivate')}</Button>
          ) : null}
        </Space>
      </div>
      <ErrorAlert error={deactivate.error} />
      <FactList headingLevel={3} sections={[{ key: 'rules', facts: [
        { key: 'quorum', label: t('committees.quorum'), value: t('committees.quorumValue', { quorum: committee.minQuorum, count: committee.members.length }) },
        { key: 'routing', label: t('committees.routing'), value: committee.code === ROUTED_COMMITTEE ? t('committees.routed') : t('committees.notRouted') },
      ] }]} />
      <h3 className="jdms-facts-title">{t('committees.members')}</h3>
      <ul className="jdms-field-list">
        {committee.members.map((member) => {
          const candidate = candidates.find((user) => user.id === member.userId);
          return (
            <li key={member.userId}>
              <span className="jdms-title-cell">
                <strong>{nameOf(member.userId)}</strong>
                <span className="jdms-title-sub">{member.designation ?? t('committees.member')}</span>
              </span>
              {candidate && !candidate.roles.some((role) => VOTING_ROLES.includes(role))
                ? <Chip tone="warning">{t('committees.cannotVote')}</Chip> : null}
            </li>
          );
        })}
      </ul>
      <p className="jdms-muted">{t('committees.votingNote')}</p>
    </section>
  );
}

function CommitteeEditor({ committee, candidates, onDone }: {
  committee?: Committee;
  candidates: Candidate[];
  onDone: () => void;
}) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<CommitteeForm>();
  const members = Form.useWatch('members', form) ?? [];
  const chosen = members.map((member) => member?.userId).filter((id): id is string => !!id);
  const save = useMutation({
    mutationFn: (values: CommitteeForm) => {
      const body = {
        code: values.code?.trim(), name: values.name.trim(), minQuorum: values.minQuorum,
        members: values.members.filter((member) => member.userId)
          .map((member) => ({ userId: member.userId, designation: member.designation?.trim() || null })),
        status: committee?.status,
      };
      return committee
        ? api<PendingChange>(`/admin/committees/${committee.id}`, { method: 'PUT', idempotent: true, headers: ifMatch(committee.version), body })
        : api<PendingChange>('/admin/committees', { method: 'POST', idempotent: true, body });
    },
    onSuccess: () => {
      void message.success(t('admin.proposed'));
      void queryClient.invalidateQueries({ queryKey: ['pending-changes'] });
      onDone();
    },
  });
  const label = (candidate: Candidate) => `${candidate.displayName ?? candidate.username} (${candidate.username})`;
  const required = (field: string) => [{ required: true, message: t('upload.required', { field }) }];

  return (
    <section aria-label={committee ? t('committees.proposeChange') : t('committees.new')} className="jdms-editor">
      <Card className="jdms-step" title={<h2 className="jdms-card-title">{committee ? t('committees.proposeChange') : t('committees.new')}</h2>}>
        <Form<CommitteeForm> form={form} layout="vertical" onFinish={(values) => save.mutate(values)}
          initialValues={committee
            ? { code: committee.code, name: committee.name, minQuorum: committee.minQuorum,
              members: committee.members.map((member) => ({ userId: member.userId, designation: member.designation ?? undefined })) }
            : { code: ROUTED_COMMITTEE, name: '', minQuorum: 2, members: [{}, {}, {}] }}>
          <Row gutter={16}>
            <Col xs={24} md={8}>
              <Form.Item name="code" label={t('committees.code')} rules={required(t('committees.code'))}
                extra={committee ? undefined : t('committees.codeHint', { code: ROUTED_COMMITTEE })}>
                <Input maxLength={30} disabled={!!committee} />
              </Form.Item>
            </Col>
            <Col xs={24} md={10}>
              <Form.Item name="name" label={t('committees.name')} rules={required(t('committees.name'))}>
                <Input maxLength={200} />
              </Form.Item>
            </Col>
            <Col xs={24} md={6}>
              <Form.Item name="minQuorum" label={t('committees.quorum')} rules={required(t('committees.quorum'))}
                extra={t('committees.quorumHint', { count: chosen.length })}>
                <InputNumber min={1} max={Math.max(1, chosen.length)} className="full-width" />
              </Form.Item>
            </Col>
          </Row>
          <h3 className="jdms-facts-title">{t('committees.members')}</h3>
          <Form.List name="members">
            {(fields, { add, remove }) => (
              <>
                {fields.map((field, index) => {
                  const userId = members[index]?.userId;
                  const candidate = candidates.find((user) => user.id === userId);
                  const cannotVote = candidate && !candidate.roles.some((role) => VOTING_ROLES.includes(role));
                  return (
                    <Row gutter={8} key={field.key} role="group" aria-label={t('committees.memberNumber', { number: index + 1 })}>
                      <Col xs={24} md={13}>
                        <Form.Item name={[field.name, 'userId']} label={t('committees.user')}
                          extra={cannotVote ? <span className="jdms-warning-text"><WarningOutlined aria-hidden /> {t('committees.cannotVoteHint')}</span> : undefined}>
                          <Select showSearch={{ optionFilterProp: 'label' }} allowClear aria-label={t('committees.user')}
                            options={candidates.map((user) => ({ value: user.id, label: label(user),
                              disabled: chosen.includes(user.id) && user.id !== userId }))} />
                        </Form.Item>
                      </Col>
                      <Col xs={20} md={9}>
                        <Form.Item name={[field.name, 'designation']} label={t('committees.designation')}>
                          <Input maxLength={100} placeholder={t('committees.designationHint')} />
                        </Form.Item>
                      </Col>
                      <Col xs={4} md={2} className="jdms-field-remove">
                        <Button type="text" icon={<DeleteOutlined aria-hidden />} aria-label={t('committees.removeMember', { number: index + 1 })}
                          onClick={() => remove(field.name)} />
                      </Col>
                    </Row>
                  );
                })}
                <Button icon={<PlusOutlined aria-hidden />} onClick={() => add({})}>{t('committees.addMember')}</Button>
              </>
            )}
          </Form.List>
          <ErrorAlert error={save.error} />
          <Space wrap className="jdms-editor-actions">
            <Button type="primary" htmlType="submit" loading={save.isPending}>{t('admin.propose')}</Button>
            <Button onClick={onDone}>{t('common.cancel')}</Button>
          </Space>
        </Form>
      </Card>
    </section>
  );
}
