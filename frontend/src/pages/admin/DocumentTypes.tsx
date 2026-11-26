import { useState } from 'react';
import { DeleteOutlined, PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { App, Button, Card, Checkbox, Col, Form, Input, InputNumber, Row, Select, Space, Switch, Table, Tag } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useNavigate, useParams } from 'react-router';
import { api, ifMatch } from '../../api/client';
import { useArabicEnabled, useCabinets } from '../../api/queries';
import { CLASSIFICATIONS, type Classification, type FieldDataType, type Page } from '../../api/types';
import { CheckboxGroupField } from '../../components/CheckboxGroupField';
import { ClassificationLabel } from '../../components/ClassificationLabel';
import { EmptyState } from '../../components/EmptyState';
import { ErrorAlert } from '../../components/ErrorAlert';
import { FactList } from '../../components/FactList';
import { LinkButton } from '../../components/LinkButton';
import { PageHeader } from '../../components/PageHeader';
import { Chip } from '../../components/StatusChip';
import { useLocalizedLabel } from '../../i18n/labels';

interface AdminField {
  key: string;
  labelEn: string;
  labelAr: string | null;
  dataType: FieldDataType;
  mandatory: boolean;
  maxLength: number | null;
  defaultValue: string | null;
  lovCode: string | null;
  searchable?: boolean;
  facet?: boolean;
  sensitive?: boolean;
  displayOrder?: number;
  displayRow?: number;
}

interface AdminType {
  id: string;
  cabinetId: string;
  code: string;
  nameEn: string;
  nameAr: string | null;
  category: string;
  channels: string[];
  defaultClassification: Classification;
  processKey: string | null;
  metaChangeNeedsApproval: boolean;
  deleteRequiresApproval: boolean;
  declareRecordOnApproval: boolean;
  requiredStandardFields: string[];
  status: 'ACTIVE' | 'INACTIVE';
  version: number;
  /** Only in the single-type response; the list leaves the fields out. */
  fields: AdminField[] | null;
}

interface FieldInput {
  key: string;
  labelEn: string;
  labelAr?: string | null;
  dataType: FieldDataType;
  mandatory?: boolean;
  maxLength?: number;
  lovCode?: string;
  searchable?: boolean;
}

interface TypeForm {
  cabinetId: string;
  code: string;
  nameEn: string;
  nameAr?: string | null;
  category: string;
  defaultClassification: Classification;
  processKey?: string;
  channels: string[];
  metaChangeNeedsApproval?: boolean;
  deleteRequiresApproval?: boolean;
  declareRecordOnApproval?: boolean;
  requiredStandardFields?: string[];
  fields?: FieldInput[];
}

const DATA_TYPES: FieldDataType[] = ['TEXT', 'NUMBER', 'DATE', 'BOOLEAN', 'LOV'];
const PROCESSES = ['single-approval', 'multi-stage-approval', 'committee-approval'];
const STANDARD = ['cif', 'customer_name', 'account_no', 'collateral_code', 'contract_ref', 'transaction_ref',
  'application_ref', 'expiry_date'];
/** Types the end-to-end tests create; hidden from the list by default. */
const TEST_PREFIX = 'E2E_';

function useAdminTypes() {
  return useQuery({ queryKey: ['admin', 'document-types'], queryFn: () => api<Page<AdminType>>('/admin/document-types?size=200') });
}

/** One type with its fields. */
function useAdminType(id: string | undefined) {
  return useQuery({
    queryKey: ['admin', 'document-types', id],
    queryFn: () => api<AdminType>(`/admin/document-types/${id}`),
    enabled: !!id,
  });
}

function ordered(fields: AdminField[] | null | undefined): AdminField[] {
  return [...(fields ?? [])].sort((a, b) => (a.displayRow ?? 0) - (b.displayRow ?? 0) || (a.displayOrder ?? 0) - (b.displayOrder ?? 0));
}

