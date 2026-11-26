import { request, test, type APIRequestContext, type Browser } from '@playwright/test';
import { signIn } from '../stack/support';

// Test data for a fresh test server (docs/manuals/build/README.md): customers of tenant BANK1 with their KYC, account
// and loan documents, uploaded and decided through the API as the dev users would do it in the screens. Most end up
// Active; two wait for a checker, one is rejected, and one customer is under a legal hold. A second run adds nothing.

type Fields = Record<string, string | number | boolean>;

interface Doc {
  type: 'PASSPORT' | 'NATIONAL_ID' | 'ACCOUNT_OPENING_FORM' | 'LOAN_AGREEMENT' | 'COLLATERAL_DEED';
  title: string;
  keys?: Fields;
  custom: Fields;
  lines: string[];
  /** what the approvers do; default approve */
  outcome?: 'approve' | 'pending' | 'reject';
}

interface Customer {
  cif: string;
  name: string;
  branch: '001' | '002';
  docs: Doc[];
}

const year = new Date().getFullYear();
const date = (offsetYears: number, month: number, day: number) =>
  `${year + offsetYears}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;

function passport(no: string, nationality: string, place: string, issued: string, expires: string): Doc {
  return {
    type: 'PASSPORT', title: 'Passport', keys: { expiryDate: expires },
    custom: { passport_no: no, nationality, issue_date: issued, place_of_issue: place },
    lines: ['PASSPORT - DATA PAGE', `Passport No. ${no}`, `Place of issue ${place}`, `Date of issue ${issued}`,
      `Date of expiry ${expires}`],
  };
}

function nationalId(no: string, issued: string, expires: string): Doc {
  return {
    type: 'NATIONAL_ID', title: 'National ID card', keys: { expiryDate: expires },
    custom: { national_id_no: no, issue_date: issued },
    lines: ['NATIONAL IDENTITY CARD', `Civil number ${no}`, `Issued ${issued}`, `Valid until ${expires}`],
  };
}

function accountOpening(accountNo: string, accountType: string, opened: string, officer: string, joint = false): Doc {
  return {
    type: 'ACCOUNT_OPENING_FORM', title: 'Account opening form', keys: { accountNo },
    custom: { account_type: accountType, date_opened: opened, officer, joint_account: joint },
    lines: ['ACCOUNT OPENING FORM - INDIVIDUALS', `Account number ${accountNo}`,
      `Account type ${accountType.replace('_', ' ').toLowerCase()}`, `Relationship officer ${officer}`,
      'The customer confirms the information given is true and complete.'],
  };
}

function loan(contractRef: string, facility: string, amount: number, originated: string, tenor: number): Doc {
  return {
    type: 'LOAN_AGREEMENT', title: 'Loan agreement', keys: { contractRef },
    custom: { facility_type: facility, amount, currency: 'OMR', loan_origination_date: originated, tenor_months: tenor },
    lines: ['LOAN AGREEMENT', `Contract reference ${contractRef}`, `Facility ${facility.replace('_', ' ').toLowerCase()}`,
      `Principal amount OMR ${amount.toLocaleString('en')}`, `Tenor ${tenor} months`,
      'Repayment by equal monthly instalments from the borrower salary account.'],
  };
}

function collateral(collateralCode: string, kind: string, value: number, valued: string, deedNo: string): Doc {
  return {
    type: 'COLLATERAL_DEED', title: 'Collateral deed', keys: { collateralCode },
    custom: { collateral_type: kind, valuation_amount: value, valuation_date: valued, deed_number: deedNo },
    lines: ['COLLATERAL DEED', `Collateral code ${collateralCode}`, `Deed number ${deedNo}`,
      `Collateral ${kind.toLowerCase()}`, `Valuation OMR ${value.toLocaleString('en')} on ${valued}`],
  };
}

const CUSTOMERS: Customer[] = [
  { cif: '100231', name: 'Aisha Al Balushi', branch: '001', docs: [
    passport('PA4410231', 'OM', 'Muscat', date(-3, 2, 14), date(7, 2, 13)),
    nationalId('10423187', date(-2, 6, 1), date(3, 5, 31)),
    accountOpening('0101-100231-001', 'SAVINGS', date(-1, 9, 20), 'Huda Al Lawati'),
  ] },
  { cif: '100245', name: 'Salim Al Harthy', branch: '001', docs: [
    passport('PB2290845', 'OM', 'Sohar', date(-5, 11, 3), date(5, 11, 2)),
    nationalId('09876512', date(-1, 1, 10), date(4, 1, 9)),
    accountOpening('0101-100245-001', 'CURRENT', date(-4, 3, 8), 'Huda Al Lawati'),
    loan('LN-001-2026-0187', 'PERSONAL_LOAN', 18000, date(-1, 4, 15), 60),
    collateral('COL-001-0187', 'DEPOSIT', 20000, date(-1, 4, 10), 'FD-77120'),
  ] },
  { cif: '100262', name: 'Fatma Al Rawahi', branch: '001', docs: [
    passport('PA9917262', 'OM', 'Muscat', date(-2, 8, 22), date(8, 8, 21)),
    nationalId('11298340', date(-3, 10, 5), date(2, 10, 4)),
    loan('LN-001-2026-0204', 'HOME_LOAN', 145000, date(0, 2, 1), 240),
    collateral('COL-001-0204', 'PROPERTY', 210000, date(-1, 12, 18), 'MQ-2291/14'),
  ] },
  { cif: '100288', name: 'Rajesh Kumar Menon', branch: '001', docs: [
    passport('Z5512288', 'IN', 'Kochi', date(-4, 7, 30), date(6, 7, 29)),
    nationalId('R-88213044', date(-1, 3, 12), date(1, 3, 11)),
    accountOpening('0101-100288-001', 'CURRENT', date(-2, 5, 17), 'Ahmed Al Farsi', true),
  ] },
  { cif: '100301', name: 'Mariam Al Zadjali', branch: '001', docs: [
    passport('PC0043301', 'OM', 'Salalah', date(-1, 5, 9), date(9, 5, 8)),
    accountOpening('0101-100301-002', 'FIXED_DEPOSIT', date(0, 1, 6), 'Ahmed Al Farsi'),
  ] },
  { cif: '100317', name: 'Khalid Al Amri', branch: '001', docs: [
    nationalId('08761123', date(-2, 2, 2), date(3, 2, 1)),
    loan('LN-001-2026-0219', 'AUTO_LOAN', 12500, date(0, 3, 12), 48),
    collateral('COL-001-0219', 'VEHICLE', 15000, date(0, 3, 5), 'MV-55-AB-3017'),
  ] },
  { cif: '100344', name: 'Noura Al Kindi', branch: '001', docs: [
    { ...passport('PB7781344', 'OM', 'Nizwa', date(-1, 10, 11), date(9, 10, 10)), outcome: 'pending' },
    { ...accountOpening('0101-100344-001', 'SAVINGS', date(0, 4, 2), 'Huda Al Lawati'), outcome: 'pending' },
  ] },
  { cif: '100359', name: 'Omar Al Saadi', branch: '001', docs: [
    { ...passport('PA1200359', 'OM', 'Muscat', date(-6, 1, 25), date(4, 1, 24)), outcome: 'reject' },
  ] },
  // branch 002 (Main Street), sent by a bank system through the integration client
  { cif: '200112', name: 'Hamed Al Busaidi', branch: '002', docs: [
    nationalId('10077345', date(-1, 7, 7), date(4, 7, 6)),
    accountOpening('0102-200112-001', 'CALL_DEPOSIT', date(-1, 7, 20), 'Said Al Mahrouqi'),
  ] },
  { cif: '200127', name: 'Layla Al Hinai', branch: '002', docs: [
    passport('PB3302127', 'OM', 'Muscat', date(-2, 12, 1), date(8, 11, 30)),
    loan('LN-002-2026-0042', 'OVERDRAFT', 5000, date(0, 5, 2), 12),
  ] },
];

const HOLD = { name: 'Litigation - Salim Al Harthy', reason: 'Court case 2026/118: keep all documents of the customer', cif: '100245' };

/** A one-page PDF with the given lines of text, so search finds words of its content. */
function pdf(lines: string[]): Buffer {
  const text = lines.map((line, index) =>
    `BT /F1 ${index === 0 ? 20 : 13} Tf 72 ${740 - index * 28} Td (${line.replace(/[()\\]/g, '')}) Tj ET`).join('\n');
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>',
    `<< /Length ${text.length} >>\nstream\n${text}\nendstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ];
  let body = '%PDF-1.4\n';
  const offsets: number[] = [];
  objects.forEach((object, index) => {
    offsets.push(body.length);
    body += `${index + 1} 0 obj\n${object}\nendobj\n`;
  });
  const xref = body.length;
  body += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  body += offsets.map((offset) => `${String(offset).padStart(10, '0')} 00000 n \n`).join('');
  body += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(body, 'latin1');
}

