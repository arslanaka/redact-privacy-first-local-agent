package dev.privacyagent.detection;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deterministic, conservative rules for structured PII: email, IBAN, payment card and phone numbers.
 *
 * <p>Conservative means: prefer missing an odd format over flagging random numbers. IBANs must pass
 * the ISO 13616 mod-97 checksum, card numbers the Luhn check, and phone numbers need a recognisable
 * shape (international prefix, trunk "0", or US-style grouping) and 8-15 digits.
 *
 * <p>Rules run in priority order (email, IBAN, card, phone); a later rule never claims a span that
 * overlaps an earlier match, so the digits inside an IBAN are not also reported as a phone number.
 */
@Component
public class RegexPiiDetector {

    static final double EMAIL_CONFIDENCE = 0.97;
    static final double IBAN_CONFIDENCE = 0.99;
    static final double CARD_CONFIDENCE = 0.97;
    static final double PHONE_INTERNATIONAL_CONFIDENCE = 0.92;
    static final double PHONE_NATIONAL_CONFIDENCE = 0.85;

    private static final Pattern EMAIL = Pattern.compile(
            "(?<![\\w.+\\-])[A-Za-z0-9._%+\\-]+@[A-Za-z0-9\\-]+(?:\\.[A-Za-z0-9\\-]+)*\\.[A-Za-z]{2,24}(?![\\w\\-])");

    /** Country code, check digits, then groups of up to 4 alphanumerics, optionally space-separated. */
    private static final Pattern IBAN = Pattern.compile(
            "\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,4})?\\b");

    private static final Pattern CARD = Pattern.compile("(?<![\\d\\-])(?:\\d[ \\-]?){12,18}\\d(?![\\d\\-])");

    private static final Pattern PHONE_INTERNATIONAL = Pattern.compile(
            "(?<![\\w+])\\+\\d{1,3}[ .\\-]?(?:\\(0\\)[ .\\-]?)?(?:\\(\\d{1,4}\\)[ .\\-]?)?\\d{1,5}(?:[ .\\-]?\\d{2,5}){1,4}(?!\\d)");

    private static final Pattern PHONE_NATIONAL = Pattern.compile(
            "(?<![\\w/.\\-])(?:0\\d{2,4}[ /\\-]\\d{3,4}(?:[ \\-]?\\d{2,5}){0,2}|\\(0\\d{2,4}\\) ?\\d{3,4}[ \\-]?\\d{2,5}"
                    + "|\\(\\d{3}\\) ?\\d{3}-\\d{4}|\\b\\d{3}-\\d{3}-\\d{4})(?![\\w/\\-]|\\.\\d)");

    public List<Detection> detect(String text) {
        List<Detection> found = new ArrayList<>();
        addEmails(text, found);
        addIbans(text, found);
        addCards(text, found);
        addPhones(text, PHONE_INTERNATIONAL, PHONE_INTERNATIONAL_CONFIDENCE, "international phone format", found);
        addPhones(text, PHONE_NATIONAL, PHONE_NATIONAL_CONFIDENCE, "national phone format", found);
        found.sort(Comparator.comparingInt(Detection::start));
        return found;
    }

    private void addEmails(String text, List<Detection> out) {
        Matcher m = EMAIL.matcher(text);
        while (m.find()) {
            addIfFree(out, Detection.regex(m.group(), PiiType.EMAIL, EMAIL_CONFIDENCE, m.start(), m.end(),
                    "email address pattern"));
        }
    }

    private void addIbans(String text, List<Detection> out) {
        Matcher m = IBAN.matcher(text);
        while (m.find()) {
            // The greedy group pattern can swallow a following token ("... 00 BIC"); shrink until valid.
            String candidate = m.group();
            int end = m.end();
            while (candidate.replace(" ", "").length() >= 15) {
                if (isValidIban(candidate)) {
                    addIfFree(out, Detection.regex(candidate, PiiType.IBAN, IBAN_CONFIDENCE, m.start(), end,
                            "IBAN checksum valid"));
                    break;
                }
                int cut = candidate.lastIndexOf(' ');
                if (cut < 0) {
                    break;
                }
                candidate = candidate.substring(0, cut);
                end = m.start() + cut;
            }
        }
    }

    private void addCards(String text, List<Detection> out) {
        Matcher m = CARD.matcher(text);
        while (m.find()) {
            String digits = m.group().replaceAll("[ \\-]", "");
            if (digits.length() >= 13 && digits.length() <= 19 && "23456".indexOf(digits.charAt(0)) >= 0
                    && !digits.chars().allMatch(c -> c == digits.charAt(0)) && luhnValid(digits)) {
                addIfFree(out, Detection.regex(m.group(), PiiType.CREDIT_CARD, CARD_CONFIDENCE, m.start(), m.end(),
                        "card number, Luhn checksum valid"));
            }
        }
    }

    private void addPhones(String text, Pattern pattern, double confidence, String note, List<Detection> out) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String value = m.group().strip();
            long digits = value.chars().filter(Character::isDigit).count();
            if (digits >= 8 && digits <= 15) {
                int start = m.start() + m.group().indexOf(value);
                addIfFree(out, Detection.regex(value, PiiType.PHONE, confidence, start, start + value.length(), note));
            }
        }
    }

    private static void addIfFree(List<Detection> out, Detection candidate) {
        if (out.stream().noneMatch(candidate::overlaps)) {
            out.add(candidate);
        }
    }

    /** ISO 13616: move the first four characters to the end, map letters to 10..35, the number mod 97 must be 1. */
    static boolean isValidIban(String raw) {
        String iban = raw.replace(" ", "");
        if (iban.length() < 15 || iban.length() > 34) {
            return false;
        }
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        var numeric = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            if (Character.isDigit(c)) {
                numeric.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                numeric.append(c - 'A' + 10);
            } else {
                return false;
            }
        }
        return new BigInteger(numeric.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    static boolean luhnValid(String digits) {
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }
}