/** The list of document types beside the selected one (UI handoff §6.8); /admin/types/:code selects one. */
export function DocumentTypeList() {
  const { t } = useTranslation();
  const label = useLocalizedLabel();
  const navigate = useNavigate();
  const { code } = useParams();
  const cabinets = useCabinets();
  const types = useAdminTypes();
  const [filter, setFilter] = useState('');
  const [cabinetId, setCabinetId] = useState<string>();
  const [hideTests, setHideTests] = useState(true);
  const all = types.data?.items ?? [];
  const tests = all.filter((type) => type.code.startsWith(TEST_PREFIX)).length;
  const needle = filter.trim().toLowerCase();
  const visible = all.filter((type) => (!hideTests || !type.code.startsWith(TEST_PREFIX))
    && (!cabinetId || type.cabinetId === cabinetId)
    && (!needle || [type.code, type.nameEn, type.nameAr ?? ''].some((value) => value.toLowerCase().includes(needle))));
  const selected = all.find((type) => type.code === code) ?? visible[0];
  const cabinetName = (id: string) => cabinets.data?.find((cabinet) => cabinet.id === id)?.name ?? '';

  return (
    <div className="jdms-master-detail">
      <Card className="jdms-table-card jdms-master">
        <div className="jdms-toolbar">
          <Input allowClear prefix={<SearchOutlined aria-hidden  />} placeholder={t('admin.filterTypes')} aria-label={t('admin.filterTypes')}
            value={filter} onChange={(event) => setFilter(event.target.value)} />
          <Select allowClear placeholder={t('admin.allCabinets')} aria-label={t('admin.filterCabinet')} value={cabinetId}
            onChange={setCabinetId} options={(cabinets.data ?? []).map((cabinet) => ({ value: cabinet.id, label: cabinet.name }))} />
          {tests > 0 ? (
            <Checkbox checked={hideTests} onChange={(event) => setHideTests(event.target.checked)}>
              {t('admin.hideTests', { count: tests })}
            </Checkbox>
          ) : null}
        </div>
        <ErrorAlert error={types.error} />
        <Table<AdminType>
          rowKey="id"
          loading={types.isLoading}
          dataSource={visible}
          pagination={false}
          scroll={{ x: 'max-content' }}
          rowClassName={(type) => (type.id === selected?.id ? 'row-selected' : '')}
          onRow={(type) => ({ onClick: () => void navigate(`/admin/types/${type.code}`) })}
          columns={[
            {
              title: t('admin.type'),
              dataIndex: 'code',
              render: (typeCode: string, type) => (
                <span className="jdms-title-cell">
                  <a href={`/admin/types/${typeCode}`} onClick={(event) => { event.preventDefault(); void navigate(`/admin/types/${typeCode}`); }}>
                    <strong>{label(type)}</strong>
                  </a>
                  <span className="jdms-code">{typeCode}</span>
                </span>
              ),
            },
            { title: t('browse.cabinet'), dataIndex: 'cabinetId', render: cabinetName },
            {
              title: t('document.classification'),
              dataIndex: 'defaultClassification',
              render: (value: Classification) => <ClassificationLabel classification={value} />,
            },
            {
              title: t('admin.process'),
              dataIndex: 'processKey',
              render: (value: string | null) => (value ? t(`admin.processes.${value}`, { defaultValue: value }) : t('admin.noApproval')),
            },
            // TODO(backend-gap: G8): a "Pending" tag when a proposal for the type is open
            {
              title: t('records.status'),
              dataIndex: 'status',
              render: (value: AdminType['status']) => (value === 'ACTIVE' ? <Chip tone="success">{t('admin.status.ACTIVE')}</Chip>
                : <Chip tone="neutral" marker="bar">{t('admin.status.INACTIVE')}</Chip>),
            },
          ]}
        />
      </Card>
      <Card className="jdms-detail">
        {selected ? <TypeDetail type={selected} cabinet={cabinetName(selected.cabinetId)} /> : <EmptyState title={t('admin.selectType')} />}
      </Card>
    </div>
  );
}

