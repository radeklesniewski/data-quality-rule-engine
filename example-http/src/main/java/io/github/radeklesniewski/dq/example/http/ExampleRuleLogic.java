package io.github.radeklesniewski.dq.example.http;

import io.github.radeklesniewski.dq.rule.RuleLogic;
import io.github.radeklesniewski.dq.rule.RuleLogicRegistry;
import java.util.Map;
import java.util.Optional;

/**
 * The host's rule code. Logic computes values only. Which value means which decision, and which rules exist at all,
 * is data in the catalog ({@code example-catalog.json}).
 */
final class ExampleRuleLogic {

    private ExampleRuleLogic() {
    }

    static final RuleLogic COUNTRY_BLOCKED =
            r -> r.getString("country").filter("ZZ"::equals).isPresent() ? "blocked" : "ok";

    static final RuleLogic VAT_FORMAT =
            r -> r.getString("vatId").filter(vat -> vat.length() >= 9).isPresent() ? "ok" : "bad";

    static final RuleLogic IBAN_FORMAT = r -> {
        Optional<String> country = r.getString("country");
        Optional<String> iban = r.getString("iban");
        return country.isPresent() && iban.filter(i -> i.startsWith(country.get())).isPresent() ? "ok" : "bad";
    };

    /** German VAT ids start with "DE". Scoped to DE in the catalog. */
    static final RuleLogic DE_VAT_PREFIX = r -> r.getString("vatId").filter(v -> v.startsWith("DE")).isPresent()
            ? "ok" : "bad";

    /** A required field: a missing legal name is a fault (rule error), not a judgement. */
    static final RuleLogic LEGAL_NAME_PRESENT = r -> r.requireString("legalName").isBlank() ? "blank" : "ok";

    static final RuleLogicRegistry REGISTRY = RuleLogicRegistry.of(Map.of(
            "countryBlocked", COUNTRY_BLOCKED,
            "vatFormat", VAT_FORMAT,
            "ibanFormat", IBAN_FORMAT,
            "deVatPrefix", DE_VAT_PREFIX,
            "legalNamePresent", LEGAL_NAME_PRESENT));
}
