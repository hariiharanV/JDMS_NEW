import { Checkbox } from 'antd';
import type { GetProps } from 'antd';

type Props = GetProps<typeof Checkbox.Group> & { 'aria-required'?: unknown; label: string };

/**
 * A checkbox group inside a Form.Item: a named group of checkboxes. Form.Item gives its control aria-required, which
 * ARIA does not allow on a group, so it is dropped here; the field still validates and shows its required mark.
 */
export function CheckboxGroupField({ label, ...props }: Props) {
  const group = { ...props };
  delete group['aria-required'];
  return <Checkbox.Group {...group} role="group" aria-label={label} />;
}
