package com.jmr.jdms.worker.capture;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the machine-readable zone of a passport (ICAO 9303 TD3: two lines of 44 characters) from OCR text (B-15
 * follow-up, ADR-0024). OCR of the zone is usually good but not perfect, so the reader is tolerant: spaces are
 * ignored, a stray character between the document number's check digit and the nationality is skipped, and filler
 * misread as {@code K} or {@code C} after the names is dropped. The check digits decide how far a value is trusted.
 */
final class MrzReader {

    /** What the zone says; a field is null when it could not be read. */
    record Mrz(String surname, String givenNames, String issuingState, String documentNumber,
            boolean documentNumberChecked, String nationality, LocalDate birthDate, boolean birthDateChecked,
            String sex, LocalDate expiryDate, boolean expiryDateChecked) {

        /** Given names then surname, as a bank writes a customer's name. */
        String fullName() {
            return String.join(" ", java.util.stream.Stream.of(givenNames, surname)
                    .filter(part -> part != null && !part.isBlank()).toList());
        }
    }

    private static final Pattern NAME_LINE = Pattern.compile("P[A-Z<]([A-Z<]{3})([A-Z<]+)");
    /** Document number, check, nationality, birth date, check, sex, expiry, check. */
    private static final String D = "[0-9OQDILSBZG]";
    private static final Pattern DATA_LINE = Pattern.compile(
            "([A-Z0-9<]{9})(" + D + ")([A-Z0-9]{3})(" + D + "{6})(" + D + ")([MFX<])(" + D + "{6})(" + D + ")");
    /** The same with one stray character after the document number's check digit (a common OCR slip). */
    private static final Pattern DATA_LINE_STRAY = Pattern.compile(
            "([A-Z0-9<]{9})(" + D + ")[0-9A-Z]([A-Z0-9]{3})(" + D + "{6})(" + D + ")([MFX<])(" + D + "{6})(" + D + ")");
    private static final int[] WEIGHTS = {7, 3, 1};

    private MrzReader() {
    }

    static Optional<Mrz> read(List<String> lines) {
        Matcher data = null;
        Matcher names = null;
        for (String raw : lines) {
            String line = raw.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
            if (names == null && line.startsWith("P") && line.contains("<<")) {
                Matcher candidate = NAME_LINE.matcher(line);
                if (candidate.lookingAt()) {
                    names = candidate;
                    continue;
                }
            }
            if (data == null && line.length() >= 28) {
                data = bestDataLine(line);
            }
        }
        if (data == null) {
            return Optional.empty();
        }
        String number = data.group(1).replace("<", "");
        boolean numberChecked = check(data.group(1)) == digit(data.group(2));
        String birthDigits = digits(data.group(4));
        String expiryDigits = digits(data.group(7));
        LocalDate birth = date(birthDigits, false);
        LocalDate expiry = date(expiryDigits, true);
        String surname = null;
        String given = null;
        String state = null;
        if (names != null) {
            state = names.group(1).replace("<", "");
            String[] parts = names.group(2).split("<<", 2);
            surname = name(parts[0]);
            given = parts.length > 1 ? name(parts[1].split("<<")[0]) : null;
        }
        return Optional.of(new Mrz(surname, given, state, number.isEmpty() ? null : number, numberChecked,
                letters(data.group(3)), birth, birth != null && check(birthDigits) == digit(data.group(5)),
                data.group(6).replace("<", ""), expiry, expiry != null && check(expiryDigits) == digit(data.group(8))));
    }

    /**
     * The alignment of the data line whose check digits hold best (document number, birth date, expiry), the exact
     * layout before the one with a stray character, the earliest on a tie; null when nothing fits.
     */
    private static Matcher bestDataLine(String line) {
        Matcher best = null;
        int bestScore = -1;
        for (Pattern pattern : List.of(DATA_LINE, DATA_LINE_STRAY)) {
            for (int start = 0; start + 28 <= line.length(); start++) {
                Matcher candidate = pattern.matcher(line);
                candidate.region(start, line.length());
                if (!candidate.lookingAt()) {
                    continue;
                }
                int score = (check(candidate.group(1)) == digit(candidate.group(2)) ? 1 : 0)
                        + (check(digits(candidate.group(4))) == digit(candidate.group(5)) ? 1 : 0)
                        + (check(digits(candidate.group(7))) == digit(candidate.group(8)) ? 1 : 0)
                        + (candidate.group(3).chars().allMatch(Character::isLetter) ? 1 : 0);
                if (score > bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    /** Names separated by single fillers; filler misread as K or C at the end is not a name. */
    private static String name(String field) {
        List<String> words = Arrays.stream(field.split("<+")).filter(word -> !word.isEmpty())
                .takeWhile(word -> !word.matches("[KC]+")).toList();
        String joined = String.join(" ", words).strip();
        return joined.isEmpty() ? null : joined;
    }

    /** YYMMDD; expiry dates are in this century, birth dates in the past. */
    private static LocalDate date(String yymmdd, boolean expiry) {
        int yy = Integer.parseInt(yymmdd.substring(0, 2));
        int year = expiry ? 2000 + yy : (2000 + yy > LocalDate.now().getYear() ? 1900 + yy : 2000 + yy);
        try {
            return LocalDate.of(year, Integer.parseInt(yymmdd.substring(2, 4)), Integer.parseInt(yymmdd.substring(4)));
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** ICAO 9303 check digit: weights 7, 3, 1; digits as themselves, A-Z as 10-35, filler as 0. */
    static int check(String field) {
        int sum = 0;
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            int value = Character.isDigit(c) ? c - '0' : Character.isLetter(c) ? c - 'A' + 10 : 0;
            sum += value * WEIGHTS[i % 3];
        }
        return sum % 10;
    }

    private static int digit(String text) {
        return digits(text).charAt(0) - '0';
    }

    /** Where only digits belong, letters OCR confuses with digits are read as those digits (O→0, I→1, S→5, ...). */
    static String digits(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            out.append(switch (c) {
                case 'O', 'Q', 'D' -> '0';
                case 'I', 'L' -> '1';
                case 'Z' -> '2';
                case 'S' -> '5';
                case 'G' -> '6';
                case 'B' -> '8';
                default -> c;
            });
        }
        return out.toString();
    }

    /** Where only letters belong (country codes), digits OCR confuses with letters are read as letters. */
    static String letters(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            out.append(switch (c) {
                case '0' -> 'O';
                case '1' -> 'I';
                case '2' -> 'Z';
                case '5' -> 'S';
                case '8' -> 'B';
                default -> c;
            });
        }
        return out.toString();
    }
}
