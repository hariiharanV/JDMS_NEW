import { useQuery } from '@tanstack/react-query';
import { api } from './client';
import type { Action, Branch, Cabinet, Classification, CurrentUser, DocumentType, Folder, Permissions } from './types';

export function useMe() {
  return useQuery({ queryKey: ['me'], queryFn: () => api<CurrentUser>('/me'), staleTime: 5 * 60_000 });
}

/** Whether the caller's bank offers Arabic (ADR-0023); off until GET /me says otherwise. */
export function useArabicEnabled(): boolean {
  return useMe().data?.languages.includes('ar') ?? false;
}

/** Tenant-wide actions of the caller: hides what they cannot do (the server still checks every request). */
export function usePermissions() {
  const query = useQuery({
    queryKey: ['permissions'],
    queryFn: () => api<Permissions>('/me/permissions'),
    staleTime: 60_000,
  });
  const actions = new Set<Action>(query.data?.actions ?? []);
  return { ...query, can: (action: Action) => actions.has(action) };
}

export function useCabinets() {
  return useQuery({ queryKey: ['cabinets'], queryFn: () => api<Cabinet[]>('/cabinets'), staleTime: 5 * 60_000 });
}

export function useFolders(cabinetId: string | undefined) {
  return useQuery({
    queryKey: ['folders', cabinetId],
    queryFn: () => api<Folder[]>(`/cabinets/${cabinetId}/folders`),
    enabled: !!cabinetId,
    staleTime: 5 * 60_000,
  });
}

export function useDocumentTypes() {
  return useQuery({
    queryKey: ['document-types'],
    queryFn: () => api<DocumentType[]>('/document-types'),
    staleTime: 5 * 60_000,
  });
}

/** A document type's minimum (default) classification by code: lists show a classification only above it. */
export function useTypeMinimum(): (code: string) => Classification | undefined {
  const types = useDocumentTypes();
  return (code) => types.data?.find((type) => type.code === code)?.defaultClassification;
}

/** The bank's branches, for pickers and names (B-17). */
export function useBranches() {
  return useQuery({ queryKey: ['branches'], queryFn: () => api<Branch[]>('/branches'), staleTime: 5 * 60_000 });
}
