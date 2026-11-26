import type { ReactNode } from 'react';
import { Col, DatePicker, Form, Input, InputNumber, Radio, Row, Select } from 'antd';
import { useTranslation } from 'react-i18next';
import type { CatalogField } from '../api/types';
import { useLocalizedLabel } from '../i18n/labels';
import { DATE_FORMATS } from './fieldValues';

/** Longer text fields count their characters as you type. */
const COUNT_ABOVE = 100;
/** Longer lists can be searched. */
const SEARCH_ABOVE = 7;

/**
 * The custom fields of a document type as form controls, in the type's order, each by its data type (UI handoff
 * §6.4). Values are validated again by the server; the form only helps.
 */
export function DynamicFields({ fields, namePrefix, extra }: {
  fields: CatalogField[];
  namePrefix: string;
  /** A note under a field (e.g. that its value was read from the document). */
  extra?: (key: string) => ReactNode;
}) {
  const { t } = useTranslation();
  const label = useLocalizedLabel();
  const ordered = [...fields]
    .sort((a, b) => a.displayRow - b.displayRow || a.displayOrder - b.displayOrder || a.key.localeCompare(b.key));

  return (
    <Row gutter={16}>
      {ordered.map((field) => (
        <Col xs={24} md={12} xl={8} key={field.key}>
          <Form.Item
            name={[namePrefix, field.key]}
            label={label(field)}
            initialValue={field.dataType === 'BOOLEAN' ? (field.defaultValue === 'true' ? true : field.defaultValue === 'false' ? false : undefined)
              : field.dataType === 'LOV' || field.dataType === 'TEXT' ? field.defaultValue ?? undefined : undefined}
            rules={field.mandatory ? [{ required: true, message: t('upload.required', { field: label(field) }) }] : []}
            extra={extra?.(field.key)}
            className={extra?.(field.key) ? 'jdms-field-suggested' : undefined}
          >
            {control(field, label, t)}
          </Form.Item>
        </Col>
      ))}
    </Row>
  );
}

function control(field: CatalogField, label: ReturnType<typeof useLocalizedLabel>, t: (key: string) => string) {
  switch (field.dataType) {
    case 'NUMBER':
      return <InputNumber className="full-width" stringMode aria-label={label(field)} />;
    case 'DATE':
      return <DatePicker className="full-width" format={DATE_FORMATS} aria-label={label(field)} />;
    case 'BOOLEAN':
      // a choice, not a switch: nothing happens until the form is submitted
      return (
        <Radio.Group aria-label={label(field)} options={[
          { value: true, label: t('common.yes') },
          { value: false, label: t('common.no') },
        ]} />
      );
    case 'LOV':
      return (
        <Select
          aria-label={label(field)}
          showSearch={field.options.length > SEARCH_ABOVE ? { optionFilterProp: 'label' } : false}
          options={field.options.map((option) => ({ value: option.code, label: label(option) }))}
        />
      );
    default:
      return <Input maxLength={field.maxLength ?? undefined} aria-label={label(field)}
        showCount={(field.maxLength ?? 0) > COUNT_ABOVE} autoComplete={field.sensitive ? 'off' : undefined} />;
  }
}