const webUrl = process.env.JDMS_WEB_PUBLIC_URL ?? 'http://localhost:5180';

/** An API client for a dev user: signs in through the screens and uses the token the web app holds. */
async function userApi(browser: Browser, username: string): Promise<APIRequestContext> {
  const page = await (await browser.newContext()).newPage();
  await signIn(page, username);
  const token = await page.evaluate(() => {
    const key = Object.keys(window.sessionStorage).find((candidate) => candidate.startsWith('oidc.user:'));
    return key ? (JSON.parse(window.sessionStorage.getItem(key) ?? '{}') as { access_token?: string }).access_token : undefined;
  });
  if (!token) throw new Error(`no token for ${username}`);
  return request.newContext({ baseURL: `${webUrl}/api/v1/`, ignoreHTTPSErrors: true,
    extraHTTPHeaders: { Authorization: `Bearer ${token}` } });
}

/** An API client for the integration client (client credentials), or undefined when its secret is not set. */
async function integrationApi(): Promise<APIRequestContext | undefined> {
  const secret = process.env.JDMS_INTEGRATION_TEST_CLIENT_SECRET;
  const keycloak = process.env.KEYCLOAK_PUBLIC_URL ?? `http://localhost:${process.env.KEYCLOAK_PORT ?? '8180'}`;
  if (!secret) return undefined;
  const keycloakApi = await request.newContext({ ignoreHTTPSErrors: true });
  const response = await keycloakApi.post(`${keycloak}/realms/jdms-local/protocol/openid-connect/token`, {
    form: { grant_type: 'client_credentials', client_id: 'jdms-integration-test', client_secret: secret },
  });
  if (!response.ok()) throw new Error(`integration client token: ${response.status()}`);
  const { access_token: token } = (await response.json()) as { access_token: string };
  return request.newContext({ baseURL: `${webUrl}/api/v1/`, ignoreHTTPSErrors: true,
    extraHTTPHeaders: { Authorization: `Bearer ${token}` } });
}

