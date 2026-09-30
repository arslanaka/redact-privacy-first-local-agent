package dev.privacyagent.detection;

/** PII categories. Must stay in sync with the AI service's PiiType enum. */
public enum PiiType {
    PERSON,
    EMAIL,
    PHONE,
    ADDRESS,
    IBAN,
    CREDIT_CARD,
    ID_NUMBER,
    DATE_OF_BIRTH,
    ORGANIZATION,
    LOCATION,
    OTHER;

    /** Categories that identify accounts or official records; any of these makes a document HIGH risk. */
    public boolean isSensitiveIdentifier() {
        return this == IBAN || this == CREDIT_CARD || this == ID_NUMBER || this == DATE_OF_BIRTH;
    }

    /** Categories that are often not personal data on their own and deserve a closer look. */
    public boolean isContextDependent() {
        return this == LOCATION || this == ORGANIZATION || this == OTHER;
    }
}
