package com.jmr.jdms.perf.generate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Synthetic banking documents for the performance tenant (B-13): metadata that fits the PERF catalog (the BANK1
 * local catalog) and English or Arabic text of a few hundred words. Pure and deterministic: the same seed and index
 * always give the same document, so a generator run can be resumed or repeated. Customers own several documents
 * each and keep their name, branch and language across them.
 */
public final class SyntheticCorpus {

    /** One synthetic document; {@code customFields} holds JSON-ready values (strings, numbers, booleans). */
    public record SyntheticDocument(long index, String cabinetCode, String typeCode, String branchCode,
            String classification, boolean deleted, String title, String cif, String customerName, String accountNo,
            String contractRef, String collateralCode, LocalDate expiryDate, Map<String, Object> customFields,
            Instant createdAt, String language, String text, String fileName) {
    }

    private record Type(String code, String cabinet, String classification, int weight) {
    }

    private static final List<Type> TYPES = List.of(
            new Type("PASSPORT", "CUSTOMERS", "CONFIDENTIAL", 18),
            new Type("NATIONAL_ID", "CUSTOMERS", "CONFIDENTIAL", 18),
            new Type("ACCOUNT_OPENING_FORM", "CUSTOMERS", "CONFIDENTIAL", 24),
            new Type("LOAN_AGREEMENT", "LOANS", "CONFIDENTIAL", 18),
            new Type("COLLATERAL_DEED", "LOANS", "CONFIDENTIAL", 10),
            new Type("SALARY_CERTIFICATE", "LOANS", "RESTRICTED", 12));
    private static final int TOTAL_WEIGHT = TYPES.stream().mapToInt(Type::weight).sum();
    public static final int BRANCHES = 20;

    private static final List<String> FIRST_EN = List.of("Mohammed", "Ahmed", "Fatma", "Aisha", "Salim", "Khalid",
            "Maryam", "Said", "Huda", "Rashid", "Noor", "Yousuf", "Layla", "Hamad", "Zainab", "Omar", "Sara", "Ali",
            "Muna", "Talal", "Priya", "Ravi", "Anil", "Grace", "John", "Elena", "Tariq", "Badar", "Shamsa", "Nasser");
    private static final List<String> LAST_EN = List.of("Al Balushi", "Al Harthy", "Al Busaidi", "Al Hinai",
            "Al Lawati", "Al Rawahi", "Al Siyabi", "Al Kindi", "Al Maamari", "Al Farsi", "Al Shukaili", "Al Amri",
            "Sharma", "Nair", "Fernandes", "Pereira", "Khan", "Smith", "Haddad", "Mansour");
    private static final List<String> FIRST_AR = List.of("محمد", "أحمد", "فاطمة", "عائشة", "سالم", "خالد", "مريم",
            "سعيد", "هدى", "راشد", "نور", "يوسف", "ليلى", "حمد", "زينب", "عمر", "سارة", "علي", "منى", "طلال", "بدر",
            "شمسة", "ناصر", "طارق");
    private static final List<String> LAST_AR = List.of("البلوشي", "الحارثي", "البوسعيدي", "الهنائي", "اللواتي",
            "الرواحي", "السيابي", "الكندي", "المعمري", "الفارسي", "الشكيلي", "العامري", "الحداد", "المنصوري");
    private static final List<String> CITIES_EN = List.of("Muscat", "Sohar", "Salalah", "Nizwa", "Sur", "Ibri",
            "Barka", "Seeb", "Rustaq", "Khasab", "Dubai", "Riyadh", "Doha", "Manama", "Kuwait City");
    private static final List<String> CITIES_AR = List.of("مسقط", "صحار", "صلالة", "نزوى", "صور", "عبري", "بركاء",
            "السيب", "الرستاق", "خصب", "دبي", "الرياض", "الدوحة", "المنامة");
    private static final List<String> EMPLOYERS = List.of("Oman Oil Marketing", "Ministry of Health",
            "Muscat Municipality", "Sohar Aluminium", "Al Madina Logistics", "Gulf Engineering Services",
            "National Ferries", "Royal Hospital", "Dhofar University", "Coastal Trading LLC", "Desert Rose Hotels");
    private static final List<String> NATIONALITIES = List.of("OM", "OM", "OM", "OM", "AE", "SA", "BH", "KW", "QA",
            "EG", "IN", "PK", "GB");
    private static final List<String> ACCOUNT_TYPES = List.of("CURRENT", "SAVINGS", "FIXED_DEPOSIT", "CALL_DEPOSIT");
    private static final List<String> FACILITIES = List.of("PERSONAL_LOAN", "HOME_LOAN", "AUTO_LOAN", "OVERDRAFT",
            "CREDIT_CARD", "CORPORATE_TERM_LOAN");
    private static final List<String> CURRENCIES = List.of("OMR", "OMR", "OMR", "AED", "SAR", "USD", "EUR", "INR");
    private static final List<String> COLLATERALS = List.of("PROPERTY", "VEHICLE", "DEPOSIT", "SHARES", "GOLD",
            "GUARANTEE");

