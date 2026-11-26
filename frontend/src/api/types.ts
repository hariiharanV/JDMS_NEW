// Response shapes of the core API (docs/04-api-outline.md). Server values are data: always rendered as text.

export type Classification = 'PUBLIC' | 'INTERNAL' | 'CONFIDENTIAL' | 'RESTRICTED';
export const CLASSIFICATIONS: Classification[] = ['PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED'];

export type DocumentStatus = 'DRAFT' | 'PENDING_APPROVAL' | 'ACTIVE' | 'REJECTED' | 'DELETED' | 'DISPOSED';

export type Action =
  | 'VIEW_METADATA'
  | 'VIEW_CONTENT'
  | 'DOWNLOAD'
  | 'UPLOAD'
  | 'EDIT_METADATA'
  | 'CHECK_OUT'
  | 'DELETE'
  | 'RESTORE'
  | 'APPROVE'
  | 'MANAGE_HOLDS'
  | 'MANAGE_RETENTION'
  | 'DISPOSE'
  | 'ADMIN_METADATA'
  | 'ADMIN_ACCESS'
  | 'VIEW_AUDIT';

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface CurrentUser {
  tenantCode: string;
  userId: string;
  username: string;
  displayName: string | null;
  principalType: 'USER' | 'CLIENT';
  roles: string[];
  allBranches: boolean;
  branchCodes: string[];
  clearance: Classification;
  /** The languages the bank's screens offer, English first (ADR-0023). */
  languages: string[];
}

export interface Permissions {
  actions: Action[];
}

export interface Cabinet {
  id: string;
  code: string;
  name: string;
}

export interface Folder {
  id: string;
  parentId: string | null;
  name: string;
  path: string;
}

export type FieldDataType = 'TEXT' | 'NUMBER' | 'DATE' | 'BOOLEAN' | 'LOV';

export interface FieldOption {
  code: string;
  labelEn: string;
  labelAr: string | null;
}

export interface CatalogField {
  key: string;
  labelEn: string;
  labelAr: string | null;
  dataType: FieldDataType;
  mandatory: boolean;
  maxLength: number | null;
  defaultValue: string | null;
  lovCode: string | null;
  sensitive: boolean;
  displayOrder: number;
  displayRow: number;
  options: FieldOption[];
}

export interface DocumentType {
  id: string;
  cabinetId: string;
  code: string;
  nameEn: string;
  nameAr: string | null;
  category: string;
  channels: string[];
  defaultClassification: Classification;
  requiredStandardFields: string[];
  fields: CatalogField[];
}

export interface Version {
  id: string;
  number: string;
  approvalStatus: 'DRAFT' | 'PENDING' | 'APPROVED' | 'REJECTED';
  sizeBytes: number;
  mimeType: string;
  detectedMimeType: string | null;
  pageCount: number | null;
  originalFilename: string;
  sha256: string;
  storageStatus: string;
  processingStatus: 'PENDING' | 'PROCESSING' | 'DONE' | 'FAILED' | 'QUARANTINED';
  indexStatus: string;
  comment: string | null;
  createdAt: string;
  createdBy: string;
}

/** Standard banking keys of a document (unwrapped into the document by the API). */
export const STANDARD_FIELDS = [
  'cif',
  'customerName',
  'accountNo',
  'collateralCode',
  'contractRef',
  'transactionRef',
  'applicationRef',
  'expiryDate',
] as const;
export type StandardField = (typeof STANDARD_FIELDS)[number];

/** API field names of the standard fields named in a type's `requiredStandardFields` (snake case). */
export const STANDARD_FIELD_KEYS: Record<string, StandardField> = {
  cif: 'cif',
  customer_name: 'customerName',
  account_no: 'accountNo',
  collateral_code: 'collateralCode',
  contract_ref: 'contractRef',
  transaction_ref: 'transactionRef',
  application_ref: 'applicationRef',
  expiry_date: 'expiryDate',
};

export interface DocumentSummary extends Partial<Record<StandardField, string | null>> {
  id: string;
  cabinetCode: string;
  documentTypeCode: string;
  folderId: string | null;
  title: string;
  status: DocumentStatus;
  classification: Classification;
  branchCode: string;
  customFields: Record<string, unknown>;
  currentVersion: Version | null;
  checkedOutBy: string | null;
  onLegalHold: boolean;
  createdAt: string;
  createdBy: string;
  updatedAt: string;
  version: number;
}

