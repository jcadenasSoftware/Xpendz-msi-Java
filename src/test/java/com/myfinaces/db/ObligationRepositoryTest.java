package com.myfinaces.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObligationRepositoryTest {

    private static final String UID = "obligation-user";
    private static final long NOW = 1_700_000_000L;

    @TempDir
    Path temporaryDirectory;

    private SqliteDatabase database;
    private AccountRepository accountRepository;
    private CategoryRepository categoryRepository;
    private TransactionRepository transactionRepository;
    private ObligationRepository obligationRepository;
    private ObligationSettlementRepository settlementRepository;
    private String accountId;
    private String transactionCategoryId;
    private String obligationCategoryId;

    @BeforeEach
    void setUp() throws Exception {
        database = new SqliteDatabase(temporaryDirectory.resolve("obligations.db"));
        AppSchema.init(database);
        new UserRepository(database).upsert(UID, "obligation@test.dev");
        accountRepository = new AccountRepository(database);
        categoryRepository = new CategoryRepository(database);
        transactionRepository = new TransactionRepository(database);
        obligationRepository = new ObligationRepository(database);
        settlementRepository = new ObligationSettlementRepository(database);

        accountId = accountRepository.create(UID, "Banco", "BANK", "COP", null).id();
        transactionCategoryId = categoryRepository.create(UID, "Ventas", null, "INCOME", "fas-wallet").id();
        obligationCategoryId = categoryRepository.create(UID, "Clientes", null, "BOTH", "fas-users").id();
    }

    @Test
    void createUpdateCancelAndReadObligation() throws Exception {
        String obligationId = obligationRepository.create(
            UID,
            ObligationRepository.TYPE_RECEIVABLE,
            "Factura pendiente",
            "Pedro",
            "COP",
            500_000L,
            NOW,
            NOW + 86_400L,
            obligationCategoryId,
            "FAC-001",
            "Venta a crédito"
        );

        ObligationRepository.Obligation created = obligationRepository.getByIdOrNull(UID, obligationId);
        assertNotNull(created);
        assertEquals("Pedro", created.counterpartyName());
        assertEquals(obligationCategoryId, created.obligationCategoryId());
        assertEquals(1, obligationRepository.listPendingForSync(UID).size());

        obligationRepository.update(
            UID,
            obligationId,
            ObligationRepository.TYPE_RECEIVABLE,
            "Factura corregida",
            "Pedro Gómez",
            "COP",
            450_000L,
            NOW,
            NOW + 172_800L,
            null,
            obligationCategoryId,
            "FAC-002",
            "Actualizada"
        );

        ObligationRepository.Obligation updated = obligationRepository.getByIdOrNull(UID, obligationId);
        assertNotNull(updated);
        assertEquals("Factura corregida", updated.title());
        assertEquals(450_000L, updated.originalAmountCents());
        assertEquals("FAC-002", updated.reference());

        obligationRepository.cancel(UID, obligationId, NOW + 300L);
        ObligationRepository.Obligation cancelled = obligationRepository.getByIdOrNull(UID, obligationId);
        assertNotNull(cancelled);
        assertEquals(Long.valueOf(NOW + 300L), cancelled.cancelledAtEpochSec());
    }

    @Test
    void settlementPersistsStrictTransactionLinkAndUniqueConstraint() throws Exception {
        String obligationId = obligationRepository.create(
            UID,
            ObligationRepository.TYPE_PAYABLE,
            "Servicio",
            "Proveedor",
            "COP",
            300_000L,
            NOW,
            null,
            null,
            null,
            null
        );
        String transactionId = transactionRepository.create(
            UID,
            accountId,
            transactionCategoryId,
            "EXPENSE",
            100_000L,
            NOW + 1L,
            "Pago asociado"
        );

        String settlementId;
        try (Connection c = database.openConnection()) {
            settlementId = settlementRepository.createDirect(
                c,
                UID,
                obligationId,
                accountId,
                100_000L,
                NOW + 1L,
                transactionId,
                "Abono",
                null
            ).id();
        }

        assertEquals(100_000L, settlementRepository.sumSettledCentsByObligation(UID, obligationId));
        assertEquals(settlementId, settlementRepository.getByLinkedTransactionId(UID, transactionId).id());
        assertEquals(1, settlementRepository.listPendingForSync(UID).size());

        String otherTransactionId = transactionRepository.create(
            UID,
            accountId,
            transactionCategoryId,
            "EXPENSE",
            50_000L,
            NOW + 2L,
            "Otro pago asociado"
        );
        assertNotNull(otherTransactionId);

        assertThrows(SQLException.class, () -> {
            try (Connection c = database.openConnection()) {
                settlementRepository.createDirect(
                    c,
                    UID,
                    obligationId,
                    accountId,
                    50_000L,
                    NOW + 2L,
                    transactionId,
                    "Duplicado",
                    null
                );
            }
        });

        assertThrows(SQLException.class, () -> {
            try (Connection c = database.openConnection()) {
                settlementRepository.createDirect(
                    c,
                    UID,
                    obligationId,
                    accountId,
                    25_000L,
                    NOW + 3L,
                    "tx-missing",
                    "Sin transacción",
                    null
                );
            }
        });
    }
}