    private static final List<String> SENTENCES_EN = List.of(
            "The customer {name} holds CIF {cif} and is served by branch {branch}.",
            "This document was received at the {city} branch and verified against the original.",
            "The relationship officer {officer} confirmed the identity of the applicant in person.",
            "All signatures were compared with the specimen held on file for account {account}.",
            "The applicant declared a monthly income of {amount} {currency} from {employer}.",
            "Copies were certified as true copies of the original by the branch operations team.",
            "The facility of {amount} {currency} is repayable over {months} monthly instalments.",
            "Interest is charged on the outstanding balance at the rate agreed in the offer letter.",
            "The borrower undertakes to keep the collateral insured for its full replacement value.",
            "Any change of address or employment must be notified to the bank within thirty days.",
            "The bank may set off any amount due against balances held in the name of the customer.",
            "The valuation report dated {date} assessed the asset at {amount} {currency}.",
            "Know your customer checks were completed and screening returned no matches.",
            "The document is retained in accordance with the record retention policy of the bank.",
            "The customer consented to the processing of personal data for account servicing.",
            "Standing instructions for salary transfer were registered with effect from {date}.",
            "The cheque book request was approved and dispatched to the {city} branch.",
            "Early settlement is permitted subject to the fees in the schedule of charges.",
            "The guarantor accepted joint and several liability for the obligations of the borrower.",
            "The title deed number {deed} was registered with the land registry in {city}.",
            "Statements will be delivered electronically to the registered email address.",
            "The account was opened with an initial deposit of {amount} {currency}.",
            "The passport was inspected under ultraviolet light and its security features checked.",
            "Residency and visa details were recorded for the expatriate applicant.",
            "The employer letter confirms continuous employment since {date}.",
            "The vehicle registration card lists the bank as the first beneficiary.",
            "A mortgage over the property was created in favour of the bank on {date}.",
            "The overdraft limit is reviewed annually and may be withdrawn on notice.",
            "Instalments are collected on the last working day of each month.",
            "The credit committee approved the proposal subject to the conditions listed.",
            "The customer acknowledged receipt of the terms and conditions in English and Arabic.",
            "No adverse information was reported by the credit bureau on {date}.",
            "The branch manager reviewed the file and recommended approval.",
            "The deposit is placed for a fixed term and renews automatically at maturity.",
            "Foreign exchange conversions are made at the rate prevailing on the transaction date.");
    private static final List<String> SENTENCES_AR = List.of(
            "العميل {name} يحمل رقم العميل {cif} ويتعامل مع فرع {branch}.",
            "تم استلام هذا المستند في فرع {city} ومطابقته مع الأصل.",
            "أكد موظف العلاقة {officer} هوية مقدم الطلب شخصياً.",
            "تمت مطابقة جميع التواقيع مع نموذج التوقيع المحفوظ للحساب {account}.",
            "صرح مقدم الطلب بدخل شهري قدره {amount} {currency} من {employer}.",
            "تم تصديق النسخ كنسخ طبق الأصل من قبل فريق العمليات في الفرع.",
            "يسدد التسهيل البالغ {amount} {currency} على {months} قسطاً شهرياً.",
            "تحتسب الفائدة على الرصيد القائم بالسعر المتفق عليه في خطاب العرض.",
            "يتعهد المقترض بالتأمين على الضمان بكامل قيمته الاستبدالية.",
            "يجب إخطار البنك بأي تغيير في العنوان أو جهة العمل خلال ثلاثين يوماً.",
            "يحق للبنك مقاصة أي مبلغ مستحق مقابل الأرصدة المحتفظ بها باسم العميل.",
            "قدر تقرير التقييم المؤرخ {date} قيمة الأصل بمبلغ {amount} {currency}.",
            "تم استكمال إجراءات اعرف عميلك ولم يسفر التدقيق عن أي تطابق.",
            "يحتفظ بالمستند وفقاً لسياسة الاحتفاظ بالسجلات في البنك.",
            "وافق العميل على معالجة بياناته الشخصية لأغراض خدمة الحساب.",
            "تم تسجيل تعليمات تحويل الراتب اعتباراً من {date}.",
            "تمت الموافقة على طلب دفتر الشيكات وإرساله إلى فرع {city}.",
            "يسمح بالسداد المبكر وفقاً للرسوم الواردة في جدول التعرفة.",
            "قبل الكفيل المسؤولية التضامنية عن التزامات المقترض.",
            "تم تسجيل سند الملكية رقم {deed} لدى السجل العقاري في {city}.",
            "سترسل كشوف الحساب إلكترونياً إلى البريد الإلكتروني المسجل.",
            "تم فتح الحساب بإيداع مبدئي قدره {amount} {currency}.",
            "تم فحص جواز السفر تحت الأشعة فوق البنفسجية والتحقق من خصائصه الأمنية.",
            "تؤكد رسالة جهة العمل استمرار التوظيف منذ {date}.",
            "تم إنشاء رهن على العقار لصالح البنك بتاريخ {date}.",
            "وافقت لجنة الائتمان على المقترح وفقاً للشروط المذكورة.",
            "أقر العميل باستلام الشروط والأحكام باللغتين العربية والإنجليزية.",
            "لم تبلغ شركة المعلومات الائتمانية عن أي معلومات سلبية بتاريخ {date}.",
            "راجع مدير الفرع الملف وأوصى بالموافقة.",
            "تودع الوديعة لأجل محدد وتجدد تلقائياً عند الاستحقاق.");
    private static final Map<String, String> TITLES_AR = Map.of("PASSPORT", "جواز سفر", "NATIONAL_ID",
            "بطاقة هوية", "ACCOUNT_OPENING_FORM", "نموذج فتح حساب", "LOAN_AGREEMENT", "اتفاقية قرض",
            "COLLATERAL_DEED", "سند ضمان", "SALARY_CERTIFICATE", "شهادة راتب");
    private static final Map<String, String> TITLES_EN = Map.of("PASSPORT", "Passport", "NATIONAL_ID",
            "National ID card", "ACCOUNT_OPENING_FORM", "Account opening form", "LOAN_AGREEMENT", "Loan agreement",
            "COLLATERAL_DEED", "Collateral deed", "SALARY_CERTIFICATE", "Salary certificate");

