import { userManager } from '../auth/oidc';

const BASE = '/api/v1';

/** A failed API call: the RFC 9457 problem's stable `code`, its detail and any field violations. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly detail: string,
    readonly errors: { field: string; code: string; message: string }[] = [],
  ) {
    super(detail || code);
    this.name = 'ApiError';
  }
}

export function newIdempotencyKey(): string {
  return `ui-${crypto.randomUUID()}`;
}

async function authorization(): Promise<Record<string, string>> {
  const user = await userManager.getUser();
  return user && !user.expired ? { Authorization: `Bearer ${user.access_token}` } : {};
}

async function failure(response: Response): Promise<ApiError> {
  try {
    const problem = (await response.json()) as { code?: string; detail?: string; errors?: ApiError['errors'] };
    return new ApiError(response.status, problem.code ?? `HTTP_${response.status}`, problem.detail ?? '',
      problem.errors ?? []);
  } catch {
    return new ApiError(response.status, `HTTP_${response.status}`, response.statusText);
  }
}

export interface RequestOptions {
  method?: string;
  body?: unknown;
  form?: FormData;
  headers?: Record<string, string>;
  idempotent?: boolean;
}

/** Calls the core API with the user's token; JSON in and out, problem+json errors thrown as {@link ApiError}. */
export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json', ...(await authorization()), ...options.headers };
  let body: BodyInit | undefined;
  if (options.form) {
    body = options.form;
  } else if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(options.body);
  }
  if (options.idempotent) {
    headers['Idempotency-Key'] = newIdempotencyKey();
  }
  const response = await fetch(`${BASE}${path}`, { method: options.method ?? 'GET', headers, body });
  if (!response.ok) {
    throw await failure(response);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

/** Binary content (preview, thumbnail, download) as a blob. */
export async function apiBlob(path: string): Promise<Blob> {
  const response = await fetch(`${BASE}${path}`, { headers: await authorization() });
  if (!response.ok) {
    throw await failure(response);
  }
  return response.blob();
}

/** The `version` of a resource as an If-Match header value. */
export function ifMatch(version: number): Record<string, string> {
  return { 'If-Match': `"${version}"` };
}
