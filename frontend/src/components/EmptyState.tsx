import type { ReactNode } from 'react';
import { InboxOutlined } from '@ant-design/icons';
import { Empty } from 'antd';

interface Props {
  title: ReactNode;
  /** One or two lines saying what being empty means. */
  description?: ReactNode;
  icon?: ReactNode;
  /** The next thing to do, usually a Button. */
  action?: ReactNode;
}

/** An empty list says what to do next instead of a bare "No data" (UI handoff §4.9). */
export function EmptyState({ title, description, icon = <InboxOutlined aria-hidden  />, action }: Props) {
  return (
    <Empty
      className="jdms-empty"
      image={<span className="jdms-empty-icon" aria-hidden="true">{icon}</span>}
      description={(
        <>
          <span className="jdms-empty-title">{title}</span>
          {description ? <span className="jdms-empty-text">{description}</span> : null}
        </>
      )}
    >
      {action}
    </Empty>
  );
}