    private static final LocalDate FIRST_DAY = LocalDate.of(2021, 1, 1);
    private static final int DAYS = (int) ChronoUnit.DAYS.between(FIRST_DAY, LocalDate.of(2026, 10, 1));

    private final long seed;
    private final int customers;
    private final double arabicShare;

    public SyntheticCorpus(long seed, int customers, double arabicShare) {
        this.seed = seed;
        this.customers = customers;
        this.arabicShare = arabicShare;
    }

    public static String branchCode(int number) {
        return String.format(Locale.ROOT, "%03d", number);
    }

    public SyntheticDocument document(long index) {
        SplittableRandom random = new SplittableRandom(seed * 1_000_003L + index);
        int customer = random.nextInt(customers);
        SplittableRandom person = new SplittableRandom(seed ^ (customer * 7_919L + 17));
        boolean arabic = person.nextDouble() < arabicShare;
        // branch 001 (head office) holds a fifth of the customers
        String branch = branchCode(person.nextInt(5) == 0 ? 1 : 2 + person.nextInt(BRANCHES - 1));
        String name = arabic ? pick(person, FIRST_AR) + " " + pick(person, FIRST_AR) + " " + pick(person, LAST_AR)
                : pick(person, FIRST_EN) + " " + pick(person, LAST_EN);
        String cif = String.format(Locale.ROOT, "C%08d", customer + 1);
        String accountNo = String.format(Locale.ROOT, "%s%010d", branch, 1_000_000L + customer * 3L);

        Type type = type(random);
        LocalDate created = FIRST_DAY.plusDays(random.nextInt(DAYS));
        Instant createdAt = created.atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(random.nextInt(86_400));
        String classification = "CONFIDENTIAL".equals(type.classification()) && random.nextInt(100) < 15
                ? "INTERNAL" : type.classification();
        boolean deleted = random.nextInt(100) < 2;

        Map<String, Object> fields = new LinkedHashMap<>();
        Map<String, String> fill = new LinkedHashMap<>();
        String contractRef = null;
        String collateralCode = null;
        LocalDate expiry = null;
        long amount = 500L * (1 + random.nextInt(400));
        String currency = pick(random, CURRENCIES);
        LocalDate issued = created.minusDays(random.nextInt(2000));
        switch (type.code()) {
            case "PASSPORT" -> {
                fields.put("passport_no", String.format(Locale.ROOT, "P%07d", random.nextInt(10_000_000)));
                fields.put("nationality", pick(random, NATIONALITIES));
                fields.put("issue_date", issued.toString());
                fields.put("place_of_issue", pick(random, CITIES_EN));
                expiry = issued.plusYears(10);
            }
            case "NATIONAL_ID" -> {
                fields.put("national_id_no", String.format(Locale.ROOT, "%08d", random.nextInt(100_000_000)));
                fields.put("issue_date", issued.toString());
                expiry = issued.plusYears(5);
            }
            case "ACCOUNT_OPENING_FORM" -> {
                fields.put("account_type", pick(random, ACCOUNT_TYPES));
                fields.put("date_opened", created.toString());
                fields.put("officer", pick(random, FIRST_EN) + " " + pick(random, LAST_EN));
                fields.put("joint_account", random.nextInt(10) == 0);
            }
            case "LOAN_AGREEMENT" -> {
                contractRef = String.format(Locale.ROOT, "LN%s%07d", branch, random.nextInt(10_000_000));
                fields.put("facility_type", pick(random, FACILITIES));
                fields.put("amount", BigDecimal.valueOf(amount * 10));
                fields.put("currency", currency);
                fields.put("loan_origination_date", created.toString());
                fields.put("tenor_months", 12 * (1 + random.nextInt(25)));
            }
            case "COLLATERAL_DEED" -> {
                collateralCode = String.format(Locale.ROOT, "CL%07d", random.nextInt(10_000_000));
                fields.put("collateral_type", pick(random, COLLATERALS));
                fields.put("valuation_amount", BigDecimal.valueOf(amount * 20));
                fields.put("valuation_date", created.minusDays(random.nextInt(60)).toString());
                fields.put("deed_number", String.format(Locale.ROOT, "D-%06d", random.nextInt(1_000_000)));
            }
            case "SALARY_CERTIFICATE" -> {
                fields.put("employer", pick(random, EMPLOYERS));
                fields.put("monthly_salary", BigDecimal.valueOf(300 + random.nextInt(4000)));
                fields.put("issue_date", created.toString());
            }
            default -> throw new IllegalStateException(type.code());
        }
        fill.put("name", name);
        fill.put("cif", cif);
        fill.put("account", accountNo);
        fill.put("branch", branch);
        fill.put("amount", Long.toString(amount));
        fill.put("currency", currency);
        fill.put("months", Integer.toString(12 * (1 + random.nextInt(25))));
        fill.put("date", created.minusDays(random.nextInt(400)).toString());
        fill.put("city", pick(random, arabic ? CITIES_AR : CITIES_EN));
        fill.put("employer", pick(random, EMPLOYERS));
        fill.put("officer", arabic ? pick(random, FIRST_AR) + " " + pick(random, LAST_AR)
                : pick(random, FIRST_EN) + " " + pick(random, LAST_EN));
        fill.put("deed", String.format(Locale.ROOT, "D-%06d", random.nextInt(1_000_000)));

        String typeTitle = (arabic ? TITLES_AR : TITLES_EN).get(type.code());
        String title = typeTitle + " - " + name;
        StringBuilder text = new StringBuilder(2048).append(typeTitle).append('\n').append(name).append('\n')
                .append(cif).append(' ').append(accountNo).append("\n\n");
        List<String> sentences = arabic ? SENTENCES_AR : SENTENCES_EN;
        int count = 10 + random.nextInt(20);
        for (int i = 0; i < count; i++) {
            text.append(fill(pick(random, sentences), fill)).append(i % 4 == 3 ? "\n\n" : " ");
        }
        return new SyntheticDocument(index, type.cabinet(), type.code(), branch, classification, deleted, title, cif,
                name, "ACCOUNT_OPENING_FORM".equals(type.code()) || random.nextInt(3) == 0 ? accountNo : null,
                contractRef, collateralCode, expiry, fields, createdAt, arabic ? "ar" : "en", text.toString().strip(),
                type.code().toLowerCase(Locale.ROOT) + "-" + index + ".txt");
    }

    private static Type type(SplittableRandom random) {
        int roll = random.nextInt(TOTAL_WEIGHT);
        for (Type type : TYPES) {
            roll -= type.weight();
            if (roll < 0) {
                return type;
            }
        }
        throw new IllegalStateException();
    }

    private static String fill(String template, Map<String, String> values) {
        String result = template;
        for (Map.Entry<String, String> value : values.entrySet()) {
            result = result.replace("{" + value.getKey() + "}", value.getValue());
        }
        return result;
    }

    private static <T> T pick(SplittableRandom random, List<T> values) {
        return values.get(random.nextInt(values.size()));
    }
}
