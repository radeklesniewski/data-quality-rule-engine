package io.github.radeklesniewski.dq;

import static io.github.radeklesniewski.dq.rule.Decision.INVALID;
import static io.github.radeklesniewski.dq.rule.Decision.NOT_APPLICABLE;
import static io.github.radeklesniewski.dq.rule.Decision.REVIEW;
import static io.github.radeklesniewski.dq.rule.Decision.VALID;

import io.github.radeklesniewski.dq.record.DataRecord;
import io.github.radeklesniewski.dq.rule.Decision;
import io.github.radeklesniewski.dq.rule.DecisionMapping;
import io.github.radeklesniewski.dq.rule.Rule;
import io.github.radeklesniewski.dq.rule.RuleLogic;
import io.github.radeklesniewski.dq.rule.RuleLogicRegistry;
import io.github.radeklesniewski.dq.rule.RuleStatus;
import io.github.radeklesniewski.dq.rule.Severity;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The scenario from the task brief (§5): three records, three self-contained RELEASED rules. Shared with other
 * modules' tests through Gradle test fixtures.
 *
 * <p>The brief gives {@code vatFormat} and {@code ibanFormat} no default. Every mapping needs one, so they use
 * {@code REVIEW}: a value the logic was never expected to produce goes to a human (assumption, see docs/PLAN.md).
 * The brief gives no severities, so all three rules use {@code ERROR}.
 */
public final class Fixtures {

    private Fixtures() {
    }

    public static final DataRecord R1 = DataRecord.ofMap(Map.of(
            "id", "r1", "vatId", "DE111111111", "country", "DE", "legalName", "ACME GmbH", "iban", "DE123456789"));

    public static final DataRecord R2 = DataRecord.ofMap(Map.of(
            "id", "r2", "vatId", "FR22", "country", "FR", "legalName", "Bricolage SARL", "iban", "FR123456789"));

    public static final DataRecord R3 = DataRecord.ofMap(Map.of(
            "id", "r3", "country", "ZZ", "legalName", "Nowhere Ltd", "iban", ""));

    public static final List<DataRecord> RECORDS = List.of(R1, R2, R3);

    // ------------------------------------------------------------------ logic (computes values only)

    /** "blocked" if country == "ZZ", else "ok". */
    public static final RuleLogic COUNTRY_BLOCKED_LOGIC =
            r -> r.getString("country").filter("ZZ"::equals).isPresent() ? "blocked" : "ok";

    /** "ok" if vatId present and length ≥ 9, else "bad". */
    public static final RuleLogic VAT_FORMAT_LOGIC =
            r -> r.getString("vatId").filter(vat -> vat.length() >= 9).isPresent() ? "ok" : "bad";

    /** "ok" if iban present and starts with country, else "bad". Reads both fields so provenance shows both. */
    public static final RuleLogic IBAN_FORMAT_LOGIC = r -> {
        Optional<String> country = r.getString("country");
        Optional<String> iban = r.getString("iban");
        return country.isPresent() && iban.filter(i -> i.startsWith(country.get())).isPresent() ? "ok" : "bad";
    };

    /** The fixture logic by logic id, for catalogs defined as data. */
    public static final RuleLogicRegistry LOGIC = RuleLogicRegistry.of(Map.of(
            "countryBlocked", COUNTRY_BLOCKED_LOGIC,
            "vatFormat", VAT_FORMAT_LOGIC,
            "ibanFormat", IBAN_FORMAT_LOGIC));

    // ------------------------------------------------------------------ rules (logic + mapping as data)

    /** blocked → INVALID, default → NOT_APPLICABLE. */
    public static final Rule COUNTRY_BLOCKED = Rule.builder("countryBlocked")
            .label("Country is not blocked")
            .logic(COUNTRY_BLOCKED_LOGIC)
            .decisionMapping(DecisionMapping.builder().map("blocked", INVALID).orElse(NOT_APPLICABLE))
            .status(RuleStatus.RELEASED)
            .severity(Severity.ERROR)
            .build();

    /** ok → VALID, bad → INVALID, default → REVIEW. */
    public static final Rule VAT_FORMAT = Rule.builder("vatFormat")
            .label("VAT id has a plausible format")
            .logic(VAT_FORMAT_LOGIC)
            .decisionMapping(DecisionMapping.builder().map("ok", VALID).map("bad", INVALID).orElse(REVIEW))
            .status(RuleStatus.RELEASED)
            .severity(Severity.ERROR)
            .build();

    /** ok → VALID, bad → INVALID, default → REVIEW. */
    public static final Rule IBAN_FORMAT = Rule.builder("ibanFormat")
            .label("IBAN matches the record's country")
            .logic(IBAN_FORMAT_LOGIC)
            .decisionMapping(DecisionMapping.builder().map("ok", VALID).map("bad", INVALID).orElse(REVIEW))
            .status(RuleStatus.RELEASED)
            .severity(Severity.ERROR)
            .build();

    public static final List<Rule> RULES = List.of(COUNTRY_BLOCKED, VAT_FORMAT, IBAN_FORMAT);

    /** Expected decisions of the brief: record id → rule id → decision. */
    public static final Map<String, Map<String, Decision>> EXPECTED = Map.of(
            "r1", Map.of("countryBlocked", NOT_APPLICABLE, "vatFormat", VALID, "ibanFormat", VALID),
            "r2", Map.of("countryBlocked", NOT_APPLICABLE, "vatFormat", INVALID, "ibanFormat", VALID),
            "r3", Map.of("countryBlocked", INVALID, "vatFormat", INVALID, "ibanFormat", INVALID));
}
