import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntApp } from 'antd';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import i18n from '../i18n';
import { countTasks, matches, nextTask, taskGroup } from '../api/tasks';
import type { Action, ApprovalRecord, DocumentSummary, Task } from '../api/types';
import { documentActions } from '../pages/documentActions';
import { TasksPage } from '../pages/TasksPage';
import { ApprovalTimeline } from './ApprovalTimeline';
import { DecisionBar } from './DecisionBar';
import { FactList } from './FactList';
import { SlaChip } from './StatusChip';

// PDF.js does not run in jsdom; the viewer has its own Playwright coverage
vi.mock('./DocumentViewer', () => ({ DocumentViewer: () => <div>preview</div> }));

function task(id: string, overrides: Partial<Task> = {}): Task {
  return {
    id, name: 'Check', kind: 'APPROVAL', stageName: null, subjectType: 'DOCUMENT', subjectId: `s-${id}`,
    documentId: `d-${id}`, versionId: `v-${id}`, summary: `Passport ${id}`, round: 1, assignee: null,
    createdAt: '2026-10-25T18:31:00Z', dueAt: null, slaStatus: 'ON_TIME', decisions: ['APPROVE', 'REJECT', 'RETURN'],
    ...overrides,
  };
}

function document(overrides: Partial<DocumentSummary> = {}): DocumentSummary {
  return {
    id: 'doc', cabinetCode: 'CUST', documentTypeCode: 'PASSPORT', folderId: null, title: 'Passport A', status: 'ACTIVE',
    classification: 'CONFIDENTIAL', branchCode: '001', customFields: {}, currentVersion: null, checkedOutBy: null,
    onLegalHold: false, createdAt: '2026-10-25T18:31:00Z', createdBy: 'maker', updatedAt: '2026-10-25T18:31:00Z',
    version: 1, cif: 'C1', ...overrides,
  };
}

const version = { id: 'v1', number: '1.0' } as DocumentSummary['currentVersion'];

beforeEach(async () => {
  await i18n.changeLanguage('en');
});

