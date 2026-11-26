import { useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from './client';
import type { Capture, Classification } from './types';

/** Polling interval while the worker reads the file, and how many times to ask before giving up. */
const POLL_MS = 1500;
const MAX_POLLS = 160;

export interface CaptureRequest {
  file: File;
  /** Identifies the attached file, so a replaced file starts a new capture. */
  fileKey: string;
  documentTypeCode: string;
  branchCode: string;
  folderId?: string;
  classification?: Classification;
}

export type CaptureState =
  | { phase: 'idle' }
  | { phase: 'starting' }
  | { phase: 'reading' }
  | { phase: 'done'; capture: Capture }
  | { phase: 'unavailable' };

/** Polls made per capture; past the limit the poll fails and the form is left to the user. */
const polls = new Map<string, number>();

const discardTimers = new Map<string, ReturnType<typeof setTimeout>>();

function discard(id: string) {
  polls.delete(id);
  // best effort: an abandoned capture also expires on the server
  void api<unknown>(`/captures/${id}`, { method: 'DELETE', idempotent: true }).catch(() => undefined);
}

function startCapture(request: CaptureRequest): Promise<Capture> {
  const body = new FormData();
  // the metadata part must come before the file part (streamed upload, ADR-0011)
  body.append('metadata', new Blob([JSON.stringify({
    documentTypeCode: request.documentTypeCode, branchCode: request.branchCode, folderId: request.folderId,
    classification: request.classification,
  })], { type: 'application/json' }));
  body.append('file', request.file, request.file.name);
  return api<Capture>('/captures', { method: 'POST', form: body, idempotent: true });
}

function pollCapture(id: string): Promise<Capture> {
  const count = (polls.get(id) ?? 0) + 1;
  polls.set(id, count);
  if (count > MAX_POLLS) {
    return Promise.reject(new Error('Gave up waiting for the document to be read'));
  }
  return api<Capture>(`/captures/${id}`);
}

/**
 * Assisted indexing (B-15, ADR-0024): sends the attached file to be read once the document type and branch are
 * known, polls until the worker has read it, and discards the capture when the file or type changes or the page is
 * left (after the upload too). Never blocks the upload: any failure just means no suggestions.
 */
export function useCapture(request: CaptureRequest | null): CaptureState {
  // a new capture only for another file or type; branch, folder and classification are checked at the start
  const requestKey = request ? `${request.fileKey}|${request.documentTypeCode}` : null;
  const started = useQuery({
    queryKey: ['capture-start', requestKey],
    queryFn: () => startCapture(request as CaptureRequest),
    enabled: request !== null,
    staleTime: Infinity,
    // kept across a strict-mode remount, so the file is not sent twice
    gcTime: 60_000,
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });
  const id = request ? started.data?.id ?? null : null;

  useEffect(() => {
    if (!id) {
      return undefined;
    }
    // a remount with the same capture (React's strict mode) keeps it; otherwise it is discarded
    const scheduled = discardTimers.get(id);
    if (scheduled) {
      clearTimeout(scheduled);
      discardTimers.delete(id);
    }
    return () => {
      discardTimers.set(id, setTimeout(() => {
        discardTimers.delete(id);
        discard(id);
      }, 0));
    };
  }, [id]);

  const read = useQuery({
    queryKey: ['capture', id],
    queryFn: () => pollCapture(id as string),
    enabled: id !== null,
    refetchInterval: (query) => (query.state.data?.status === 'PENDING' ? POLL_MS : false),
    staleTime: Infinity,
    gcTime: 0,
    retry: false,
    refetchOnWindowFocus: false,
  });

  if (!request) {
    return { phase: 'idle' };
  }
  if (started.isError || read.isError) {
    return { phase: 'unavailable' };
  }
  if (!id) {
    return { phase: 'starting' };
  }
  if (!read.data || read.data.status === 'PENDING') {
    return { phase: 'reading' };
  }
  return { phase: 'done', capture: read.data };
}
