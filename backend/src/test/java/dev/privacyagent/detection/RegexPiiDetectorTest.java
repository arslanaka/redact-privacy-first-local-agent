package dev.privacyagent.detection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RegexPiiDetectorTest {

    private final RegexPiiDetector detector = new RegexPiiDetector();

    private List<Detection> detect(String text) {
        return detector.detect(text);
    }

    private static Detection only(List<Detection> ds) {
        assertThat(ds).hasSize(1);
        return ds.getFirst();
    }

    @Test
    void detectsEmailWithExactOffsets() {
        String text = "Contact john.smith+billing@example.co.uk today.";
        Detection d = only(detect(text));
        assertThat(d.type()).isEqualTo(PiiType.EMAIL);
        assertThat(d.text()).isEqualTo("john.smith+billing@example.co.uk");
        assertThat(text.substring(d.start(), d.end())).isEqualTo(d.text());
        assertThat(d.source()).isEqualTo(DetectionSource.REGEX);
        assertThat(d.confidence()).isEqualTo(RegexPiiDetector.EMAIL_CONFIDENCE);
    }

    @Test
    void detectsValidIbanOnlyWhenChecksumPasses() {
        Detection d = only(detect("IBAN: DE89 3704 0044 0532 0130 00 BIC COBADEFFXXX"));
        assertThat(d.type()).isEqualTo(PiiType.IBAN);
        assertThat(d.text()).isEqualTo("DE89 3704 0044 0532 0130 00");
        assertThat(detect("IBAN: DE89 3704 0044 0532 0130 01")).noneMatch(x -> x.type() == PiiType.IBAN);
        assertThat(only(detect("GB82WEST12345698765432")).type()).isEqualTo(PiiType.IBAN);
    }

    @Test
    void ibanDigitsAreNotAlsoReportedAsPhoneOrCard() {
        assertThat(detect("Pay to DE89 3704 0044 0532 0130 00.")).extracting(Detection::type).containsExactly(PiiType.IBAN);
    }

    @Test
    void detectsLuhnValidCardsOnly() {
        assertThat(only(detect("Card 4111 1111 1111 1111 exp 12/27")).type()).isEqualTo(PiiType.CREDIT_CARD);
        assertThat(only(detect("Card 5555-5555-5555-4444")).type()).isEqualTo(PiiType.CREDIT_CARD);
        assertThat(detect("Card 4111 1111 1111 1112")).noneMatch(x -> x.type() == PiiType.CREDIT_CARD);
        assertThat(detect("Order 1234567890123")).noneMatch(x -> x.type() == PiiType.CREDIT_CARD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"+49 30 1234 5678", "+44 20 7946 0958", "+1 (415) 555-0132", "030 12345678",
            "0151 23456789", "(555) 123-4567", "555-123-4567", "+49 (0)30 1234567"})
    void detectsPhoneNumbers(String phone) {
        String text = "Call " + phone + " now";
        Detection d = only(detect(text));
        assertThat(d.type()).isEqualTo(PiiType.PHONE);
        assertThat(d.text()).isEqualTo(phone);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Callback: (555) 010-4477.", "Call 030 12345678, thanks", "Mobile: 0151 23456789.",
            "Reach me on +44 20 7946 0958."})
    void detectsPhoneNumbersFollowedByPunctuation(String text) {
        assertThat(only(detect(text)).type()).isEqualTo(PiiType.PHONE);
    }

    @Test
    void decimalNumbersAreNotPhones() {
        assertThat(detect("Measured 030 1234.56 units")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Date of birth 14/07/1986", "Invoice INV-2024-0042", "Issued 2024-01-15",
            "Total 1,299.00 EUR", "10969 Berlin", "Version 3.5.16", "Room 0042", "Ticket #48213"})
    void ignoresNumbersThatAreNotPhones(String text) {
        assertThat(detect(text)).isEmpty();
    }

    @Test
    void resultsAreSortedAndNonOverlapping() {
        var ds = detect("mail a@b.io or call +49 30 1234 5678, IBAN DE89 3704 0044 0532 0130 00");
        assertThat(ds).extracting(Detection::type).containsExactly(PiiType.EMAIL, PiiType.PHONE, PiiType.IBAN);
        for (int i = 1; i < ds.size(); i++) {
            assertThat(ds.get(i).start()).isGreaterThanOrEqualTo(ds.get(i - 1).end());
        }
    }

    @Test
    void checksumHelpers() {
        assertThat(RegexPiiDetector.isValidIban("DE89370400440532013000")).isTrue();
        assertThat(RegexPiiDetector.isValidIban("DE00370400440532013000")).isFalse();
        assertThat(RegexPiiDetector.luhnValid("79927398713")).isTrue();
        assertThat(RegexPiiDetector.luhnValid("79927398710")).isFalse();
    }
}