describe('DecisionBar', () => {
  it('stops Reject and Return without a comment at the comment field', async () => {
    const onDecide = vi.fn();
    render(<DecisionBar decisions={['APPROVE', 'REJECT', 'RETURN']} onDecide={onDecide} />);
    expect(screen.getByText('Required to reject or return')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Reject' }));
    expect(onDecide).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toHaveTextContent('Write why you are rejecting it');
    expect(screen.getByRole('textbox', { name: 'Comment' })).toHaveFocus();
    expect(screen.getByRole('textbox', { name: 'Comment' })).toHaveAttribute('aria-invalid', 'true');

    await userEvent.click(screen.getByRole('button', { name: 'Return for changes' }));
    expect(onDecide).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toHaveTextContent('Write what the maker should change');

    await userEvent.click(screen.getByRole('textbox', { name: 'Comment' }));
    await userEvent.paste('  Add the expiry page ');
    await userEvent.click(screen.getByRole('button', { name: 'Return for changes' }));
    expect(onDecide).toHaveBeenCalledWith('RETURN', 'Add the expiry page');
  });

  it('approves without a comment, primary first, reject last', async () => {
    const onDecide = vi.fn();
    render(<DecisionBar decisions={['REJECT', 'RETURN', 'APPROVE']} onDecide={onDecide} />);
    const buttons = screen.getAllByRole('button').map((button) => button.textContent);
    expect(buttons).toEqual(['Approve', 'Return for changes', 'Reject']);
    await userEvent.click(screen.getByRole('button', { name: 'Approve' }));
    expect(onDecide).toHaveBeenCalledWith('APPROVE', '');
  });

  it('lets the maker resubmit with an optional comment', () => {
    render(<DecisionBar decisions={['RESUBMIT']} onDecide={vi.fn()} />);
    expect(screen.getByRole('button', { name: 'Resubmit' })).toBeInTheDocument();
    expect(screen.getByText('Optional')).toBeInTheDocument();
  });
});

describe('the task inbox model', () => {
  const inbox = [
    task('1', { slaStatus: 'DUE_SOON' }),
    task('2', { subjectType: 'VERSION' }),
    task('3', { kind: 'AMEND', decisions: ['RESUBMIT'] }),
    task('4', { subjectType: 'PENDING_CHANGE', documentId: null, slaStatus: 'OVERDUE' }),
  ];

  it('groups tasks by what they are about', () => {
    expect(inbox.map(taskGroup)).toEqual(['uploads', 'versions', 'returned', 'changes']);
  });

  it('counts each filter', () => {
    expect(countTasks(inbox)).toEqual({ all: 4, uploads: 1, versions: 1, returned: 1, changes: 1, dueSoon: 1, overdue: 1 });
    expect(inbox.filter((candidate) => matches(candidate, 'dueSoon')).map((candidate) => candidate.id)).toEqual(['1']);
  });

  it('opens the next task after a decision, else the previous one', () => {
    expect(nextTask(inbox, '2')?.id).toBe('3');
    expect(nextTask(inbox, '4')?.id).toBe('3');
    expect(nextTask([inbox[0] as Task], '1')).toBeUndefined();
  });
});

describe('document actions', () => {
  const maker = new Set<Action>(['VIEW_CONTENT', 'DOWNLOAD', 'CHECK_OUT', 'DELETE']);

  it('active: download first, new version and check-out beside it, delete in More', () => {
    expect(documentActions(document({ currentVersion: version }), maker, 'me')).toEqual({
      primary: 'download', secondary: ['newVersion', 'checkOut'], more: ['copyLink', 'delete'],
    });
  });

  it('checked out by me: check in first; by someone else: no changes', () => {
    expect(documentActions(document({ currentVersion: version, checkedOutBy: 'me' }), maker, 'me')).toEqual({
      primary: 'checkIn', secondary: ['download'], more: ['copyLink', 'cancelCheckOut'],
    });
    expect(documentActions(document({ currentVersion: version, checkedOutBy: 'other' }), maker, 'me')).toEqual({
      primary: 'download', secondary: [], more: ['copyLink'],
    });
  });

  it('cannot delete while on legal hold or before approval', () => {
    expect(documentActions(document({ currentVersion: version, onLegalHold: true }), maker, 'me').more).toEqual(['copyLink']);
    expect(documentActions(document({ currentVersion: version, status: 'PENDING_APPROVAL' }), maker, 'me')).toEqual({
      primary: 'download', secondary: [], more: ['copyLink'],
    });
  });

  it('deleted: restore for a records officer only; disposed: nothing but the link', () => {
    const deleted = document({ currentVersion: version, status: 'DELETED' });
    expect(documentActions(deleted, new Set<Action>(['RESTORE']), 'me').primary).toBe('restore');
    expect(documentActions(deleted, maker, 'me').primary).toBeNull();
    expect(documentActions(document({ status: 'DISPOSED' }), maker, 'me')).toEqual({
      primary: null, secondary: [], more: ['copyLink'],
    });
  });
});

describe('ApprovalTimeline, FactList and SlaChip', () => {
  it('lists the history newest first with who, when and the comment', () => {
    const records: ApprovalRecord[] = [
      { id: 'a', subjectType: 'DOCUMENT', versionId: null, stageName: null, actorName: 'Mona Maker', decision: 'SUBMIT',
        comment: null, decidedAt: '2026-10-25T18:31:00Z' },
      { id: 'b', subjectType: 'DOCUMENT', versionId: null, stageName: null, actorName: 'Chris Checker', decision: 'RETURN',
        comment: 'Add the expiry page', decidedAt: '2026-10-25T19:00:00Z' },
    ];
    render(<ApprovalTimeline records={records} />);
    const items = screen.getAllByRole('listitem');
    expect(items[0]).toHaveTextContent('Returned by Chris Checker');
    expect(items[0]).toHaveTextContent('Add the expiry page');
    expect(items[1]).toHaveTextContent('Submitted by Mona Maker');
  });

  it('renders labelled values and leaves out empty sections', () => {
    render(<FactList sections={[
      { key: 'customer', title: 'Customer', facts: [{ key: 'cif', label: 'CIF', value: 'C1' }] },
      { key: 'empty', title: 'Nothing', facts: [] },
    ]} />);
    expect(screen.getByRole('heading', { name: 'Customer' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Nothing' })).toBeNull();
    expect(screen.getByRole('term')).toHaveTextContent('CIF');
    expect(screen.getByRole('definition')).toHaveTextContent('C1');
  });

  it('shows due soon and overdue as chips, on time as text, and nothing without a deadline', () => {
    const { container } = render(<><SlaChip sla="DUE_SOON" /><SlaChip sla="OVERDUE" /><SlaChip sla="ON_TIME" /><SlaChip sla="NONE" /></>);
    expect(container.querySelectorAll('.jdms-chip')).toHaveLength(2);
    expect(container).toHaveTextContent('Due soonOverdueOn time');
  });
});

describe('TasksPage', () => {
  const tasks = [task('t1'), task('t2', { summary: 'Passport t2' })];
  const posted: { url: string; body: unknown }[] = [];

  beforeEach(() => {
    posted.length = 0;
    const responses: Record<string, unknown> = {
      '/api/v1/tasks?page=0&size=200': { items: tasks, page: 0, size: 200, total: 2 },
      '/api/v1/tasks/t1': { task: tasks[0], history: [{ id: 'h1', subjectType: 'DOCUMENT', versionId: null, stageName: null,
        actorName: 'Mona Maker', decision: 'SUBMIT', comment: null, decidedAt: '2026-10-25T18:31:00Z' }] },
      '/api/v1/tasks/t2': { task: tasks[1], history: [] },
      '/api/v1/documents/d-t1': { ...document({ id: 'd-t1', title: 'Passport t1' }), allowedActions: ['VIEW_CONTENT'] },
      '/api/v1/documents/d-t2': { ...document({ id: 'd-t2', title: 'Passport t2' }), allowedActions: ['VIEW_CONTENT'] },
      '/api/v1/documents/d-t1/versions': [],
      '/api/v1/documents/d-t2/versions': [],
      '/api/v1/document-types': [],
      '/api/v1/me': { userId: 'checker', username: 'checker1', displayName: 'Chris Checker' },
    };
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (init?.method === 'POST') posted.push({ url, body: JSON.parse(String(init.body)) });
      return new Response(JSON.stringify(init?.method === 'POST' ? {} : (responses[url] ?? {})),
        { status: 200, headers: { 'Content-Type': 'application/json' } });
    }));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function Where() {
    return <output data-testid="where">{useLocation().pathname}</output>;
  }

  function renderAt(path: string) {
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <AntApp>
          <MemoryRouter initialEntries={[path]}>
            <Routes>
              <Route path="tasks" element={<TasksPage />} />
              <Route path="tasks/:taskId" element={<TasksPage />} />
            </Routes>
            <Where />
          </MemoryRouter>
        </AntApp>
      </QueryClientProvider>,
    );
  }

  it('lists the tasks with a filter and no old columns', async () => {
    renderAt('/tasks');
    const list = await screen.findByRole('region', { name: 'Task list' });
    expect(within(list).getAllByRole('link')).toHaveLength(2);
    expect(screen.getByRole('radio', { name: 'All · 2' })).toBeChecked();
    expect(screen.queryByRole('columnheader')).toBeNull();
  });

  it('decides in place without a dialog and opens the next task', async () => {
    renderAt('/tasks/t1');
    const detail = await screen.findByRole('region', { name: 'Task' });
    expect(await within(detail).findByText(/^Submitted by Mona Maker · \d+ Oct 2026, \d\d:\d\d$/)).toBeInTheDocument();
    expect(within(detail).getByRole('link', { name: /Open full document/ })).toHaveAttribute('href', '/documents/d-t1');

    await userEvent.click(within(detail).getByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/tasks/t2'));
    expect(posted).toEqual([{ url: '/api/v1/tasks/t1/complete', body: { decision: 'APPROVE' } }]);
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(await screen.findByRole('heading', { name: 'Passport t2' })).toBeInTheDocument();
  });
});
