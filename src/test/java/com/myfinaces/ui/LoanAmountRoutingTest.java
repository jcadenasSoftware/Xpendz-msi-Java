package com.myfinaces.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import myfinances.application.loan.LoanCommandFactory;
import myfinances.domain.loan.commands.CreateLoanCommand;
import myfinances.domain.loan.journal.LoanType;
import org.junit.jupiter.api.Test;

class LoanAmountRoutingTest {

    private final LoanCommandFactory factory = new LoanCommandFactory();

    @Test
    void createLoanUsesCanonicalParserForRepresentativeValues() {
        Map<String, Long> samples = new LinkedHashMap<>();
        samples.put("1", 100L);
        samples.put("100", 10_000L);
        samples.put("1.000", 100_000L);
        samples.put("25.000", 2_500_000L);
        samples.put("250.000", 25_000_000L);
        samples.put("1.000.000", 100_000_000L);
        samples.put("2.500.000", 250_000_000L);
        samples.put("999.999.999,99", 99_999_999_999L);

        samples.forEach((text, expectedCents) -> {
            Long parsed = MoneyInputFormatter.parseToCents(text, MoneyInputFormatter.DEFAULT_LOCALE);
            assertNotNull(parsed, () -> "El parser canónico rechazó: " + text);
            assertEquals(expectedCents, parsed.longValue(), () -> "Parser incorrecto para: " + text);

            CreateLoanCommand command = factory.createLoan(
                "owner-1",
                LoanType.LENT,
                "Persona",
                "COP",
                "account-1",
                parsed,
                1_000L,
                "tx-1",
                null
            );

            assertEquals(expectedCents, command.initialPrincipalCents(), () -> "principalCents incorrecto para: " + text);
            assertEquals("tx-1", command.transactionId());
            assertNotNull(command.envelope().loanId());
        });
    }
}
