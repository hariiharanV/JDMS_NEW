package com.jmr.jdms.worker.capture;

import com.jmr.jdms.contracts.FieldSuggestion;
import com.jmr.jdms.contracts.FieldSuggestion.Confidence;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Suggests upload field values from a document's text (ADR-0024, English): finds a known label followed by a value on
 * the same line ({@code Account No.: 0123 456789}) or on the next line, and reads the value as the field's type.
 * Labels are the standard banking fields' usual English wordings plus each custom field's English label and key.
 * Nothing is guessed without a label. Fixed patterns only, no rules evaluated at run time (CLAUDE.md rule 9).
 *
 * <p>Several labels can share a line; a value ends at the next label, at a column gap (two or more spaces or a tab)
 * or at the end of the line. A value after a separator ({@code :}, {@code #}, {@code -}, {@code =}) is
 * {@link Confidence#HIGH}; a value on the next line, after a label without a separator or after a generic label
 * ({@code Name}, {@code Reference}) is {@link Confidence#MEDIUM}, as is a field with conflicting candidates.
 *
 * <p>Pure and bounded: the text is cut at {@link #MAX_TEXT_CHARS} and lines at {@link #MAX_LINE_CHARS}.
 */
public final class FieldSuggester {

    static final int MAX_TEXT_CHARS = 200_000;
    static final int MAX_LINE_CHARS = 2_000;
    private static final int MAX_LABEL_EVIDENCE = 100;
    private static final int MAX_UNLIMITED_TEXT = 500;
    private static final int MAX_IDENTIFIER = 50;

    enum Kind { IDENTIFIER, ACCOUNT, NAME, DATE, TEXT, NUMBER, BOOLEAN, LOV }

    /** A field to fill: its API name, how its value is read, and the labels that announce it. */
    record Target(String field, Kind kind, int maxLength, Map<String, String> lovValues, List<String> labels,
            List<String> genericLabels) {
    }

    private static final List<Target> STANDARD = List.of(
            standard("cif", Kind.IDENTIFIER, 50, List.of("CIF No", "CIF Number", "CIF ID", "CIF", "Customer No",
                    "Customer Number", "Customer ID", "Customer Id No", "Client ID", "Client No", "Client Number"),
                    List.of()),
            standard("customerName", Kind.NAME, 200, List.of("Customer Name", "Name of Customer", "Account Name",
                    "Account Holder Name", "Account Holder", "Client Name", "Applicant Name", "Name of Applicant",
                    "Borrower Name", "Borrower"), List.of("Name", "Full Name")),
            standard("accountNo", Kind.ACCOUNT, 50, List.of("Account No", "Account Number", "Account #", "A/C No",
                    "A/C Number", "A/C", "Acct No", "Acct Number", "Acc No", "IBAN"), List.of("Account")),
            standard("collateralCode", Kind.IDENTIFIER, 50, List.of("Collateral Code", "Collateral Reference",
                    "Collateral Ref", "Collateral No", "Collateral Number", "Collateral ID"), List.of()),
            standard("contractRef", Kind.IDENTIFIER, 50, List.of("Contract Reference", "Contract Ref", "Contract No",
                    "Contract Number", "Loan Reference", "Loan Ref", "Loan No", "Loan Number", "Loan Account No",
                    "Facility Reference", "Facility Ref", "Facility No"), List.of()),
            standard("transactionRef", Kind.IDENTIFIER, 50, List.of("Transaction Reference", "Transaction Ref",
                    "Transaction No", "Transaction Number", "Transaction ID", "Txn Ref", "Txn No", "Txn ID"),
                    List.of("Reference No", "Reference Number", "Ref No", "Reference")),
            standard("applicationRef", Kind.IDENTIFIER, 50, List.of("Application Reference", "Application Ref",
                    "Application No", "Application Number", "Application ID"), List.of()),
            standard("expiryDate", Kind.DATE, 10, List.of("Expiry Date", "Date of Expiry", "Expiration Date",
                    "Expiry", "Expires On", "Expires", "Valid Until", "Valid Till", "Valid Thru", "Valid To"),
                    List.of()));

    /**
     * Labels printed on identity documents that name no upload field here. Known as labels, they end the value
     * before them and are never taken as a value themselves.
     */
    private static final List<String> DELIMITERS = List.of("Surname", "Given names", "Given name", "Given Name(s)",
            "Nationality", "Sex", "Date of birth", "Place of birth", "Type", "Country code", "Code", "Authority",
            "Date of issue", "Date of expiry", "Place of issue", "Personal No", "Holder's signature", "Signature");

    private static final String SEPARATORS = ":#=\\-\u2013\u2014";
    private static final Pattern COLUMN_GAP = Pattern.compile("\\s{2,}|\t");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9/_.\\-]{0,49}");
    private static final Pattern IBAN = Pattern.compile("[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{2,4}){3,8}");
    private static final Pattern ACCOUNT = Pattern.compile("\\d[\\d \\-]{4,40}\\d");
    private static final Pattern NAME = Pattern.compile("\\p{L}[\\p{L}\\p{M} .'\\-]*");
    private static final Pattern NUMBER = Pattern.compile("[-+]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?");
    private static final Pattern ISO_DATE = Pattern.compile("(?<!\\d)(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})(?!\\d)");
    private static final Pattern NUMERIC_DATE = Pattern.compile(
            "(?<!\\d)(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4}|\\d{2})(?!\\d)");
    private static final String MONTHS = "(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?";
    private static final Pattern DAY_MONTH_YEAR = Pattern.compile(
            "(?<!\\d)(\\d{1,2})(?:st|nd|rd|th)?[\\s\\-]+" + MONTHS + "[\\s\\-,]+(\\d{4})(?!\\d)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MONTH_DAY_YEAR = Pattern.compile(
            "(?<![a-z])" + MONTHS + "\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})(?!\\d)", Pattern.CASE_INSENSITIVE);
    private static final List<String> MONTH_PREFIXES = List.of("jan", "feb", "mar", "apr", "may", "jun", "jul",
            "aug", "sep", "oct", "nov", "dec");

    private final List<Target> targets;
    private final Map<String, List<LabelOf>> byLabel;
    private final Pattern labels;

    private record LabelOf(Target target, boolean generic) {
    }

    private record Candidate(String value, Confidence confidence, String label, int order) {
    }

    /** A suggester for the standard fields and the given custom fields. */
    public FieldSuggester(List<FieldHint> customFields) {
        List<Target> all = new ArrayList<>(STANDARD);
        customFields.forEach(hint -> all.add(custom(hint)));
        this.targets = List.copyOf(all);
        Map<String, List<LabelOf>> index = new LinkedHashMap<>();
        for (Target target : targets) {
            target.labels().forEach(label -> index.computeIfAbsent(normalize(label), key -> new ArrayList<>())
                    .add(new LabelOf(target, false)));
            target.genericLabels().forEach(label -> index.computeIfAbsent(normalize(label), key -> new ArrayList<>())
                    .add(new LabelOf(target, true)));
        }
        DELIMITERS.forEach(label -> index.putIfAbsent(normalize(label), new ArrayList<>()));
        this.byLabel = index;
        String alternatives = index.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed())
                .map(FieldSuggester::labelRegex).collect(Collectors.joining("|"));
        // a label is a whole phrase: not inside a word, optionally followed by a full stop ("No.")
        this.labels = Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + alternatives + ")(?![\\p{L}\\p{N}])\\.?",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** The best value per field, in the order of the form (standard fields first, then custom fields). */
    public List<FieldSuggestion> suggest(String text) {
        String bounded = text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text;
        List<String> lines = bounded.replace("\r\n", "\n").replace('\r', '\n').replace('\u00A0', ' ')
                .replaceAll("[\u200E\u200F\u202A-\u202E\u2066-\u2069]", "")
                .lines().map(line -> line.length() > MAX_LINE_CHARS ? line.substring(0, MAX_LINE_CHARS) : line)
                .toList();
        Map<Target, List<Candidate>> found = new LinkedHashMap<>();
        int order = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            List<MatchResult> matches = labelsIn(line);
            for (int m = 0; m < matches.size(); m++) {
                MatchResult match = matches.get(m);
                int end = m + 1 < matches.size() ? matches.get(m + 1).start() : line.length();
                String after = line.substring(match.end(), end);
                boolean separated = !after.isBlank() && SEPARATORS.indexOf(after.strip().charAt(0)) >= 0;
                String region = separated ? after.strip().substring(1) : after;
                region = untilColumnGap(region.stripLeading()).strip();
                boolean nextLine = false;
                if (region.isEmpty() && m + 1 == matches.size()) {
                    region = nextLineValue(lines, i);
                    nextLine = true;
                }
                if (region.isEmpty() || (!separated && !startsLabel(line.substring(0, match.start())))) {
                    continue; // no value, or a label word in running text (a label needs a separator or a line start)
                }
                for (LabelOf labelOf : byLabel.getOrDefault(normalize(match.label()), List.of())) {
                    if (nextLine && labelOf.target().kind() == Kind.TEXT && !plausibleText(region)) {
                        continue; // a date, another label or OCR debris under a text label is not its value
                    }
                    Optional<String> value = read(labelOf.target(), region);
                    if (value.isPresent()) {
                        Confidence confidence = separated && !nextLine && !labelOf.generic() ? Confidence.HIGH
                                : Confidence.MEDIUM;
                        found.computeIfAbsent(labelOf.target(), key -> new ArrayList<>()).add(new Candidate(
                                value.get(), confidence, evidence(match.label()), order++));
                    }
                }
            }
        }
        MrzReader.read(lines).ifPresent(mrz -> addMrz(mrz, found));
        List<FieldSuggestion> suggestions = new ArrayList<>();
        for (Target target : targets) {
            List<Candidate> candidates = found.get(target);
            if (candidates == null) {
                continue;
            }
            Candidate best = candidates.stream().min(Comparator.comparing((Candidate c) -> c.confidence().ordinal())
                    .thenComparingInt(Candidate::order)).orElseThrow();
            boolean conflict = candidates.stream().anyMatch(c -> !c.value().equals(best.value())
                    && c.confidence() == best.confidence());
            suggestions.add(new FieldSuggestion(target.field(), best.value(),
                    conflict ? Confidence.MEDIUM : best.confidence(), best.label()));
        }
        return suggestions;
    }

    private record MatchResult(int start, int end, String label) {
    }

    /** A text value found on the next line: some Latin letters or digits, not a date, not another "x / y" label. */
    private static boolean plausibleText(String region) {
        return date(region).isEmpty() && region.indexOf('/') < 0
                && region.codePoints().filter(c -> c < 0x250 && Character.isLetterOrDigit(c)).count() >= 3;
    }

    /** Before a label: nothing, or the other language's label and a slash ("जन्म तिथि / Date of Birth"). */
    private static boolean startsLabel(String prefix) {
        String before = prefix.strip();
        return before.isEmpty() || before.endsWith("/");
    }

    private static final String MRZ_EVIDENCE = "Machine-readable zone";

    /**
     * Values from a passport's machine-readable zone, ahead of labelled ones (order -1): name, expiry date, and
     * custom fields for the passport number, nationality and date of birth when the type has them. A value whose
     * check digit holds is HIGH confidence.
     */
    private void addMrz(MrzReader.Mrz mrz, Map<Target, List<Candidate>> found) {
        boolean sound = mrz.documentNumberChecked() && mrz.expiryDateChecked();
        if (!mrz.fullName().isBlank()) {
            standardTarget("customerName").ifPresent(target -> addFirst(found, target, new Candidate(mrz.fullName(),
                    sound ? Confidence.HIGH : Confidence.MEDIUM, MRZ_EVIDENCE, -1)));
        }
        if (mrz.expiryDate() != null) {
            standardTarget("expiryDate").ifPresent(target -> addFirst(found, target, new Candidate(
                    mrz.expiryDate().toString(), mrz.expiryDateChecked() ? Confidence.HIGH : Confidence.MEDIUM,
                    MRZ_EVIDENCE, -1)));
        }
        if (mrz.documentNumber() != null) {
            customTarget("passport", Kind.TEXT).ifPresent(target -> addFirst(found, target, new Candidate(
                    mrz.documentNumber(), mrz.documentNumberChecked() ? Confidence.HIGH : Confidence.MEDIUM,
                    MRZ_EVIDENCE, -1)));
        }
        if (mrz.birthDate() != null) {
            customTarget("birth", Kind.DATE).ifPresent(target -> addFirst(found, target, new Candidate(
                    mrz.birthDate().toString(), mrz.birthDateChecked() ? Confidence.HIGH : Confidence.MEDIUM,
                    MRZ_EVIDENCE, -1)));
        }
        if (mrz.nationality() != null) {
            customTarget("national", Kind.LOV).flatMap(target -> country(mrz.nationality(), target.lovValues())
                    .map(code -> Map.entry(target, code))).ifPresent(entry -> addFirst(found, entry.getKey(),
                    new Candidate(entry.getValue(), sound ? Confidence.HIGH : Confidence.MEDIUM, MRZ_EVIDENCE, -1)));
        }
    }

    private static void addFirst(Map<Target, List<Candidate>> found, Target target, Candidate candidate) {
        found.computeIfAbsent(target, key -> new ArrayList<>()).add(candidate);
    }

    private Optional<Target> standardTarget(String field) {
        return targets.stream().filter(target -> target.field().equals(field)).findFirst();
    }

    /** The first custom field of this kind whose key or English label mentions the word. */
    private Optional<Target> customTarget(String word, Kind kind) {
        return targets.stream().filter(target -> target.field().startsWith("customFields.") && target.kind() == kind
                && target.labels().stream().anyMatch(label -> normalize(label).contains(word))).findFirst();
    }

    /**
     * A nationality from the zone (ISO 3166 alpha-3, e.g. IND) as one of the list's codes: the alpha-3 or alpha-2
     * code itself, or the value whose English label starts with the country's English name (India, Indian).
     */
    static Optional<String> country(String alpha3, Map<String, String> values) {
        String alpha2 = java.util.Arrays.stream(Locale.getISOCountries())
                .filter(code -> {
                    try {
                        return Locale.of("", code).getISO3Country().equals(alpha3);
                    } catch (java.util.MissingResourceException e) {
                        return false;
                    }
                }).findFirst().orElse(null);
        String name = alpha2 == null ? null : Locale.of("", alpha2).getDisplayCountry(Locale.ENGLISH)
                .toLowerCase(Locale.ROOT);
        return values.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(alpha3)
                || entry.getKey().equalsIgnoreCase(alpha2 == null ? "" : alpha2)
                || (name != null && normalize(entry.getValue()).startsWith(name)))
                .map(Map.Entry::getKey).findFirst();
    }

    private List<MatchResult> labelsIn(String line) {
        List<MatchResult> matches = new ArrayList<>();
        Matcher matcher = labels.matcher(line);
        while (matcher.find()) {
            String label = matcher.group();
            matches.add(new MatchResult(matcher.start(), matcher.end(), label.endsWith(".")
                    ? label.substring(0, label.length() - 1) : label));
        }
        return matches;
    }

    /** The value at the start of the next non-blank line, when that line does not start with a label itself. */
    private String nextLineValue(List<String> lines, int index) {
        for (int j = index + 1; j < lines.size() && j <= index + 2; j++) {
            String next = lines.get(j).strip();
            if (next.isEmpty()) {
                continue;
            }
            Matcher matcher = labels.matcher(next);
            if (matcher.find() && matcher.start() == 0) {
                return "";
            }
            String value = matcher.find(0) ? next.substring(0, matcher.start()) : next;
            return untilColumnGap(value).strip();
        }
        return "";
    }

    private static String untilColumnGap(String region) {
        Matcher gap = COLUMN_GAP.matcher(region);
        return gap.find() ? region.substring(0, gap.start()) : region;
    }

    // --- reading values ---

    private static Optional<String> read(Target target, String region) {
        return switch (target.kind()) {
            case IDENTIFIER -> identifier(region);
            case ACCOUNT -> account(region);
            case NAME -> name(region, target.maxLength());
            case DATE -> date(region);
            case NUMBER -> number(region);
            case BOOLEAN -> bool(region);
            case LOV -> lov(region, target.lovValues());
            case TEXT -> text(region, target.maxLength());
        };
    }

    static Optional<String> identifier(String region) {
        Matcher matcher = IDENTIFIER.matcher(region);
        if (!matcher.lookingAt()) {
            return Optional.empty();
        }
        String value = matcher.group().replaceAll("[./\\-_]+$", "");
        return value.chars().anyMatch(Character::isDigit) && value.length() <= MAX_IDENTIFIER
                ? Optional.of(value) : Optional.empty();
    }

    static Optional<String> account(String region) {
        Matcher iban = IBAN.matcher(region);
        if (iban.lookingAt()) {
            return Optional.of(iban.group().replace(" ", ""));
        }
        Matcher digits = ACCOUNT.matcher(region);
        if (digits.lookingAt()) {
            String value = digits.group().replace(" ", "");
            if (value.chars().filter(Character::isDigit).count() >= 6 && value.length() <= MAX_IDENTIFIER) {
                return Optional.of(value);
            }
        }
        // an account "number" with letters, e.g. 0101-AB-123456
        return identifier(region).filter(value -> value.chars().filter(Character::isDigit).count() >= 6);
    }

    static Optional<String> name(String region, int maxLength) {
        Matcher matcher = NAME.matcher(region);
        if (!matcher.lookingAt()) {
            return Optional.empty();
        }
        String value = matcher.group().replaceAll("\\s+", " ").replaceAll("[ .'\\-]+$", "").strip();
        long letters = value.codePoints().filter(Character::isLetter).count();
        return letters >= 2 && value.length() <= maxLength ? Optional.of(value) : Optional.empty();
    }

    /** The first date in the region, as {@code yyyy-MM-dd}; numeric dates are read day first (dd/MM/yyyy). */
    static Optional<String> date(String region) {
        Matcher iso = ISO_DATE.matcher(region);
        if (iso.find() && iso.start() < 3) {
            return valid(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)),
                    Integer.parseInt(iso.group(3)));
        }
        Matcher numeric = NUMERIC_DATE.matcher(region);
        if (numeric.find() && numeric.start() < 3) {
            int first = Integer.parseInt(numeric.group(1));
            int second = Integer.parseInt(numeric.group(2));
            int year = year(numeric.group(3));
            // day first, as banks in the region write dates; month first only when the second part cannot be a month
            return second > 12 ? valid(year, first, second) : valid(year, second, first);
        }
        Matcher dayMonth = DAY_MONTH_YEAR.matcher(region);
        if (dayMonth.find() && dayMonth.start() < 3) {
            return valid(Integer.parseInt(dayMonth.group(3)), month(dayMonth.group(2)),
                    Integer.parseInt(dayMonth.group(1)));
        }
        Matcher monthDay = MONTH_DAY_YEAR.matcher(region);
        if (monthDay.find() && monthDay.start() < 3) {
            return valid(Integer.parseInt(monthDay.group(3)), month(monthDay.group(1)),
                    Integer.parseInt(monthDay.group(2)));
        }
        return Optional.empty();
    }

    private static int year(String digits) {
        int year = Integer.parseInt(digits);
        return digits.length() == 2 ? 2000 + year : year;
    }

    private static int month(String name) {
        return MONTH_PREFIXES.indexOf(name.substring(0, 3).toLowerCase(Locale.ROOT)) + 1;
    }

    private static Optional<String> valid(int year, int month, int day) {
        if (year < 1900 || year > 2200) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.of(year, month, day).toString());
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    /** A number, ignoring a currency code or symbol around it and thousands separators. */
    static Optional<String> number(String region) {
        String stripped = region.replaceFirst("^(?:[A-Za-z]{3}|[$\u20AC\u00A3])\\s*", "");
        Matcher matcher = NUMBER.matcher(stripped);
        if (!matcher.lookingAt()) {
            return Optional.empty();
        }
        return Optional.of(new BigDecimal(matcher.group().replace(",", "").replace("+", "")).toPlainString());
    }

    static Optional<String> bool(String region) {
        String word = region.strip().split("\\s+")[0].toLowerCase(Locale.ROOT).replaceAll("[.,;]$", "");
        return switch (word) {
            case "yes", "y", "true" -> Optional.of("true");
            case "no", "n", "false" -> Optional.of("false");
            default -> Optional.empty();
        };
    }

    /** A list value named by its code or English label at the start of the region; the code is suggested. */
    static Optional<String> lov(String region, Map<String, String> values) {
        String normalized = normalize(region);
        return values.entrySet().stream()
                .flatMap(entry -> java.util.stream.Stream.of(
                        Map.entry(normalize(entry.getKey()), entry.getKey()),
                        Map.entry(normalize(entry.getValue()), entry.getKey())))
                .filter(entry -> !entry.getKey().isEmpty() && (normalized.equals(entry.getKey())
                        || normalized.startsWith(entry.getKey() + " ")))
                .max(Comparator.comparingInt(entry -> entry.getKey().length()))
                .map(Map.Entry::getValue);
    }

    static Optional<String> text(String region, int maxLength) {
        String value = region.replaceAll("\\s+", " ").strip();
        return !value.isEmpty() && value.codePointCount(0, value.length()) <= maxLength ? Optional.of(value)
                : Optional.empty();
    }

    // --- labels ---

    private static Target standard(String field, Kind kind, int maxLength, List<String> labels,
            List<String> generic) {
        return new Target(field, kind, maxLength, Map.of(), labels, generic);
    }

    private static Target custom(FieldHint hint) {
        Kind kind = switch (hint.dataType()) {
            case "NUMBER" -> Kind.NUMBER;
            case "DATE" -> Kind.DATE;
            case "BOOLEAN" -> Kind.BOOLEAN;
            case "LOV" -> Kind.LOV;
            default -> Kind.TEXT;
        };
        int max = hint.maxLength() == null ? MAX_UNLIMITED_TEXT : Math.min(hint.maxLength(), MAX_UNLIMITED_TEXT);
        List<String> labels = new ArrayList<>();
        if (hint.labelEn() != null && !hint.labelEn().isBlank()) {
            labels.add(hint.labelEn());
        }
        String fromKey = hint.key().replace('_', ' ');
        if (labels.stream().noneMatch(label -> normalize(label).equals(normalize(fromKey)))) {
            labels.add(fromKey);
        }
        for (String label : List.copyOf(labels)) {
            String normalized = normalize(label);
            Matcher trailing = Pattern.compile("^(.+) date$").matcher(normalized);
            Matcher leading = Pattern.compile("^date of (.+)$").matcher(normalized);
            String variant = trailing.matches() ? "date of " + trailing.group(1)
                    : leading.matches() ? leading.group(1) + " date" : null;
            if (variant != null && labels.stream().noneMatch(existing -> normalize(existing).equals(variant))) {
                labels.add(variant);
            }
        }
        return new Target("customFields." + hint.key(), kind, max, hint.lovValues(), labels, List.of());
    }

    /** Lower case, single spaces, no full stops: how labels are compared. */
    static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace(".", "").replaceAll("\\s+", " ").strip();
    }

    /** A label as a pattern: its words with any spacing (and optional full stops) between them. */
    private static String labelRegex(String normalized) {
        return java.util.Arrays.stream(normalized.split(" ")).map(Pattern::quote)
                .collect(Collectors.joining("\\.?\\s*"));
    }

    private static String evidence(String label) {
        String value = label.replaceAll("\\s+", " ").strip();
        return value.length() > MAX_LABEL_EVIDENCE ? value.substring(0, MAX_LABEL_EVIDENCE) : value;
    }
}
