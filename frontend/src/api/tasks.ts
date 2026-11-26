import { useInfiniteQuery } from '@tanstack/react-query';
import { api } from './client';
import type { Decision, Page, Task } from './types';

/** The largest page the API serves; the inbox loads this many at a time. */
const INBOX_PAGE = 200;

/**
 * The caller's inbox, oldest first, loaded a page at a time. The task filters and counts work on the loaded tasks, so
 * a page is as large as the API allows.
 */
export function useInbox() {
  const query = useInfiniteQuery({
    queryKey: ['tasks', 'inbox'],
    queryFn: ({ pageParam }) => api<Page<Task>>(`/tasks?page=${pageParam}&size=${INBOX_PAGE}`),
    initialPageParam: 0,
    getNextPageParam: (last, pages) =>
      pages.reduce((loaded, page) => loaded + page.items.length, 0) < last.total ? last.page + 1 : undefined,
  });
  const tasks = query.data?.pages.flatMap((page) => page.items) ?? [];
  const total = query.data?.pages[0]?.total ?? 0;
  return { ...query, tasks, total };
}

/** What a task is about, as the inbox groups it. */
export type TaskGroup = 'uploads' | 'versions' | 'returned' | 'changes';

export function taskGroup(task: Task): TaskGroup {
  if (task.kind === 'AMEND') return 'returned';
  if (task.subjectType === 'DOCUMENT') return 'uploads';
  if (task.subjectType === 'VERSION') return 'versions';
  return 'changes';
}

/** The inbox filters: by group, or by deadline. */
export type TaskFilter = 'all' | TaskGroup | 'dueSoon' | 'overdue';

export const TASK_FILTERS: TaskFilter[] = ['all', 'uploads', 'versions', 'returned', 'changes', 'dueSoon', 'overdue'];

export function matches(task: Task, filter: TaskFilter): boolean {
  switch (filter) {
    case 'all': return true;
    case 'dueSoon': return task.slaStatus === 'DUE_SOON';
    case 'overdue': return task.slaStatus === 'OVERDUE';
    default: return taskGroup(task) === filter;
  }
}

export function countTasks(tasks: Task[]): Record<TaskFilter, number> {
  const counts = Object.fromEntries(TASK_FILTERS.map((filter) => [filter, 0])) as Record<TaskFilter, number>;
  for (const task of tasks) {
    for (const filter of TASK_FILTERS) {
      if (matches(task, filter)) counts[filter] += 1;
    }
  }
  return counts;
}

/** The task to open after one is decided: the next in the list, else the previous, else none. */
export function nextTask(tasks: Task[], decidedId: string): Task | undefined {
  const index = tasks.findIndex((task) => task.id === decidedId);
  if (index < 0) return tasks[0];
  return tasks[index + 1] ?? tasks[index - 1];
}

/** Reject and return need a reason; the maker reads it. */
export function needsComment(decision: Decision): boolean {
  return decision === 'REJECT' || decision === 'RETURN';
}
