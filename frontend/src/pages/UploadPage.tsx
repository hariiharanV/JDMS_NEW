import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { CheckOutlined, FileOutlined, FileSearchOutlined, InboxOutlined } from '@ant-design/icons';
import { Alert, App, Button, Card, Col, Collapse, DatePicker, Form, Input, Row, Select, Spin, TreeSelect,
  Upload } from 'antd';
import dayjs from 'dayjs';
import type { UploadFile } from 'antd';
import { useMutation } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useNavigate, useSearchParams } from 'react-router';
import { useCapture, type CaptureState } from '../api/capture';
import { api, ApiError } from '../api/client';
import { useCabinets, useDocumentTypes, useFolders, useMe } from '../api/queries';
import { CLASSIFICATIONS, STANDARD_FIELD_KEYS, STANDARD_FIELDS, type CatalogField, type Classification,
  type DocumentSummary, type FieldSuggestion, type StandardField } from '../api/types';
import { ClassificationLabel } from '../components/ClassificationLabel';
import { DynamicFields } from '../components/DynamicFields';
import { customFieldValues, DATE_FORMATS, type DateValue } from '../components/fieldValues';
import { ErrorAlert } from '../components/ErrorAlert';
import { PageHeader } from '../components/PageHeader';
import { useFormatters, useLocalizedLabel } from '../i18n/labels';

interface UploadForm extends Partial<Record<StandardField, unknown>> {
  cabinetId: string;
  documentTypeCode: string;
  title?: string;
  classification: Classification;
  branchCode: string;
  folderId?: string;
  custom?: Record<string, unknown>;
}

interface UploadResponse extends DocumentSummary {
  warnings?: { code: string; count?: number }[];
}

/** The customer comes first; the other references are optional unless the type requires them. */
const CUSTOMER: StandardField[] = ['cif', 'customerName'];
const REFERENCES: StandardField[] = ['accountNo', 'collateralCode', 'contractRef', 'transactionRef', 'applicationRef'];

const filled = (value: unknown) => value !== undefined && value !== null && value !== '';

/** An API field name (`cif`, `customFields.passport_no`) as a form field path. */
function formPath(field: string): (string | number)[] {
  return field.startsWith('customFields.') ? ['custom', field.slice('customFields.'.length)] : [field];
}

/**
 * A suggested value as the form holds it (dates as date picker values, yes/no as booleans); undefined when it does
 * not fit the field (unknown field, list value not in the list, text too long).
 */
function formValue(suggestion: FieldSuggestion, fields: CatalogField[]): unknown {
  if (!suggestion.field.startsWith('customFields.')) {
    if (!(STANDARD_FIELDS as readonly string[]).includes(suggestion.field)) return undefined;
    return suggestion.field === 'expiryDate' ? dayjs(suggestion.value, 'YYYY-MM-DD') : suggestion.value;
  }
  const field = fields.find((candidate) => candidate.key === suggestion.field.slice('customFields.'.length));
  switch (field?.dataType) {
    case undefined:
      return undefined;
    case 'DATE':
      return dayjs(suggestion.value, 'YYYY-MM-DD');
    case 'BOOLEAN':
      return suggestion.value === 'true';
    case 'LOV':
      return field.options.some((option) => option.code === suggestion.value) ? suggestion.value : undefined;
    case 'TEXT':
      return field.maxLength && suggestion.value.length > field.maxLength ? undefined : suggestion.value;
    default:
      return suggestion.value;
  }
}

/**
 * Upload (spec §4.2, UI handoff §6.4): numbered sections that adapt to the chosen document type, beside a checklist
 * of what is still missing. Opened from Browse with ?cabinet=&folder=. Once the file and the type are chosen, the
 * file is read in the background and the fields the user has not touched are filled in from it (B-15, ADR-0024);
 * filled fields say so until the user changes them.
 */
