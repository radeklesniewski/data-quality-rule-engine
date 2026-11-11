package io.github.radeklesniewski.dq;

import static io.github.radeklesniewski.dq.rule.Decision.INVALID;
import static io.github.radeklesniewski.dq.rule.Decision.NOT_APPLICABLE;
import static io.github.radeklesniewski.dq.rule.Decision.REVIEW;
import static io.github.radeklesniewski.dq.rule.Decision.VALID;

import io.github.radeklesniewski.dq.record.DataRecord;
import io.github.radeklesniewski.dq.rule.DecisionMapping;
import io.github.radeklesniewski.dq.rule.Rule;
import io.github.radeklesniewski.dq.rule.RuleStatus;
import io.github.radeklesniewski.dq.rule.Severity;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The scenario from the task brief (§5): three records, three self-contained RELEASED rules.
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

    /** "blocked" if country == "ZZ", else "ok". blocked → INVALID, default → NOT_APPLICABLE. */
    public static final Rule COUNTRY_BLOCKED = Rule.builder("countryBlocked")
            .label("Country is not blocked")
            .logic(r -> r.getString("country").filter("ZZ"::equals).isPresent() ? "blocked" : "ok")
            .decisionMapping(DecisionMapping.builder().map("blocked", INVALID).orElse(NOT_APPLICABLE))
            .status(RuleStatus.RELEASED)
            .severity(Severity.ERROR)
            .build();

    /** "ok" if vatId present and length ≥ 9, else "bad". ok → VALID, bad → INVALID. */
    public static final Rule VAT_FORMAT = Rule.builder("vatFormat")
            .label("VAT id has a plausible format")
            .logic(r -> r.getString("vatId").filter(vat -> vat.length() >= 9).isPresent() ? "ok" : "bad")
            .decisionMapping(DecisionMapping.builder().map("ok", VALID).map("bad", INVALID).orElse(REVIEW))
            .status(RuleStatus.RELEASED)
            .severity(Severity.ERROR)
            .build();

    /** "ok" if iban present and starts with country, else "bad". ok → VALID, bad → INVALID. */
    public static final Rule IBAN_FORMAT = Rule.builder("ibanFormat")
            .label("IBAN matches the record's country")
            .logic(r -> {
                // Read both fields up front so provenance always shows both, whatever the outcome.
                Optional<String> country = r.getString("country");
                Optional<String> iban = r.getString("iban");
                return country.isPresent() && iban.filter(i -> i.startsWith(country.get())).isPresent() ? "ok" : "bad";
            })
            .decisionMapping(DecisionMapping.builder().map("ok", VALID).map("bad", INVALID).orElse(REVIEW))
            .status(RuleStatus.RELEASED)
            .severity(Severity.ERROR)
            .build();

    public static final List<Rule> RULES = List.of(COUNTRY_BLOCKED, VAT_FORMAT, IBAN_FORMAT);
}
