import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { STANDARD_FIELDS, type CatalogField, type DocumentSummary, type DocumentType } from '../api/types';
import { useFormatters, useLocalizedLabel } from '../i18n/labels';
import { ClassificationLabel } from './ClassificationLabel';
import type { Fact, FactSection } from './FactList';

/** The customer's own fields head the list; the other standard fields belong with the type's. */
const CUSTOMER_FIELDS = ['customerName', 'cif'] as const;

/**
 * A document's metadata as fact sections: Customer, the type's fields (standard ones first, then the type's own in
 * definition order) and Record. Values are formatted by field type; CIF links to a search for that customer.
 */
export function useDocumentFacts() {
  const { t } = useTranslation();
  const format = useFormatters();
  const label = useLocalizedLabel();

  const value = (field: CatalogField | undefined, raw: unknown): ReactNode => {
    if (typeof raw === 'boolean') return t(raw ? 'common.yes' : 'common.no');
    if (field?.dataType === 'DATE' && typeof raw === 'string') return format.date(raw);
    if (field?.dataType === 'NUMBER' && raw !== '' && !Number.isNaN(Number(raw))) return format.number(Number(raw));
    if (field?.dataType === 'LOV') {
      const option = field.options.find((candidate) => candidate.code === raw);
      if (option) return label(option);
    }
    return <bdi>{String(raw)}</bdi>;
  };

  return (document: DocumentSummary, type: DocumentType | undefined): FactSection[] => {
    const standard = (key: (typeof STANDARD_FIELDS)[number]): ReactNode => {
      const raw = document[key];
      if (key === 'cif' && raw) return <Link to={`/search?scope=cif&q=${encodeURIComponent(raw)}`}><bdi>{raw}</bdi></Link>;
      if (key === 'expiryDate' && raw) return format.date(raw);
      return <bdi>{raw}</bdi>;
    };
    const customer: Fact[] = CUSTOMER_FIELDS.filter((key) => document[key])
      .map((key) => ({ key, label: t(`document.${key}`), value: standard(key) }));
    const others: Fact[] = STANDARD_FIELDS
      .filter((key) => !(CUSTOMER_FIELDS as readonly string[]).includes(key) && document[key])
      .map((key) => ({ key, label: t(`document.${key}`), value: standard(key) }));
    const ordered = [...(type?.fields ?? [])].sort((a, b) => a.displayOrder - b.displayOrder);
    const known = new Set(ordered.map((field) => field.key));
    const custom: Fact[] = [
      ...ordered.filter((field) => document.customFields[field.key] !== undefined && document.customFields[field.key] !== null)
        .map((field) => ({ key: field.key, label: label(field), value: value(field, document.customFields[field.key]) })),
      // fields no longer in the type's definition still show, under their key
      ...Object.entries(document.customFields).filter(([key]) => !known.has(key))
        .map(([key, raw]) => ({ key, label: key, value: value(undefined, raw) })),
    ];
    return [
      { key: 'customer', title: t('facts.customer'), facts: customer },
      { key: 'type', title: type ? label(type) : document.documentTypeCode, facts: [...others, ...custom] },
      {
        key: 'record',
        title: t('facts.record'),
        facts: [
          { key: 'branch', label: t('document.branch'), value: <bdi>{document.branchCode}</bdi> }, // TODO(backend-gap: G2)
          { key: 'created', label: t('document.createdAt'), value: format.dateTime(document.createdAt) },
          {
            key: 'classification',
            label: t('document.classification'),
            value: <ClassificationLabel classification={document.classification} />,
          },
          // TODO(backend-gap: G13): the retention policy that applies is not in the document response, so it is left out
          { key: 'hold', label: t('facts.legalHold'), value: document.onLegalHold ? t('facts.onHold') : t('facts.none') },
        ],
      },
    ];
  };
}