export function UploadPage() {
  const { t } = useTranslation();
  const label = useLocalizedLabel();
  const format = useFormatters();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const { message } = App.useApp();
  const [form] = Form.useForm<UploadForm>();
  const [files, setFiles] = useState<UploadFile[]>([]);
  const [titleEdited, setTitleEdited] = useState(false);
  const me = useMe();
  const cabinets = useCabinets();
  const types = useDocumentTypes();
  const values = Form.useWatch([], form) as Partial<UploadForm> | undefined;
  const cabinetId = Form.useWatch('cabinetId', form);
  const typeCode = Form.useWatch('documentTypeCode', form);
  const folders = useFolders(cabinetId);
  const type = types.data?.find((candidate) => candidate.code === typeCode);
  const required = useMemo(() => new Set((type?.requiredStandardFields ?? [])
    .map((key) => STANDARD_FIELD_KEYS[key]).filter((field): field is StandardField => !!field)), [type]);
  const file = files[0];
  const branchCode = Form.useWatch('branchCode', form) as string | undefined;
  const folderId = Form.useWatch('folderId', form) as string | undefined;
  const classification = Form.useWatch('classification', form) as Classification | undefined;
  const original = file?.originFileObj;
  const captureState = useCapture(original && type && branchCode ? {
    file: original, fileKey: file.uid, documentTypeCode: type.code, branchCode, folderId, classification,
  } : null);
  /** Fields filled in from the document (form path joined with dots), until the user changes them. */
  const [suggested, setSuggested] = useState<Record<string, FieldSuggestion>>({});
  const applied = useRef<string | null>(null);

  // a cabinet (and folder) chosen in Browse
  const [initial] = useState(() => ({ cabinet: params.get('cabinet'), folder: params.get('folder') ?? undefined }));
  const presetCabinet = cabinets.data?.find((cabinet) => cabinet.code === initial.cabinet)?.id;
  useEffect(() => {
    if (presetCabinet && form.getFieldValue('cabinetId') === undefined) {
      form.setFieldsValue({ cabinetId: presetCabinet, folderId: initial.folder });
    }
  }, [presetCabinet, initial.folder, form]);

  const upload = useMutation({
    mutationFn: async (submitted: UploadForm) => {
      const original = file?.originFileObj;
      if (!original || !type) {
        throw new Error('no file');
      }
      const metadata: Record<string, unknown> = {
        documentTypeCode: type.code,
        title: submitted.title,
        classification: submitted.classification,
        branchCode: submitted.branchCode,
        folderId: submitted.folderId,
        customFields: customFieldValues(type.fields, submitted.custom ?? {}),
      };
      for (const field of STANDARD_FIELDS) {
        const value = submitted[field];
        if (value) {
          metadata[field] = field === 'expiryDate' ? (value as DateValue).format('YYYY-MM-DD') : value;
        }
      }
      const body = new FormData();
      // the metadata part must come before the file part (streamed upload, ADR-0011)
      body.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }));
      body.append('file', original, original.name);
      return api<UploadResponse>('/documents', { method: 'POST', form: body, idempotent: true });
    },
    onSuccess: (document) => {
      if (document.warnings?.some((warning) => warning.code === 'DUPLICATE_CONTENT')) {
        void message.warning(t('upload.duplicate'));
      }
      void message.success(t('upload.done'));
      void navigate(`/documents/${document.id}`);
    },
    onError: (error) => {
      // the server's field violations appear on the fields themselves
      if (error instanceof ApiError && error.errors.length > 0) {
        form.setFields(error.errors.map((violation) => ({
          name: formPath(violation.field) as never,
          errors: [t(`violations.${violation.code}`, { defaultValue: violation.message })],
        })));
      }
    },
  });

  const folderTree = useMemo(() => {
    const all = folders.data ?? [];
    interface Node { value: string; title: string; children: Node[] }
    const build = (parentId: string | null): Node[] =>
      all.filter((folder) => folder.parentId === parentId)
        .map((folder) => ({ value: folder.id, title: folder.name, children: build(folder.id) }));
    return build(null);
  }, [folders.data]);

  // the rail's checklist counts the required fields that are filled
  const requiredPaths: { path: (string | number)[]; key: string }[] = [
    { path: ['cabinetId'], key: 'cabinetId' },
    { path: ['documentTypeCode'], key: 'documentTypeCode' },
    ...(type ? [
      ...[...required].map((field) => ({ path: [field], key: field })),
      ...type.fields.filter((field) => field.mandatory).map((field) => ({ path: ['custom', field.key], key: `custom.${field.key}` })),
      { path: ['branchCode'], key: 'branchCode' },
      { path: ['classification'], key: 'classification' },
    ] : []),
  ];
  const valueAt = (path: (string | number)[]) =>
    path.reduce<unknown>((node, key) => (node as Record<string, unknown> | undefined)?.[key], values);
  const requiredFilled = requiredPaths.filter(({ path }) => filled(valueAt(path))).length;

  const defaultTitle = (code: string | undefined, customer: unknown) => {
    const chosen = types.data?.find((candidate) => candidate.code === code);
    return chosen ? [label(chosen), typeof customer === 'string' ? customer.trim() : ''].filter(Boolean).join(' ') : undefined;
  };
  // fill in what the document says, once per capture, leaving alone whatever the user already touched
  useEffect(() => {
    if (captureState.phase !== 'done' || !type || applied.current === captureState.capture.id
      || captureState.capture.documentTypeCode !== type.code) {
      return;
    }
    applied.current = captureState.capture.id;
    const marks: Record<string, FieldSuggestion> = {};
    const updates: { name: (string | number)[]; value: unknown; errors: string[] }[] = [];
    for (const suggestion of captureState.capture.suggestions) {
      const path = formPath(suggestion.field);
      const value = formValue(suggestion, type.fields);
      if (value === undefined || form.isFieldTouched(path as never)) continue;
      updates.push({ name: path, value, errors: [] });
      marks[path.join('.')] = suggestion;
    }
    form.setFields(updates as never);
    setSuggested(marks);
    if (!titleEdited && marks.customerName) {
      form.setFieldsValue({ title: defaultTitle(type.code, marks.customerName.value) });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [captureState, type]);

  const hint = (path: string) => {
    const suggestion = suggested[path];
    return suggestion ? <SuggestedHint suggestion={suggestion} /> : undefined;
  };

  const rule = (field: string) => [{ required: true, message: t('upload.required', { field }) }];
  const standard = (field: StandardField) => (
    <Col xs={24} md={12} xl={8} key={field}>
      <Form.Item name={field} label={t(`document.${field}`)} rules={required.has(field) ? rule(t(`document.${field}`)) : []}
        extra={hint(field)} className={suggested[field] ? 'jdms-field-suggested' : undefined}>
        {field === 'expiryDate' ? <DatePicker className="full-width" format={DATE_FORMATS} /> : <Input maxLength={100} />}
      </Form.Item>
    </Col>
  );
  const references = REFERENCES.filter((field) => !required.has(field));

  return (
    <section className="page">
      <PageHeader title={t('upload.title')} description={t('upload.intro')} />
      <Form<UploadForm>
        form={form}
        layout="vertical"
        className="jdms-upload"
        scrollToFirstError
        onFinish={(submitted) => upload.mutate(submitted)}
        onValuesChange={(changed: Partial<UploadForm>, all: Partial<UploadForm>) => {
          // a filled-in field the user changes is theirs now
          const changedPaths = Object.entries(changed).flatMap(([key, value]) => key === 'custom'
            && value && typeof value === 'object' ? Object.keys(value).map((field) => `custom.${field}`) : [key]);
          if (changedPaths.some((path) => suggested[path])) {
            setSuggested((marks) => Object.fromEntries(Object.entries(marks)
              .filter(([path]) => !changedPaths.includes(path))));
          }
          if (changed.documentTypeCode) {
            setSuggested({});
          }
          if (changed.cabinetId) {
            form.setFieldsValue({ documentTypeCode: undefined, folderId: undefined });
          }
          if (changed.documentTypeCode) {
            const chosen = types.data?.find((candidate) => candidate.code === changed.documentTypeCode);
            form.setFieldsValue({ classification: chosen?.defaultClassification, custom: {},
              branchCode: form.getFieldValue('branchCode') ?? me.data?.branchCodes[0] });
          }
          if ('title' in changed) {
            setTitleEdited(true);
          } else if (!titleEdited && (changed.documentTypeCode || 'customerName' in changed)) {
            form.setFieldsValue({ title: defaultTitle(all.documentTypeCode, all.customerName) });
          }
        }}
      >
        <div className="jdms-upload-form">
          <Step number={1} title={t('upload.steps.file')}>
            {file ? (
              <div className="jdms-file-row">
                <FileOutlined aria-hidden className="jdms-file-icon" />
                <div className="jdms-file-text">
                  <bdi className="jdms-file-name">{file.name}</bdi>
                  <span className="jdms-muted">{[file.size !== undefined ? format.bytes(file.size) : null, t('upload.ready')].filter(Boolean).join(' · ')}</span>
                </div>
                <Upload showUploadList={false} maxCount={1} beforeUpload={() => false}
                  onChange={({ fileList }) => { setFiles(fileList.slice(-1)); setSuggested({}); }}>
                  <Button>{t('upload.replace')}</Button>
                </Upload>
              </div>
            ) : (
              <Upload.Dragger fileList={files} maxCount={1} beforeUpload={() => false} showUploadList={false}
                onChange={({ fileList }) => { setFiles(fileList.slice(-1)); setSuggested({}); }}>
                <p className="ant-upload-drag-icon"><InboxOutlined aria-hidden  /></p>
                <p>{t('upload.dropHint')}</p>
              </Upload.Dragger>
            )}
            {file ? <AssistStatus state={captureState} typeChosen={!!type} filled={Object.keys(suggested).length} />
              : null}
          </Step>

          <Step number={2} title={t('upload.steps.what')}>
            <Row gutter={16}>
              <Col xs={24} md={12}>
                <Form.Item name="cabinetId" label={t('browse.cabinet')} rules={rule(t('browse.cabinet'))}>
                  <Select showSearch={{ optionFilterProp: 'label' }} loading={cabinets.isLoading} aria-label={t('browse.cabinet')}
                    options={(cabinets.data ?? []).map((cabinet) => ({ value: cabinet.id, label: cabinet.name }))} />
                </Form.Item>
              </Col>
              <Col xs={24} md={12}>
                <Form.Item name="documentTypeCode" label={t('document.type')} rules={rule(t('document.type'))}>
                  <Select showSearch={{ optionFilterProp: 'label' }} aria-label={t('document.type')} disabled={!cabinetId}
                    options={(types.data ?? [])
                      .filter((candidate) => candidate.cabinetId === cabinetId && candidate.channels.includes('UI'))
                      .map((candidate) => ({ value: candidate.code, label: label(candidate) }))} />
                </Form.Item>
              </Col>
            </Row>
          </Step>

          {type ? (
            <>
              <Step number={3} title={t('upload.steps.customer', { type: label(type) })}>
                <Row gutter={16}>
                  {[...CUSTOMER, ...STANDARD_FIELDS.filter((field) => !CUSTOMER.includes(field)
                    && (required.has(field) || field === 'expiryDate'))].map(standard)}
                </Row>
                {type.fields.length > 0 ? (
                  <DynamicFields fields={type.fields} namePrefix="custom" extra={(key) => hint(`custom.${key}`)} />
                ) : null}
              </Step>

              <Step number={4} title={t('upload.steps.filing')}>
                <Row gutter={16}>
                  <Col xs={24} md={12} xl={8}>
                    <Form.Item name="title" label={t('document.title')}>
                      <Input maxLength={255} />
                    </Form.Item>
                  </Col>
                  <Col xs={24} md={12} xl={8}>
                    <Form.Item name="branchCode" label={t('document.branch')} rules={rule(t('document.branch'))}>
                      {me.data?.allBranches ? <Input /> : (
                        <Select aria-label={t('document.branch')}
                          options={(me.data?.branchCodes ?? []).map((code) => ({ value: code, label: code }))} />
                      )}
                    </Form.Item>
                  </Col>
                  <Col xs={24} md={12} xl={8}>
                    <Form.Item name="classification" label={t('document.classification')} rules={rule(t('document.classification'))}
                      extra={t('upload.classificationHint')}>
                      <Select aria-label={t('document.classification')}
                        options={CLASSIFICATIONS.filter((value) => CLASSIFICATIONS.indexOf(value)
                          >= CLASSIFICATIONS.indexOf(type.defaultClassification))
                          .map((value) => ({ value, label: <ClassificationLabel classification={value} /> }))} />
                    </Form.Item>
                  </Col>
                  <Col xs={24} md={12} xl={8}>
                    <Form.Item name="folderId" label={t('document.folder')}>
                      <TreeSelect allowClear treeDefaultExpandAll treeData={folderTree} aria-label={t('document.folder')} />
                    </Form.Item>
                  </Col>
                </Row>
                {references.length > 0 ? (
                  <Collapse ghost className="jdms-more" items={[{
                    key: 'references',
                    label: t('upload.moreReferences'),
                    forceRender: true,
                    children: <Row gutter={16}>{references.map(standard)}</Row>,
                  }]} />
                ) : null}
              </Step>
            </>
          ) : null}
        </div>

        <aside className="jdms-upload-rail" aria-label={t('upload.beforeSubmit')}>
          <Card>
            <h2 className="jdms-card-title">{t('upload.beforeSubmit')}</h2>
            <ul className="jdms-checklist">
              <Check done={!!file}>{file ? t('upload.check.fileAttached') : t('upload.check.fileMissing')}</Check>
              <Check done={requiredFilled === requiredPaths.length}>
                {requiredFilled === requiredPaths.length && type
                  ? t('upload.check.allFilled', { count: requiredPaths.length })
                  : t('upload.check.filled', { filled: requiredFilled, count: requiredPaths.length })}
              </Check>
            </ul>
            <div className="jdms-rail-section">
              <h3 className="jdms-facts-title">{t('upload.route')}</h3>
              {/* TODO(backend-gap: G19): the type's approval route is not in the catalogue, so it is described in general */}
              <p className="jdms-muted">{t('upload.routeText')}</p>
            </div>
            <ErrorAlert error={upload.error} />
            {/* TODO(backend-gap: G5): Save as draft */}
            <Button type="primary" size="large" block htmlType="submit" loading={upload.isPending} disabled={!file}>
              {t('upload.submit')}
            </Button>
          </Card>
        </aside>
      </Form>
    </section>
  );
}

function Step({ number, title, children }: { number: number; title: ReactNode; children: ReactNode }) {
  return (
    <Card className="jdms-step">
      <h2 className="jdms-step-title"><span className="jdms-step-number" aria-hidden="true">{number}</span>{title}</h2>
      {children}
    </Card>
  );
}

function Check({ done, children }: { done: boolean; children: ReactNode }) {
  return (
    <li className={done ? 'jdms-check-done' : 'jdms-check-open'}>
      {done ? <CheckOutlined aria-hidden="true" /> : <span className="jdms-check-ring" aria-hidden="true" />}
      <span>{children}</span>
    </li>
  );
}

/** What reading the attached file came to (B-15): announced politely, never in the way of the upload. */
function AssistStatus({ state, typeChosen, filled }: { state: CaptureState; typeChosen: boolean; filled: number }) {
  const { t } = useTranslation();
  let content: ReactNode;
  if (!typeChosen) {
    content = <p className="jdms-muted">{t('upload.assist.chooseType')}</p>;
  } else if (state.phase === 'starting' || state.phase === 'reading') {
    content = <div className="jdms-muted jdms-assist-line"><Spin size="small" /> <span>{t('upload.assist.reading')}</span></div>;
  } else if (state.phase === 'done' && state.capture.status === 'INFECTED') {
    content = <Alert type="warning" showIcon title={t('upload.assist.infected')} />;
  } else if (state.phase === 'done' && state.capture.status === 'DONE') {
    content = filled > 0 ? <Alert type="info" showIcon icon={<FileSearchOutlined />}
      title={t('upload.assist.filled', { count: filled })} />
      : <p className="jdms-muted">{t('upload.assist.nothing')}</p>;
  } else if (state.phase === 'idle') {
    content = null;
  } else {
    content = <p className="jdms-muted">{t('upload.assist.unavailable')}</p>;
  }
  return <div className="jdms-assist" role="status" aria-live="polite">{content}</div>;
}

function SuggestedHint({ suggestion }: { suggestion: FieldSuggestion }) {
  const { t } = useTranslation();
  return (
    <span className="jdms-suggested">
      <FileSearchOutlined aria-hidden="true" />{' '}
      {t(suggestion.confidence === 'HIGH' ? 'upload.assist.fromDocument' : 'upload.assist.fromDocumentCheck',
        { label: suggestion.label })}
    </span>
  );
}