function TypeDetail({ type, cabinet }: { type: AdminType; cabinet: string }) {
  const { t } = useTranslation();
  const detail = useAdminType(type.id);
  const onOff = (value: boolean) => (value ? t('admin.on') : t('admin.off'));
  const fields = ordered(detail.data?.fields);
  return (
    <section aria-label={t('admin.typeDetail')} className="jdms-type-detail">
      <div className="jdms-type-head">
        <div>
          <div className="jdms-muted jdms-small">{cabinet} · <span className="jdms-code">{type.code}</span></div>
          <h2 className="jdms-properties-title">{type.nameEn}{type.nameAr ? <> · <bdi lang="ar" dir="rtl">{type.nameAr}</bdi></> : null}</h2>
        </div>
        <LinkButton to={`/admin/types/${type.code}/propose`}>{t('admin.proposeChange')}</LinkButton>
      </div>
      <FactList headingLevel={3} sections={[{
        key: 'rules',
        facts: [
          { key: 'classification', label: t('admin.minimumClassification'), value: <ClassificationLabel classification={type.defaultClassification} /> },
          { key: 'approval', label: t('admin.process'), value: type.processKey ? t(`admin.processes.${type.processKey}`, { defaultValue: type.processKey }) : t('admin.noApproval') },
          { key: 'channels', label: t('admin.channels'), value: type.channels.map((channel) => t(`admin.channel.${channel}`, { defaultValue: channel })).join(t('common.listSeparator')) },
          {
            key: 'standard',
            label: t('admin.requiredStandard'),
            value: type.requiredStandardFields.length > 0 ? type.requiredStandardFields.map((field) => t(`admin.standard.${field}`, { defaultValue: field })).join(t('common.listSeparator')) : t('facts.none'),
          },
        ],
      }]} />
      <h3 className="jdms-facts-title">{t('admin.fields')}</h3>
      <ErrorAlert error={detail.error} />
      {detail.isLoading ? null : fields.length === 0 ? <p className="jdms-muted">{t('admin.noFields')}</p> : (
        <ul className="jdms-field-list">
          {fields.map((field) => (
            <li key={field.key}>
              <span className="jdms-title-cell">
                <span><strong>{field.labelEn}</strong>{field.labelAr ? <> · <bdi lang="ar" dir="rtl">{field.labelAr}</bdi></> : null}</span>
                <span className="jdms-title-sub">{t(`admin.dataTypes.${field.dataType}`)}</span>
              </span>
              <Tag>{field.mandatory ? t('admin.required') : t('admin.optional')}</Tag>
            </li>
          ))}
        </ul>
      )}
      <h3 className="jdms-facts-title">{t('admin.rules')}</h3>
      <dl className="jdms-rule-list">
        <div><dt>{t('admin.metaApproval')}</dt><dd>{onOff(type.metaChangeNeedsApproval)}</dd></div>
        <div><dt>{t('admin.deleteApproval')}</dt><dd>{onOff(type.deleteRequiresApproval)}</dd></div>
        <div><dt>{t('admin.declareRecord')}</dt><dd>{onOff(type.declareRecordOnApproval)}</dd></div>
      </dl>
    </section>
  );
}

/** Arabic labels are optional (ADR-0023): blank is sent as none, and the screens show the English label. */
function arabic(value: string | null | undefined): string | null {
  return value?.trim() ? value.trim() : null;
}

/**
 * The fields as the API takes them. {@code keptArabic}: while Arabic is off its boxes are hidden, so the Arabic
 * labels a field already had are sent back unchanged.
 */
function fieldPayload(fields: FieldInput[] | undefined, keptArabic?: Map<string, string | null>) {
  return (fields ?? []).map((field, index) => ({
    key: field.key, labelEn: field.labelEn,
    labelAr: keptArabic ? keptArabic.get(field.key) ?? null : arabic(field.labelAr), dataType: field.dataType,
    mandatory: field.mandatory ?? false, maxLength: field.dataType === 'LOV' ? null : field.maxLength ?? null,
    defaultValue: null, lovCode: field.dataType === 'LOV' ? field.lovCode ?? null : null,
    searchable: field.searchable ?? false, facet: false, sensitive: false, displayOrder: index + 1, displayRow: index + 1,
  }));
}

