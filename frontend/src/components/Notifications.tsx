import { BellOutlined, WarningOutlined } from '@ant-design/icons';
import { Badge, Button, Popover } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { api } from '../api/client';
import type { Notification, Page } from '../api/types';
import { useFormatters } from '../i18n/labels';

/** The caller's own notifications (GET /me/notifications) behind the bell, with the unread count on it. */
export function Notifications() {
  const { t } = useTranslation();
  const format = useFormatters();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const notifications = useQuery({
    queryKey: ['notifications'],
    queryFn: () => api<Page<Notification>>('/me/notifications?size=20'),
    refetchInterval: 60_000,
  });
  const markRead = useMutation({
    mutationFn: (ids: string[]) => Promise.all(ids.map((id) => api(`/me/notifications/${id}/read`, { method: 'POST', idempotent: true }))),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['notifications'] }),
  });
  const items = notifications.data?.items ?? [];
  const unread = items.filter((item) => !item.readAt);

  const content = (
    <div className="jdms-notifications">
      <div className="jdms-notifications-head">
        <strong>{t('notifications.title')}</strong>
        {unread.length > 0 ? (
          <Button type="link" size="small" onClick={() => markRead.mutate(unread.map((item) => item.id))}>
            {t('notifications.markAllRead')}
          </Button>
        ) : null}
      </div>
      {items.length === 0 ? <p className="jdms-notifications-empty">{t('notifications.empty')}</p> : (
        <ul>
          {items.map((item) => (
            <li key={item.id}>
              <button type="button" className={item.readAt ? 'jdms-notification' : 'jdms-notification jdms-unread'}
                onClick={() => {
                  if (!item.readAt) markRead.mutate([item.id]);
                  if (item.documentId) void navigate(`/documents/${item.documentId}`);
                }}>
                <WarningOutlined aria-hidden="true" />
                <span>
                  {t(`notifications.types.${item.type}`, { defaultValue: item.message })}
                  <time dateTime={item.createdAt}>{format.listDateTime(item.createdAt)}</time>
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );

  return (
    <Popover content={content} trigger="click" placement="bottomRight" arrow={false}>
      <Button type="text" className="jdms-header-icon"
        aria-label={unread.length > 0 ? t('notifications.labelUnread', { count: unread.length }) : t('notifications.title')}
        icon={<Badge count={unread.length} size="small" offset={[2, -2]}><BellOutlined aria-hidden="true" /></Badge>} />
    </Popover>
  );
}
