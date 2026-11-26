import { LockFilled, LockOutlined, UnlockOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { Classification } from '../api/types';
import { isAboveMinimum } from './classification';

const ICON = {
  PUBLIC: UnlockOutlined,
  INTERNAL: LockOutlined,
  CONFIDENTIAL: LockOutlined,
  RESTRICTED: LockFilled,
} as const;

/**
 * Classification that gets heavier as the level rises, with an icon so it is never colour alone (UI handoff §4.4).
 * In lists pass {@code minimum} (the type's default classification): the label is shown only above it, and not at
 * all while the minimum is unknown.
 */
export function ClassificationLabel({ classification, minimum, inList = false }: {
  classification: Classification;
  minimum?: Classification;
  inList?: boolean;
}) {
  const { t } = useTranslation();
  if (inList && !isAboveMinimum(classification, minimum)) {
    return null;
  }
  const Icon = ICON[classification];
  return (
    <span className={`jdms-classification jdms-classification-${classification}`}>
      <Icon aria-hidden="true" />
      {t(`classification.${classification}`)}
    </span>
  );
}