/**
 * A new document type, or a proposal to change one, on its own page (UI handoff §6.8): basics, rules and fields.
 * Nothing changes until a second administrator approves; a change to the fields is a proposal of its own.
 */
export function TypeEditor() {
  const { t } = useTranslation();
  const label = useLocalizedLabel();
  const navigate = useNavigate();
  const { code } = useParams();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const cabinets = useCabinets();
  const types = useAdminTypes();
  const arabicOn = useArabicEnabled();
  const [form] = Form.useForm<TypeForm>();
  const lovs = useQuery({ queryKey: ['admin', 'lovs'], queryFn: () => api<Page<{ code: string; name: string }>>('/admin/lovs?size=200') });
  const listed = code ? types.data?.items.find((type) => type.code === code) : undefined;
  const full = useAdminType(listed?.id);
  const existing = full.data;
  const title = existing ? t('admin.proposeFor', { type: label(existing) }) : t('admin.newType');

  const propose = useMutation({
    mutationFn: async (values: TypeForm) => {
      // with Arabic off, the Arabic names a type already had are kept as they are
      const keptArabic = arabicOn ? undefined
        : new Map((existing?.fields ?? []).map((field) => [field.key, field.labelAr]));
      const nameAr = arabicOn ? arabic(values.nameAr) : existing?.nameAr ?? null;
      const rules = {
        processKey: values.processKey ?? null,
        metaChangeNeedsApproval: values.metaChangeNeedsApproval ?? false,
        deleteRequiresApproval: values.deleteRequiresApproval ?? false,
        declareRecordOnApproval: values.declareRecordOnApproval ?? false,
        requiredStandardFields: values.requiredStandardFields ?? [],
      };
      if (!existing) {
        return api<unknown>('/admin/document-types', {
          method: 'POST', idempotent: true, body: { ...values, nameAr, ...rules, fields: fieldPayload(values.fields, keptArabic) },
        });
      }
      const { fields, ...attributes } = values;
      await api<unknown>(`/admin/document-types/${existing.id}`, {
        method: 'PUT', idempotent: true, headers: ifMatch(existing.version),
        body: { ...attributes, nameAr, ...rules, cabinetId: existing.cabinetId, code: existing.code, status: existing.status, fields: null },
      });
      const proposed = fieldPayload(fields, keptArabic);
      const current = fieldPayload(ordered(existing.fields).map((field) => ({ ...field, maxLength: field.maxLength ?? undefined, lovCode: field.lovCode ?? undefined })), keptArabic);
      if (JSON.stringify(proposed) !== JSON.stringify(current)) {
        await api<unknown>(`/admin/document-types/${existing.id}/fields`, {
          method: 'PUT', idempotent: true, headers: ifMatch(existing.version), body: proposed,
        });
      }
      return undefined;
    },
    onSuccess: () => {
      void message.success(t('admin.proposed'));
      void queryClient.invalidateQueries({ queryKey: ['pending-changes'] });
      void navigate(existing ? `/admin/types/${existing.code}` : '/admin/types');
    },
  });

  if (code && !existing) {
    const loading = types.isLoading || full.isLoading;
    return (
      <section className="page">
        <PageHeader title={t('admin.documentTypes')} />
        <ErrorAlert error={types.error ?? full.error} />
        {loading ? null : <EmptyState title={t('admin.typeNotFound')} />}
      </section>
    );
  }
  const initialValues: Partial<TypeForm> = existing ? {
    ...existing,
    processKey: existing.processKey ?? undefined,
    fields: ordered(existing.fields).map((field) => ({ ...field, maxLength: field.maxLength ?? undefined, lovCode: field.lovCode ?? undefined })),
  } : { channels: ['UI', 'INTEGRATION'], defaultClassification: 'INTERNAL' };
  const required = (field: string) => [{ required: true, message: t('upload.required', { field }) }];

  return (
    <section className="page">
      <PageHeader
        breadcrumb={[
          { key: 'types', title: <a href="/admin/types" onClick={(event) => { event.preventDefault(); void navigate('/admin/types'); }}>{t('admin.documentTypes')}</a> },
          { key: 'this', title },
        ]}
        title={title}
        description={t('admin.proposeNote')}
      />
      <section aria-label={title} className="jdms-editor">
        <Form<TypeForm> form={form} layout="vertical" onFinish={(values) => propose.mutate(values)} initialValues={initialValues} scrollToFirstError>
          <Card className="jdms-step" title={<h2 className="jdms-card-title">{t('admin.basics')}</h2>}>
            <Row gutter={16}>
              <Col xs={24} md={8}>
                <Form.Item name="cabinetId" label={t('browse.cabinet')} rules={required(t('browse.cabinet'))}>
                  <Select aria-label={t('browse.cabinet')} disabled={!!existing}
                    options={(cabinets.data ?? []).map((cabinet) => ({ value: cabinet.id, label: cabinet.name }))} />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="code" label={t('records.code')}
                  rules={[...required(t('records.code')), { pattern: /^[A-Z][A-Z0-9_]{1,49}$/, message: t('admin.codeFormat') }]}>
                  <Input maxLength={50} disabled={!!existing} />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="category" label={t('admin.category')} rules={required(t('admin.category'))}><Input maxLength={50} /></Form.Item>
              </Col>
              <Col xs={24} md={arabicOn ? 12 : 24}>
                <Form.Item name="nameEn" label={arabicOn ? t('admin.nameEn') : t('admin.name')} rules={required(arabicOn ? t('admin.nameEn') : t('admin.name'))}><Input maxLength={200} /></Form.Item>
              </Col>
              {arabicOn ? (
                <Col xs={24} md={12}>
                  <Form.Item name="nameAr" label={t('admin.nameAr')} extra={t('admin.arabicOptional')}><Input maxLength={200} dir="rtl" lang="ar" /></Form.Item>
                </Col>
              ) : null}
            </Row>
          </Card>

          <Card className="jdms-step" title={<h2 className="jdms-card-title">{t('admin.rules')}</h2>}>
            <Row gutter={16}>
              <Col xs={24} md={8}>
                <Form.Item name="defaultClassification" label={t('admin.minimumClassification')} rules={required(t('admin.minimumClassification'))}>
                  <Select aria-label={t('admin.minimumClassification')}
                    options={CLASSIFICATIONS.map((value) => ({ value, label: <ClassificationLabel classification={value} /> }))} />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="processKey" label={t('admin.process')}>
                  <Select allowClear aria-label={t('admin.process')} placeholder={t('admin.noApproval')}
                    options={PROCESSES.map((value) => ({ value, label: t(`admin.processes.${value}`) }))} />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="channels" label={t('admin.channels')} rules={required(t('admin.channels'))}>
                  <CheckboxGroupField label={t('admin.channels')} options={[{ value: 'UI', label: t('admin.channel.UI') }, { value: 'INTEGRATION', label: t('admin.channel.INTEGRATION') }]} />
                </Form.Item>
              </Col>
              <Col xs={24}>
                <Form.Item name="requiredStandardFields" label={t('admin.requiredStandard')}>
                  <Select mode="multiple" aria-label={t('admin.requiredStandard')}
                    options={STANDARD.map((value) => ({ value, label: t(`admin.standard.${value}`, { defaultValue: value }) }))} />
                </Form.Item>
              </Col>
              <Col xs={24}>
                {/* switches are fine here: nothing changes until the proposal is approved */}
                <div className="jdms-switches">
                  <Form.Item name="metaChangeNeedsApproval" valuePropName="checked" label={t('admin.metaApproval')}><Switch /></Form.Item>
                  <Form.Item name="deleteRequiresApproval" valuePropName="checked" label={t('admin.deleteApproval')}><Switch /></Form.Item>
                  <Form.Item name="declareRecordOnApproval" valuePropName="checked" label={t('admin.declareRecord')}><Switch /></Form.Item>
                </div>
              </Col>
            </Row>
          </Card>

          <Card className="jdms-step" title={<h2 className="jdms-card-title">{t('admin.fields')}</h2>}>
            <Form.List name="fields">
              {(fields, { add, remove }) => (
                <>
                  {fields.map((field, index) => (
                    <Row gutter={8} key={field.key} className="field-row" aria-label={t('admin.fieldNumber', { number: index + 1 })} role="group">
                      <Col xs={12} md={4}>
                        <Form.Item name={[field.name, 'key']} label={t('admin.fieldKey')}
                          rules={[{ required: true, message: t('upload.required', { field: t('admin.fieldKey') }) }, { pattern: /^[a-z][a-z0-9_]{0,49}$/, message: t('admin.keyFormat') }]}>
                          <Input />
                        </Form.Item>
                      </Col>
                      <Col xs={12} md={arabicOn ? 5 : 10}>
                        <Form.Item name={[field.name, 'labelEn']} label={arabicOn ? t('admin.labelEn') : t('admin.label')} rules={required(arabicOn ? t('admin.labelEn') : t('admin.label'))}><Input /></Form.Item>
                      </Col>
                      {arabicOn ? (
                        <Col xs={12} md={5}>
                          <Form.Item name={[field.name, 'labelAr']} label={t('admin.labelAr')}><Input dir="rtl" lang="ar" /></Form.Item>
                        </Col>
                      ) : null}
                      <Col xs={12} md={4}>
                        <Form.Item name={[field.name, 'dataType']} label={t('admin.dataType')} rules={required(t('admin.dataType'))}>
                          <Select aria-label={t('admin.dataType')} options={DATA_TYPES.map((value) => ({ value, label: t(`admin.dataTypes.${value}`) }))} />
                        </Form.Item>
                      </Col>
                      <Col xs={12} md={3}>
                        <Form.Item noStyle shouldUpdate>
                          {() => form.getFieldValue(['fields', field.name, 'dataType']) === 'LOV' ? (
                            <Form.Item name={[field.name, 'lovCode']} label={t('admin.lov')} rules={required(t('admin.lov'))}>
                              <Select aria-label={t('admin.lov')} options={(lovs.data?.items ?? []).map((lov) => ({ value: lov.code, label: lov.name }))} />
                            </Form.Item>
                          ) : (
                            <Form.Item name={[field.name, 'maxLength']} label={t('admin.maxLength')}>
                              <InputNumber min={1} max={4000} className="full-width" />
                            </Form.Item>
                          )}
                        </Form.Item>
                      </Col>
                      <Col xs={8} md={2}>
                        <Form.Item name={[field.name, 'mandatory']} valuePropName="checked" label={t('admin.mandatory')}><Switch /></Form.Item>
                      </Col>
                      <Col xs={4} md={1} className="jdms-field-remove">
                        <Button danger type="text" icon={<DeleteOutlined aria-hidden  />} aria-label={t('admin.removeFieldNumber', { number: index + 1 })}
                          onClick={() => remove(field.name)} />
                      </Col>
                    </Row>
                  ))}
                  <Button icon={<PlusOutlined aria-hidden  />} onClick={() => add({ dataType: 'TEXT' })}>{t('admin.addField')}</Button>
                </>
              )}
            </Form.List>
          </Card>
          <ErrorAlert error={propose.error} />
          <Space wrap className="jdms-editor-actions">
            <Button type="primary" htmlType="submit" loading={propose.isPending}>{t('admin.propose')}</Button>
            <Button onClick={() => void navigate(existing ? `/admin/types/${existing.code}` : '/admin/types')}>{t('common.cancel')}</Button>
          </Space>
        </Form>
      </section>
    </section>
  );
}
