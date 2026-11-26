import type { ReactNode } from 'react';
import { Breadcrumb, Space, Typography } from 'antd';
import type { BreadcrumbProps } from 'antd';

interface Props {
  title: ReactNode;
  /** One line under the title saying what the page is for. */
  description?: ReactNode;
  breadcrumb?: BreadcrumbProps['items'];
  /** At most one primary button. */
  actions?: ReactNode;
  /** Under the title, before the description: status and classification on a detail page. */
  meta?: ReactNode;
}

/** The page title (the only h1), with an optional breadcrumb, description and actions at the inline end. */
export function PageHeader({ title, description, breadcrumb, actions, meta }: Props) {
  return (
    <header className="jdms-page-header">
      {breadcrumb ? <Breadcrumb items={breadcrumb} className="jdms-page-breadcrumb" /> : null}
      <div className="jdms-page-header-row">
        <div className="jdms-page-heading">
          <Typography.Title level={1}>{title}</Typography.Title>
          {meta ? <div className="jdms-page-meta">{meta}</div> : null}
          {description ? <p className="jdms-page-description">{description}</p> : null}
        </div>
        {actions ? <Space wrap className="jdms-page-actions">{actions}</Space> : null}
      </div>
    </header>
  );
}
