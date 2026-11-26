import { useState } from 'react';
import { CheckOutlined, CloseOutlined } from '@ant-design/icons';
import { App, Button, Card, Col, Form, Input, Radio, Row, Select, Space } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/client';
import { useCabinets, useDocumentTypes } from '../../api/queries';
import type { Page } from '../../api/types';
import { sentenceCase } from '../../components/auditActions';
import { CheckboxGroupField } from '../../components/CheckboxGroupField';
import { EmptyState } from '../../components/EmptyState';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Chip } from '../../components/StatusChip';
import { useLocalizedLabel } from '../../i18n/labels';
import { ACTION_GROUPS, buildMatrix, KNOWN_ACTIONS, ROLE_ORDER, type Rule, type ScopeType } from './accessMatrix';
import { FolderPicker } from './FolderPicker';

interface RuleForm {
  principalType: 'ROLE' | 'USER';
  principalId: string;
  actions: string[];
  scopeType: ScopeType;
  scopeId?: string;
  effect: 'ALLOW' | 'DENY';
  branchConstraint: 'OWN' | 'ALL' | 'LIST';
  branchCodes?: string;
  description?: string;
}

/**
 * Access rules (UI handoff §6.9): the whole-bank role rules as a role × action matrix, the narrower rules under it,
 * and a rule proposal that opens in place. Every change is a proposal a second security administrator approves.
 */