export interface DocumentDetail extends DocumentSummary {
  allowedActions: Action[];
}

export interface ApprovalRecord {
  id: string;
  subjectType: string;
  versionId: string | null;
  stageName: string | null;
  actorName: string | null;
  decision: string;
  comment: string | null;
  decidedAt: string;
}

export interface AuditEvent {
  id: string;
  seq: number;
  occurredAt: string;
  actorType: string;
  actorId: string;
  action: string;
  resourceType: string;
  resourceId: string | null;
  outcome: 'SUCCESS' | 'DENIED' | 'ERROR';
  clientIp: string | null;
  requestId: string | null;
  details: Record<string, unknown>;
  hash: string;
}

export interface AuditVerification {
  id: string;
  finishedAt: string;
  status: 'VALID' | 'BROKEN';
  eventsChecked: number;
  headSeq: number;
  brokenSeq: number | null;
  failure: string | null;
}

export type Decision = 'APPROVE' | 'REJECT' | 'RETURN' | 'RESUBMIT';

export interface Task {
  id: string;
  name: string;
  kind: 'APPROVAL' | 'VOTE' | 'AMEND';
  stageName: string | null;
  subjectType: string;
  subjectId: string;
  documentId: string | null;
  versionId: string | null;
  summary: string;
  round: number;
  assignee: string | null;
  createdAt: string;
  dueAt: string | null;
  slaStatus: 'NONE' | 'ON_TIME' | 'DUE_SOON' | 'OVERDUE';
  decisions: Decision[];
}

export interface Segment {
  text: string;
  match: boolean;
}

export interface SearchHit {
  id: string;
  title: string;
  documentTypeCode: string;
  cabinetCode: string;
  branchCode: string;
  status: DocumentStatus;
  classification: Classification;
  cif: string | null;
  accountNo: string | null;
  createdAt: string;
  snippets: Segment[][] | null;
}

export interface Bucket {
  value: string;
  count: number;
}

export interface SearchResult {
  items: SearchHit[];
  facets: Record<string, Bucket[]>;
  total: number;
  page: number;
  size: number;
}

export interface PendingChange {
  id: string;
  subjectType: string;
  operation: string;
  subjectId: string | null;
  summary: string;
  status: string;
  proposedBy: string;
  proposedAt: string;
  /** Whether the caller may approve or reject it (not their own proposal, and they hold the permission). */
  canDecide: boolean;
}

export interface RetentionPolicy {
  id: string;
  code: string;
  name: string;
  triggerType: string;
  eventType: string | null;
  periodMonths: number;
  dispositionAction: 'DESTROY' | 'REVIEW';
  requiresApproval: boolean;
  documentTypeCodes: string[];
  version: number;
}

export interface LegalHold {
  id: string;
  name: string;
  reason: string;
  status: 'ACTIVE' | 'RELEASED';
  documentIds: string[];
  createdAt: string;
  createdBy: string | null;
  releasedAt: string | null;
}

export interface DispositionBatch {
  id: string;
  status: 'PENDING' | 'EXECUTED' | 'REJECTED';
  preparedAt: string;
  approvedBy: string | null;
  approvedAt: string | null;
  executedAt: string | null;
  items: { documentId: string; dueDate: string; outcome: string; reason: string | null }[];
}

export interface Notification {
  id: string;
  type: string;
  documentId: string | null;
  versionId: string | null;
  /** English text from the server; the UI shows its own translation of the type when it has one. */
  message: string;
  createdAt: string;
  readAt: string | null;
}

/** Assisted indexing (B-15, ADR-0024): where reading an attached file stands. */
export type CaptureStatus = 'PENDING' | 'DONE' | 'INFECTED' | 'FAILED';

/** A value read from the attached file for one upload field (`cif`, `customFields.amount`, ...). */
export interface FieldSuggestion {
  field: string;
  value: string;
  confidence: 'HIGH' | 'MEDIUM';
  /** The label it was found next to, as written in the document. */
  label: string;
}

export interface Capture {
  id: string;
  status: CaptureStatus;
  documentTypeCode: string;
  ocr: boolean;
  suggestions: FieldSuggestion[];
  expiresAt: string;
}

/** A branch of the bank (GET /branches, B-17). */
export interface Branch {
  code: string;
  name: string;
}
