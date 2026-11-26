import { Typography } from 'antd';
import { shortId } from '../i18n/format';

/**
 * A code or identifier as small monospace secondary text, isolated left to right so it reads correctly inside
 * Arabic sentences (UI handoff §3.2). {@code short} shows the first 8 characters; {@code copyable} the full value.
 */
export function Code({ value, short = false, copyable = false }: { value: string; short?: boolean; copyable?: boolean }) {
  if (copyable) {
    return (
      <bdi dir="ltr">
        <Typography.Text className="jdms-code jdms-code-full" copyable={{ text: value }}>{value}</Typography.Text>
      </bdi>
    );
  }
  return <bdi dir="ltr" className="jdms-code" title={short ? value : undefined}>{short ? shortId(value) : value}</bdi>;
}