export function AccessRules({ proposing, onProposing }: { proposing: boolean; onProposing: (open: boolean) => void }) {
  const { t } = useTranslation();
  const [selected, setSelected] = useState<{ role: string; action: string } | null>(null);
  const [prefill, setPrefill] = useState<Partial<RuleForm>>();
  const rules = useQuery({ queryKey: ['admin', 'access-rules'], queryFn: () => api<Page<Rule>>('/admin/access-rules?size=200') });
  const matrix = buildMatrix(rules.data?.items ?? []);
  const groups = [...ACTION_GROUPS, ...(matrix.otherActions.length > 0 ? [{ key: 'other', actions: matrix.otherActions }] : [])];
  const roleName = (role: string) => t(`role.${role}`, { defaultValue: sentenceCase(role) });
  const actionName = (action: string) => t(`actions.${action}`, { defaultValue: action });

  return (
    <>
      {proposing ? (
        <RuleProposal key={JSON.stringify(prefill ?? {})} initial={prefill} onClose={() => { onProposing(false); setPrefill(undefined); }} />
      ) : null}
      <ErrorAlert error={rules.error} />
      <Card className="jdms-table-card" title={<h2 className="jdms-card-title">{t('access.standard')}</h2>}
        extra={(
          <span className="jdms-legend">
            <span><span className="jdms-cell jdms-cell-allow" aria-hidden="true"><CheckOutlined aria-hidden  /></span>{t('access.allowed')}</span>
            <span><span className="jdms-cell jdms-cell-deny" aria-hidden="true"><CloseOutlined aria-hidden  /></span>{t('access.denied')}</span>
            <span><span className="jdms-cell" aria-hidden="true" />{t('access.notGranted')}</span>
          </span>
        )}>
        <div className="jdms-matrix-scroll">
          <table className="jdms-matrix">
            <caption className="visually-hidden">{t('access.caption')}</caption>
            <thead>
              <tr>
                <td className="jdms-matrix-corner" />
                {groups.map((group) => (
                  <th key={group.key} scope="colgroup" colSpan={group.actions.length} className={`jdms-matrix-group jdms-group-${group.key}`}>
                    {t(`access.groups.${group.key}`)}
                  </th>
                ))}
              </tr>
              <tr>
                <th scope="col" className="jdms-matrix-role">{t('access.role')}</th>
                {groups.flatMap((group) => group.actions).map((action) => (
                  <th key={action} scope="col" className="jdms-matrix-action">{actionName(action)}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {matrix.roles.map((role) => (
                <tr key={role}>
                  <th scope="row" className="jdms-matrix-role">{roleName(role)}</th>
                  {groups.flatMap((group) => group.actions).map((action) => {
                    const value = matrix.cell(role, action);
                    const current = selected?.role === role && selected.action === action;
                    return (
                      <td key={action}>
                        <button type="button" aria-pressed={current}
                          className={`jdms-cell${value === 'ALLOW' ? ' jdms-cell-allow' : value === 'DENY' ? ' jdms-cell-deny' : ''}`}
                          aria-label={t('access.cellLabel', {
                            role: roleName(role), action: actionName(action),
                            value: value === 'ALLOW' ? t('access.allowed') : value === 'DENY' ? t('access.denied') : t('access.notGranted'),
                          })}
                          onClick={() => setSelected(current ? null : { role, action })}>
                          {value === 'ALLOW' ? <CheckOutlined aria-hidden  /> : value === 'DENY' ? <CloseOutlined aria-hidden  /> : null}
                        </button>
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {selected ? (
          <section className="jdms-cell-detail" aria-label={t('access.detail')}>
            <h3 className="jdms-panel-title">{roleName(selected.role)} · {actionName(selected.action)}</h3>
            {matrix.rulesFor(selected.role, selected.action).length === 0 ? <p>{t('access.noRule')}</p> : (
              <ul>
                {matrix.rulesFor(selected.role, selected.action).map((rule) => (
                  <li key={rule.id}>
                    <EffectChip effect={rule.effect} /> {rule.description ?? t('access.noDescription')}
                    <span className="jdms-muted"> · {t(`access.branches.${rule.branchConstraint}`)}</span>
                  </li>
                ))}
              </ul>
            )}
            <Space wrap>
              <Button onClick={() => {
                setPrefill({ principalType: 'ROLE', principalId: selected.role, actions: [selected.action],
                  effect: matrix.cell(selected.role, selected.action) === 'ALLOW' ? 'DENY' : 'ALLOW' });
                onProposing(true);
                window.scrollTo({ top: 0 });
              }}>{t('access.proposeChange')}</Button>
              <Button type="text" onClick={() => setSelected(null)}>{t('common.close')}</Button>
            </Space>
          </section>
        ) : null}
      </Card>

      <Card title={<h2 className="jdms-card-title">{t('access.narrower')}</h2>} className="jdms-table-card jdms-narrower">
        <p className="jdms-card-note">{t('access.narrowerNote')}</p>
        {matrix.narrower.length === 0 ? <EmptyState title={t('access.noNarrower')} /> : (
          <ul className="jdms-rows">
            {matrix.narrower.map((rule) => (
              <li key={rule.id} className="jdms-row-item">
                <span className="jdms-scope-tag">{t(`access.scopes.${rule.scopeType}`)}</span>
                <span className="jdms-row-text">
                  <strong><bdi>{rule.scopeLabel ?? rule.scopeId ?? t('access.scopes.TENANT')}</bdi></strong>
                  <span>
                    {t(rule.effect === 'DENY' ? 'access.sentenceDeny' : 'access.sentenceAllow', {
                      who: rule.principalType === 'ROLE' ? roleName(rule.principalId) : rule.principalId,
                      actions: rule.actions.map(actionName).join(t('common.listSeparator')),
                    })}
                  </span>
                </span>
                <EffectChip effect={rule.effect} />
                {rule.description ? <span className="jdms-muted">{rule.description}</span> : null}
              </li>
            ))}
          </ul>
        )}
      </Card>
    </>
  );
}

function EffectChip({ effect }: { effect: Rule['effect'] }) {
  const { t } = useTranslation();
  return effect === 'DENY' ? <Chip tone="danger" marker="square">{t('admin.effects.DENY')}</Chip>
    : <Chip tone="success">{t('admin.effects.ALLOW')}</Chip>;
}

function RuleProposal({ initial, onClose }: { initial?: Partial<RuleForm>; onClose: () => void }) {
  const { t } = useTranslation();
  const label = useLocalizedLabel();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const cabinets = useCabinets();
  const types = useDocumentTypes();
  const [form] = Form.useForm<RuleForm>();
  const principalType = Form.useWatch('principalType', form);
  const scopeType = Form.useWatch('scopeType', form);
  const branches = Form.useWatch('branchConstraint', form);
  const propose = useMutation({
    mutationFn: (values: RuleForm) => api<unknown>('/admin/access-rules', {
      method: 'POST',
      idempotent: true,
      body: {
        principalType: values.principalType, principalId: values.principalId.trim(), actions: values.actions,
        scopeType: values.scopeType, scopeId: values.scopeType === 'TENANT' ? null : values.scopeId,
        effect: values.effect, branchConstraint: values.branchConstraint,
        branchCodes: values.branchConstraint === 'LIST' ? (values.branchCodes ?? '').split(/[\s,]+/).filter(Boolean) : [],
        description: values.description?.trim() || null,
      },
    }),
    onSuccess: () => {
      void message.success(t('access.proposed'));
      void queryClient.invalidateQueries({ queryKey: ['pending-changes'] });
      onClose();
    },
  });
  const required = (field: string) => [{ required: true, message: t('upload.required', { field }) }];

  return (
    <section aria-label={t('access.propose')} className="inline-panel">
      <h2 className="jdms-panel-title">{t('access.propose')}</h2>
      <Form<RuleForm> form={form} layout="vertical" onFinish={(values) => propose.mutate(values)}
        initialValues={{ principalType: 'ROLE', scopeType: 'TENANT', effect: 'ALLOW', branchConstraint: 'OWN', actions: [], ...initial }}>
        <Row gutter={16}>
          <Col xs={24} md={8}>
            <Form.Item name="principalType" label={t('access.who')}>
              <Radio.Group options={[{ value: 'ROLE', label: t('access.aRole') }, { value: 'USER', label: t('access.aUser') }]} />
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item name="principalId" label={principalType === 'USER' ? t('access.userId') : t('access.role')}
              rules={required(principalType === 'USER' ? t('access.userId') : t('access.role'))}>
              {principalType === 'USER' ? <Input maxLength={100} /> : (
                <Select aria-label={t('access.role')}
                  options={ROLE_ORDER.map((role) => ({ value: role, label: t(`role.${role}`, { defaultValue: sentenceCase(role) }) }))} />
              )}
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item name="effect" label={t('admin.effect')}>
              <Radio.Group options={[{ value: 'ALLOW', label: t('admin.effects.ALLOW') }, { value: 'DENY', label: t('admin.effects.DENY') }]} />
            </Form.Item>
          </Col>
          <Col xs={24}>
            <Form.Item name="actions" label={t('admin.actions')} rules={required(t('admin.actions'))}>
              <CheckboxGroupField label={t('admin.actions')} className="jdms-action-checks"
                options={KNOWN_ACTIONS.map((action) => ({ value: action, label: t(`actions.${action}`, { defaultValue: action }) }))} />
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item name="scopeType" label={t('admin.scope')}>
              <Select aria-label={t('admin.scope')}
                options={(['TENANT', 'CABINET', 'DOCUMENT_TYPE', 'FOLDER', 'DOCUMENT'] as const).map((value) => ({ value, label: t(`access.scopes.${value}`) }))} />
            </Form.Item>
          </Col>
          {scopeType && scopeType !== 'TENANT' ? (
            <Col xs={24} md={8}>
              <Form.Item name="scopeId" label={t(`access.scopes.${scopeType}`)} rules={required(t(`access.scopes.${scopeType}`))}
                extra={scopeType === 'FOLDER' ? t('access.folderHint') : scopeType === 'DOCUMENT' ? t('access.idHint') : undefined}>
                {scopeType === 'CABINET' ? (
                  <Select aria-label={t('access.scopes.CABINET')} options={(cabinets.data ?? []).map((cabinet) => ({ value: cabinet.id, label: cabinet.name }))} />
                ) : scopeType === 'DOCUMENT_TYPE' ? (
                  <Select aria-label={t('access.scopes.DOCUMENT_TYPE')} showSearch={{ optionFilterProp: 'label' }}
                    options={(types.data ?? []).map((type) => ({ value: type.id, label: label(type) }))} />
                ) : scopeType === 'FOLDER' ? <FolderPicker label={t('access.scopes.FOLDER')} /> : <Input maxLength={36} />}
              </Form.Item>
            </Col>
          ) : null}
          <Col xs={24} md={8}>
            <Form.Item name="branchConstraint" label={t('access.branchesLabel')}>
              <Select aria-label={t('access.branchesLabel')}
                options={(['OWN', 'ALL', 'LIST'] as const).map((value) => ({ value, label: t(`access.branches.${value}`) }))} />
            </Form.Item>
          </Col>
          {branches === 'LIST' ? (
            <Col xs={24} md={8}>
              <Form.Item name="branchCodes" label={t('access.branchCodes')} rules={required(t('access.branchCodes'))}><Input /></Form.Item>
            </Col>
          ) : null}
          <Col xs={24}>
            <Form.Item name="description" label={t('admin.description')}><Input maxLength={500} /></Form.Item>
          </Col>
        </Row>
        <ErrorAlert error={propose.error} />
        <Space wrap>
          <Button type="primary" htmlType="submit" loading={propose.isPending}>{t('admin.propose')}</Button>
          <Button onClick={onClose}>{t('common.cancel')}</Button>
        </Space>
      </Form>
    </section>
  );
}