async function json<T>(response: Awaited<ReturnType<APIRequestContext['get']>>, what: string): Promise<T> {
  if (!response.ok()) throw new Error(`${what}: ${response.status()} ${await response.text()}`);
  return (await response.json()) as T;
}

test('test data for a fresh test server', async ({ browser }) => {
  const maker = await userApi(browser, 'maker1');
  const existing = await json<{ total: number }>(await maker.get(`documents?cif=${CUSTOMERS[0].cif}`), 'documents');
  if (existing.total > 0) {
    console.log(`Test data already there (customer ${CUSTOMERS[0].cif}); nothing added.`);
    return;
  }
  const integration = await integrationApi();
  if (!integration) console.log('JDMS_INTEGRATION_TEST_CLIENT_SECRET not set: branch 002 customers left out.');

  // uploads: branch 001 by maker1 in the screens' channel, branch 002 by a bank system
  const outcomes = new Map<string, 'approve' | 'pending' | 'reject'>();
  for (const customer of CUSTOMERS) {
    const uploader = customer.branch === '001' ? maker : integration;
    if (!uploader) continue;
    for (const doc of customer.docs) {
      const metadata = { documentTypeCode: doc.type, title: `${doc.title} - ${customer.name}`, branchCode: customer.branch,
        cif: customer.cif, customerName: customer.name, ...doc.keys, customFields: doc.custom };
      const created = await json<{ id: string }>(await uploader.post('documents', {
        headers: { 'Idempotency-Key': crypto.randomUUID() },
        multipart: {
          metadata: { name: 'metadata.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(metadata)) },
          file: { name: `${doc.type.toLowerCase()}-${customer.cif}.pdf`, mimeType: 'application/pdf',
            buffer: pdf([...doc.lines.slice(0, 1), `Customer ${customer.name}`, `CIF ${customer.cif}`, ...doc.lines.slice(1)]) },
        },
      }), `upload ${metadata.title}`);
      outcomes.set(created.id, doc.outcome ?? 'approve');
      console.log(`uploaded ${metadata.title} (${doc.type})`);
    }
  }

  // decisions: the branch checker first, then the credit committee for the large loan
  for (const username of ['checker1', 'committee1', 'committee2', 'committee3']) {
    const approver = await userApi(browser, username);
    const inbox = await json<{ items: { id: string; documentId?: string; name: string }[] }>(
      await approver.get('tasks?page=0&size=200'), 'tasks');
    for (const task of inbox.items) {
      const outcome = task.documentId ? outcomes.get(task.documentId) : undefined;
      if (!outcome || outcome === 'pending') continue;
      const decision = outcome === 'reject'
        ? { decision: 'REJECT', comment: 'The scan is unreadable; please upload a clear copy of the data page.' }
        : { decision: 'APPROVE', comment: 'Checked against the original.' };
      const response = await approver.post(`tasks/${task.id}/complete`,
        { headers: { 'Idempotency-Key': crypto.randomUUID() }, data: decision });
      console.log(`${username}: ${decision.decision} ${task.name} -> ${response.status()}`);
    }
  }

  // a legal hold on one customer's documents
  const records = await userApi(browser, 'records1');
  const hold = await json<{ id: string }>(await records.post('legal-holds', {
    headers: { 'Idempotency-Key': crypto.randomUUID() }, data: { name: HOLD.name, reason: HOLD.reason } }), 'legal hold');
  await json(await records.post(`legal-holds/${hold.id}/items`, {
    headers: { 'Idempotency-Key': crypto.randomUUID() }, data: { cif: HOLD.cif } }), 'legal hold items');
  console.log(`legal hold placed: ${HOLD.name}`);
});
