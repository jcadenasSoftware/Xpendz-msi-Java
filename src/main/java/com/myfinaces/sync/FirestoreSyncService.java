package com.myfinaces.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myfinaces.auth.AuthSession;
import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.BudgetRepository;
import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.GoalRepository;
import com.myfinaces.db.LoanPaymentRepository;
import com.myfinaces.db.LoanRepository;
import com.myfinaces.db.TransactionRepository;
import com.myfinaces.db.TransferRepository;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class FirestoreSyncService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final String projectId;

    public FirestoreSyncService(String projectId) {
        this.http = HttpClient.newHttpClient();
        this.projectId = projectId;
    }

    public void deleteGoal(AuthSession session, String goalId) throws Exception {
        String url = "https://firestore.googleapis.com/v1/projects/" + urlEncode(projectId)
            + "/databases/(default)/documents/users/" + urlEncode(session.uid())
            + "/goals/" + urlEncode(goalId);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + session.idToken())
            .DELETE()
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Firestore delete goal failed (" + resp.statusCode() + "): " + resp.body());
        }
    }

    public void syncAccounts(AuthSession session, AccountRepository accountRepo) throws Exception {
        List<AccountRepository.Account> accounts = accountRepo.list(session.uid());
        System.out.println("[FirestoreSync] accounts=" + accounts.size());
        for (AccountRepository.Account a : accounts) {
            upsertAccount(session, a);
        }
    }

    public void syncGoals(AuthSession session, GoalRepository goalRepo) throws Exception {
        List<GoalRepository.Goal> goals = goalRepo.listByUser(session.uid());
        System.out.println("[FirestoreSync] goals=" + goals.size());
        for (GoalRepository.Goal g : goals) {
            upsertGoal(session, g);
        }
    }

    public void syncBudgets(AuthSession session, BudgetRepository budgetRepo) throws Exception {
        List<BudgetRepository.Budget> budgets = budgetRepo.listByUser(session.uid());
        System.out.println("[FirestoreSync] budgets=" + budgets.size());
        for (BudgetRepository.Budget b : budgets) {
            upsertBudget(session, b);
        }
    }

    public void syncAccount(AuthSession session, AccountRepository.Account account) throws Exception {
        upsertAccount(session, account);
    }

    public void syncGoal(AuthSession session, GoalRepository.Goal goal) throws Exception {
        upsertGoal(session, goal);
    }

    public void syncBudget(AuthSession session, BudgetRepository.Budget budget) throws Exception {
        upsertBudget(session, budget);
    }

    public void deleteBudget(AuthSession session, String budgetId) throws Exception {
        String url = "https://firestore.googleapis.com/v1/projects/" + urlEncode(projectId)
            + "/databases/(default)/documents/users/" + urlEncode(session.uid())
            + "/budgets/" + urlEncode(budgetId);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + session.idToken())
            .DELETE()
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Firestore delete budget failed (" + resp.statusCode() + "): " + resp.body());
        }
    }

    public void deleteAccount(AuthSession session, String accountId) throws Exception {
        String url = "https://firestore.googleapis.com/v1/projects/" + urlEncode(projectId)
            + "/databases/(default)/documents/users/" + urlEncode(session.uid())
            + "/accounts/" + urlEncode(accountId);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + session.idToken())
            .DELETE()
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Firestore delete account failed (" + resp.statusCode() + "): " + resp.body());
        }
    }

    public void syncCategories(AuthSession session, CategoryRepository categoryRepo) throws Exception {
        List<CategoryRepository.Category> categories = categoryRepo.listAll(session.uid());
        System.out.println("[FirestoreSync] categories=" + categories.size());
        for (CategoryRepository.Category c : categories) {
            upsertCategory(session, c);
        }
    }

    public void syncCategory(AuthSession session, CategoryRepository.Category category) throws Exception {
        upsertCategory(session, category);
    }

    public void deleteCategory(AuthSession session, String categoryId) throws Exception {
        String url = "https://firestore.googleapis.com/v1/projects/" + urlEncode(projectId)
            + "/databases/(default)/documents/users/" + urlEncode(session.uid())
            + "/categories/" + urlEncode(categoryId);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + session.idToken())
            .DELETE()
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Firestore delete category failed (" + resp.statusCode() + "): " + resp.body());
        }
    }

    public List<CategoryRepository.Category> pullCategories(AuthSession session) throws Exception {
        String baseUrl = "https://firestore.googleapis.com/v1/projects/" + urlEncode(projectId)
            + "/databases/(default)/documents/users/" + urlEncode(session.uid())
            + "/categories";
        List<String> pages = pullAllPages(session, baseUrl, 1000);

        List<CategoryRepository.Category> out = new ArrayList<>();
        for (String body : pages) {
            out.addAll(parseCategoriesList(session.uid(), body));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<BudgetRepository.Budget> parseBudgetsList(String userUid, String body) throws Exception {
        Map<String, Object> root = MAPPER.readValue(body, Map.class);
        Object docsObj = root.get("documents");
        if (!(docsObj instanceof List<?> docs)) {
            return List.of();
        }

        long now = Instant.now().getEpochSecond();
        List<BudgetRepository.Budget> out = new java.util.ArrayList<>();
        for (Object d : docs) {
            if (!(d instanceof Map<?, ?> doc)) {
                continue;
            }
            Object nameObj = doc.get("name");
            if (!(nameObj instanceof String fullName) || fullName.isBlank()) {
                continue;
            }
            String id = fullName.substring(fullName.lastIndexOf('/') + 1);

            Object fieldsObj = doc.get("fields");
            if (!(fieldsObj instanceof Map<?, ?> fields)) {
                continue;
            }

            String month = readStringField(fields, "month");
            String categoryId = readStringField(fields, "categoryId");
            Long limitCents = readLongField(fields, "limitCents");
            String currency = readStringField(fields, "currency");

            if (month == null || month.isBlank()) {
                continue;
            }
            if (categoryId == null || categoryId.isBlank()) {
                continue;
            }
            if (limitCents == null) {
                continue;
            }
            if (currency == null || currency.isBlank()) {
                continue;
            }

            Long createdAt = readLongField(fields, "createdAtEpochSec");
            Long updatedAt = readLongField(fields, "updatedAtEpochSec");
            long cAt = createdAt == null ? now : createdAt;
            long uAt = updatedAt == null ? cAt : updatedAt;

            out.add(new BudgetRepository.Budget(
                id,
                userUid,
                month,
                categoryId,
                limitCents,
                currency,
                cAt,
                uAt
            ));
        }

        return out;
    }

    public List<BudgetRepository.Budget> pullBudgets(AuthSession session) throws Exception {
        String baseUrl = "https://firestore.googleapis.com/v1/projects/" + urlEncode(projectId)
            + "/databases/(default)/documents/users/" + urlEncode(session.uid())
            + "/budgets";
        List<String> pages = pullAllPages(session, baseUrl, 1000);

        List<BudgetRepository.Budget> out = new ArrayList<>();
        for (String body : pages) {
            out.addAll(parseBudgetsList(session.uid(), body));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<LoanPaymentRepository.LoanPayment> parseLoanPaymentsList(String userUid, String body) throws Exception {
        Map<String, Object> root = MAPPER.readValue(body, Map.class);
        Object docsObj = root.get("documents");
        if (!(docsObj instanceof List<?> docs)) {
            return List.of();
        }

        long now = Instant.now().getEpochSecond();
        List<LoanPaymentRepository.LoanPayment> out = new java.util.ArrayList<>();
        for (Object d : docs) {
            if (!(d instanceof Map<?, ?> doc)) {
                continue;
            }
            Object nameObj = doc.get("name");
            if (!(nameObj instanceof String fullName) || fullName.isBlank()) {
                continue;
            }
            String id = fullName.substring(fullName.lastIndexOf('/') + 1);

            Object fieldsObj = doc.get("fields");
            if (!(fieldsObj instanceof Map<?, ?> fields)) {
                continue;
            }

            String loanId = readStringField(fields, "loanId");
            String accountId = readStringField(fields, "accountId");
            Long principalCents = readLongField(fields, "principalCents");
            Long occurredAt = readLongField(fields, "occurredAtEpochSec");

            if (loanId == null || loanId.isBlank()) {
                continue;
            }
            if (accountId == null || accountId.isBlank()) {
                continue;
            }
            if (principalCents == null) {
                continue;
            }
            if (occurredAt == null) {
                continue;
            }

            String linkedTransactionId = readStringField(fields, "linkedTransactionId");
            String note = readStringField(fields, "note");
            Long createdAt = readLongField(fields, "createdAtEpochSec");
            Long updatedAt = readLongField(fields, "updatedAtEpochSec");
            String updatedBy = readStringField(fields, "updatedBy");

            long cAt = createdAt == null ? now : createdAt;
            long uAt = updatedAt == null ? cAt : updatedAt;

            out.add(new LoanPaymentRepository.LoanPayment(
                id,
                loanId,
                userUid,
                accountId,
                principalCents,
                occurredAt,
                linkedTransactionId,
                note,
                cAt,
                uAt,
                updatedBy
            ));
        }

        return out;
    }

    public List<LoanPaymentRepository.LoanPayment> pullLoanPayments(AuthSession session) throws Exception {
        String baseUrl = "https://firestore.googleapis.com/v1/projects/" + urlEncode(projectId)
            + "/databases/(default)/documents/users/" + urlEncode(session.uid())
            + "/loanPayments";
        List<String> pages = pullAllPages(session, baseUrl, 1000);

        List<LoanPaymentRepository.LoanPayment> out = new ArrayList<>();
        for (String body : pages) {
            out.addAll(parseLoanPaymentsList(session.uid(), body));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<LoanRepository.Loan> parseLoansList(String userUid, String body) throws Exception {
        Map<String, Object> root = MAPPER.readValue(body, Map.class);
        Object docsObj = root.get("documents");
        if (!(docsObj instanceof List<?> docs)) {
            return List.of();
        }

        long now = Instant.now().getEpochSecond();
        List<LoanRepository.Loan> out = new java.util.ArrayList<>();
        for (Object d : docs) {
            if (!(d instanceof Map<?, ?> doc)) {
                continue;
            }
            Object nameObj = doc.get("name");
            if (!(nameObj instanceof String fullName) || fullName.isBlank()) {
                continue;
            }
            String id = fullName.substring(fullName.lastIndexOf('/') + 1);

            Object fieldsObj = doc.get("fields");
            if (!(fieldsObj instanceof Map<?, ?> fields)) {
                continue;
            }

            String type = readStringField(fields, "type");
            String counterpartyName = readStringField(fields, "counterpartyName");
            String accountId = readStringField(fields, "accountId");
            String currency = readStringField(fields, "currency");
            Long principalCents = readLongField(fields, "principalCents");
            String status = readStringField(fields, "status");
            String notes = readStringField(fields, "notes");
            Long occurredAt = readLongField(fields, "occurredAtEpochSec");

            if (type == null || type.isBlank()) {
                continue;
            }
            if (counterpartyName == null || counterpartyName.isBlank()) {
                continue;
            }
            if (currency == null || currency.isBlank()) {
                continue;
            }
            if (principalCents == null) {
                continue;
            }
            if (status == null || status.isBlank()) {
                status = LoanRepository.STATUS_OPEN;
            }

            Long createdAt = readLongField(fields, "createdAtEpochSec");
            Long updatedAt = readLongField(fields, "updatedAtEpochSec");
            String updatedBy = readStringField(fields, "updatedBy");

            long cAt = createdAt == null ? now : createdAt;
            long uAt = updatedAt == null ? cAt : updatedAt;
            long occ = occurredAt == null ? cAt : occurredAt;

            out.add(new LoanRepository.Loan(
                id,
                userUid,
                type,
                counterpartyName,
                accountId,
                principalCents,
                currency,
                status,
                notes,
                occ,
                cAt,
                uAt,
                updatedBy
            ));
        }

        return out;
    }

    public List<LoanRepository.Loan> pullLoans(AuthSession session) throws Exception {
        String baseUrl = "https://firestore.googleapis.com/v1/projects/" + urlEncode(projectId)
            + "/databases/(default)/documents/users/" + urlEncode(session.uid())
            + "/loans";
        List<String> pages = pullAllPages(session, baseUrl, 1000);

        List<LoanRepository.Loan> out = new ArrayList<>();
        for (String body : pages) {
            out.addAll(parseLoansList(session.uid(), body));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<GoalRepository.Goal> parseGoalsList(String userUid, String body) throws Exception {
        Map<String, Object> root = MAPPER.readValue(body, Map.class);
        Object docsObj = root.get("documents");
        if (!(docsObj instanceof List<?> docs)) {
            return List.of();
        }

        long now = Instant.now().getEpochSecond();
        List<GoalRepository.Goal> out = new java.util.ArrayList<>();
        for (Object d : docs) {
            if (!(d instanceof Map<?, ?> doc)) {
                continue;
            }
            Object nameObj = doc.get("name");
            if (!(nameObj instanceof String fullName) || fullName.isBlank()) {
                continue;
            }
            String id = fullName.substring(fullName.lastIndexOf('/') + 1);

            Object fieldsObj = doc.get("fields");
            if (!(fieldsObj instanceof Map<?, ?> fields)) {
                continue;
            }

            String name = readStringField(fields, "name");
            String currency = readStringField(fields, "currency");
            Long targetCents = readLongField(fields, "targetCents");
            Long targetDate = readLongField(fields, "targetDateEpochSec");
            String accountId = readStringField(fields, "accountId");

            if (name == null || name.isBlank()) {
                continue;
            }
            if (currency == null || currency.isBlank()) {
                continue;
            }
            if (targetCents == null) {
                continue;
            }
            if (targetDate == null) {
                continue;
            }
            if (accountId == null || accountId.isBlank()) {
                continue;
            }

            String status = readStringField(fields, "status");
            if (status == null || status.isBlank()) {
                status = GoalRepository.STATUS_OPEN;
            }

            Long createdAt = readLongField(fields, "createdAtEpochSec");
            Long updatedAt = readLongField(fields, "updatedAtEpochSec");
            long cAt = createdAt == null ? now : createdAt;
            long uAt = updatedAt == null ? cAt : updatedAt;

            out.add(new GoalRepository.Goal(
                id,
                userUid,
                name,
                currency,
                targetCents,
                targetDate,
                accountId,
                status,
                cAt,
                uAt
            ));
        }

        return out;
    }

    public List<AccountRepository.Account> pullAccounts(AuthSession session) throws Exception {
        String baseUrl = "https://firestore.googleapis.com/v1/projects/" + urlEncode(projectId)
            + "/databases/(default)/documents/users/" + urlEncode(session.uid())
            + "/accounts";
        List<String> pages = pullAllPages(session, baseUrl, 1000);

        List<AccountRepository.Account> out = new ArrayList<>();
        for (String body : pages) {
            out.addAll(parseAccountsList(session.uid(), body));
        }
        return out;
    }

    public List<GoalRepository.Goal> pullGoals(AuthSession session) throws Exception {
        String baseUrl = "https://firestore.googleapis.com/v1/projects/" + urlEncode(projectId)
            + "/databases/(default)/documents/users/" + urlEncode(session.uid())
            + "/goals";
        List<String> pages = pullAllPages(session, baseUrl, 1000);

        List<GoalRepository.Goal> out = new ArrayList<>();
        for (String body : pages) {
            out.addAll(parseGoalsList(session.uid(), body));
        }
        return out;
    }

    public List<TransactionRepository.TransactionSyncRow> pullTransactions(AuthSession session) throws Exception {
        String baseUrl = "https://firestore.googleapis.com/v1/projects/" + urlEncode(projectId)
            + "/databases/(default)/documents/users/" + urlEncode(session.uid())
            + "/transactions";
        List<String> pages = pullAllPages(session, baseUrl, 1000);

        List<TransactionRepository.TransactionSyncRow> out = new ArrayList<>();
        for (String body : pages) {
            out.addAll(parseTransactionsList(session.uid(), body));
        }
        return out;
    }

    public List<TransferRepository.TransferSyncRow> pullTransfers(AuthSession session) throws Exception {
        String baseUrl = "https://firestore.googleapis.com/v1/projects/" + urlEncode(projectId)
            + "/databases/(default)/documents/users/" + urlEncode(session.uid())
            + "/transfers";
        List<String> pages = pullAllPages(session, baseUrl, 1000);

        List<TransferRepository.TransferSyncRow> out = new ArrayList<>();
        for (String body : pages) {
            out.addAll(parseTransfersList(session.uid(), body));
        }
        return out;
    }

    public void syncTransactions(AuthSession session, TransactionRepository txRepo) throws Exception {
        List<TransactionRepository.TransactionSyncRow> txs = txRepo.listAllForSync(session.uid());
        System.out.println("[FirestoreSync] transactions=" + txs.size());
        for (TransactionRepository.TransactionSyncRow t : txs) {
            upsertTransaction(session, t);
        }
    }

    public void syncTransfers(AuthSession session, TransferRepository transferRepo) throws Exception {
        List<TransferRepository.TransferSyncRow> trs = transferRepo.listAllForSync(session.uid());
        System.out.println("[FirestoreSync] transfers=" + trs.size());
        for (TransferRepository.TransferSyncRow tr : trs) {
            upsertTransfer(session, tr);
        }
    }

    private long retryAfterMs(HttpResponse<?> resp) {
        try {
            String v = resp.headers().firstValue("Retry-After").orElse(null);
            if (v == null) {
                return 0L;
            }
            v = v.trim();
            if (v.isEmpty()) {
                return 0L;
            }
            long seconds = Long.parseLong(v);
            if (seconds <= 0) {
                return 0L;
            }
            return Math.min(600_000L, seconds * 1_000L);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private List<String> pullAllPages(AuthSession session, String baseUrl, int pageSize) throws Exception {
        String nextPageToken = null;
        List<String> bodies = new ArrayList<>();
        int guard = 0;
        while (guard++ < 1000) {
            String url = baseUrl + "?pageSize=" + pageSize + (nextPageToken == null ? "" : "&pageToken=" + urlEncode(nextPageToken));

            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + session.idToken())
                .GET()
                .build();

            HttpResponse<String> resp;
            int retries = 0;
            while (true) {
                resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 429) {
                    break;
                }
                if (retries++ >= 8) {
                    throw new RuntimeException("Firestore pull failed (429): " + resp.body());
                }
                long retryAfterMs = retryAfterMs(resp);
                long expBackoffMs = Math.min(300_000L, 1_000L * (1L << Math.min(retries, 8)));
                long sleepMs = Math.max(retryAfterMs, expBackoffMs);
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Firestore pull interrupted");
                }
            }

            if (resp.statusCode() / 100 != 2) {
                throw new RuntimeException("Firestore pull failed (" + resp.statusCode() + "): " + resp.body());
            }

            String body = resp.body();
            bodies.add(body);

            Map<?, ?> json = MAPPER.readValue(body, Map.class);
            Object tokenObj = json.get("nextPageToken");
            if (!(tokenObj instanceof String token) || token.isBlank()) {
                break;
            }
            nextPageToken = token;
        }
        return bodies;
    }

    public void syncTransaction(AuthSession session, TransactionRepository.TransactionSyncRow tx) throws Exception {
        upsertTransaction(session, tx);
    }

    public void syncTransfer(AuthSession session, TransferRepository.TransferSyncRow tr) throws Exception {
        upsertTransfer(session, tr);
    }

    public void syncLoanPayment(AuthSession session, LoanPaymentRepository.LoanPayment payment) throws Exception {
        upsertLoanPayment(session, payment);
    }

    public void syncLoan(AuthSession session, LoanRepository.Loan loan) throws Exception {
        upsertLoan(session, loan);
    }

    public void deleteTransaction(AuthSession session, String transactionId) throws Exception {
        String url = "https://firestore.googleapis.com/v1/projects/" + urlEncode(projectId)
            + "/databases/(default)/documents/users/" + urlEncode(session.uid())
            + "/transactions/" + urlEncode(transactionId);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + session.idToken())
            .DELETE()
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Firestore delete transaction failed (" + resp.statusCode() + "): " + resp.body());
        }
    }

    public void deleteTransfer(AuthSession session, String transferId) throws Exception {
        String url = "https://firestore.googleapis.com/v1/projects/" + urlEncode(projectId)
            + "/databases/(default)/documents/users/" + urlEncode(session.uid())
            + "/transfers/" + urlEncode(transferId);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + session.idToken())
            .DELETE()
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Firestore delete transfer failed (" + resp.statusCode() + "): " + resp.body());
        }
    }

    private void upsertAccount(AuthSession session, AccountRepository.Account a) throws Exception {
        String url = "https://firestore.googleapis.com/v1/projects/" + urlEncode(projectId)
            + "/databases/(default)/documents/users/" + urlEncode(session.uid())
            + "/accounts/" + urlEncode(a.id());

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("userUid", stringField(a.userUid()));
        fields.put("name", stringField(a.name()));
        fields.put("type", stringField(a.type()));
        fields.put("currency", stringField(a.currency()));
        fields.put("createdAtEpochSec", intField(a.createdAtEpochSec()));
        fields.put("updatedAtEpochSec", intField(a.updatedAtEpochSec()));
        fields.put("updatedBy", stringField(DeviceId.get()));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fields", fields);

        String body = MAPPER.writeValueAsString(payload);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + session.idToken())
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Firestore upsert account failed (" + resp.statusCode() + "): " + resp.body());
        }
    }

    private void upsertCategory(AuthSession session, CategoryRepository.Category c) throws Exception {
        String url = "https://firestore.googleapis.com/v1/projects/" + urlEncode(projectId)
            + "/databases/(default)/documents/users/" + urlEncode(session.uid())
            + "/categories/" + urlEncode(c.id());

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", stringField(c.id()));
        fields.put("userUid", stringField(c.userUid()));
        fields.put("name", stringField(c.name()));
        if (c.parentId() == null || c.parentId().isBlank()) {
            fields.put("parentId", nullField());
        } else {
            fields.put("parentId", stringField(c.parentId()));
        }
        fields.put("createdAtEpochSec", intField(c.createdAtEpochSec()));
        fields.put("updatedAtEpochSec", intField(c.updatedAtEpochSec()));
        fields.put("updatedBy", stringField(DeviceId.get()));

        patchDoc(session, url, fields, "category");
    }

    private void upsertGoal(AuthSession session, GoalRepository.Goal g) throws Exception {
        String url = "https://firestore.googleapis.com/v1/projects/" + urlEncode(projectId)
            + "/databases/(default)/documents/users/" + urlEncode(session.uid())
            + "/goals/" + urlEncode(g.id());

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", stringField(g.id()));
        fields.put("userUid", stringField(g.userUid()));
        fields.put("name", stringField(g.name()));
        fields.put("currency", stringField(g.currency()));
        fields.put("targetCents", intField(g.targetCents()));
        fields.put("targetDateEpochSec", intField(g.targetDateEpochSec()));
        fields.put("accountId", stringField(g.accountId()));
        fields.put("status", stringField(g.status()));
        fields.put("createdAtEpochSec", intField(g.createdAtEpochSec()));
        fields.put("updatedAtEpochSec", intField(g.updatedAtEpochSec()));
        fields.put("updatedBy", stringField(DeviceId.get()));

        patchDoc(session, url, fields, "goal");
    }

    private void upsertBudget(AuthSession session, BudgetRepository.Budget b) throws Exception {
        String url = "https://firestore.googleapis.com/v1/projects/" + urlEncode(projectId)
            + "/databases/(default)/documents/users/" + urlEncode(session.uid())
            + "/budgets/" + urlEncode(b.id());

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", stringField(b.id()));
        fields.put("userUid", stringField(b.userUid()));
        fields.put("month", stringField(b.month()));
        fields.put("categoryId", stringField(b.categoryId()));
        fields.put("limitCents", intField(b.limitCents()));
        fields.put("currency", stringField(b.currency()));
        fields.put("createdAtEpochSec", intField(b.createdAtEpochSec()));
        fields.put("updatedAtEpochSec", intField(b.updatedAtEpochSec()));
        fields.put("updatedBy", stringField(DeviceId.get()));

        patchDoc(session, url, fields, "budget");
    }

    private void upsertLoan(AuthSession session, LoanRepository.Loan l) throws Exception {
        String url = "https://firestore.googleapis.com/v1/projects/" + urlEncode(projectId)
            + "/databases/(default)/documents/users/" + urlEncode(session.uid())
            + "/loans/" + urlEncode(l.id());

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", stringField(l.id()));
        fields.put("userUid", stringField(l.userUid()));
        fields.put("type", stringField(l.type()));
        fields.put("counterpartyName", stringField(l.counterpartyName()));
        if (l.accountId() == null || l.accountId().isBlank()) {
            fields.put("accountId", nullField());
        } else {
            fields.put("accountId", stringField(l.accountId()));
        }
        fields.put("principalCents", intField(l.principalCents()));
        fields.put("currency", stringField(l.currency()));
        fields.put("status", stringField(l.status()));
        fields.put("notes", stringField(l.notes()));
        fields.put("occurredAtEpochSec", intField(l.occurredAtEpochSec()));
        fields.put("createdAtEpochSec", intField(l.createdAtEpochSec()));
        fields.put("updatedAtEpochSec", intField(l.updatedAtEpochSec()));
        fields.put("updatedBy", stringField(DeviceId.get()));

        patchDoc(session, url, fields, "loan");
    }

    private void upsertLoanPayment(AuthSession session, LoanPaymentRepository.LoanPayment p) throws Exception {
        String url = "https://firestore.googleapis.com/v1/projects/" + urlEncode(projectId)
            + "/databases/(default)/documents/users/" + urlEncode(session.uid())
            + "/loanPayments/" + urlEncode(p.id());

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", stringField(p.id()));
        fields.put("userUid", stringField(p.userUid()));
        fields.put("loanId", stringField(p.loanId()));
        fields.put("accountId", stringField(p.accountId()));
        fields.put("principalCents", intField(p.principalCents()));
        fields.put("occurredAtEpochSec", intField(p.occurredAtEpochSec()));
        if (p.linkedTransactionId() == null || p.linkedTransactionId().isBlank()) {
            fields.put("linkedTransactionId", nullField());
        } else {
            fields.put("linkedTransactionId", stringField(p.linkedTransactionId()));
        }
        fields.put("note", stringField(p.note()));
        fields.put("createdAtEpochSec", intField(p.createdAtEpochSec()));
        fields.put("updatedAtEpochSec", intField(p.updatedAtEpochSec()));
        fields.put("updatedBy", stringField(DeviceId.get()));

        patchDoc(session, url, fields, "loanPayment");
    }

    @SuppressWarnings("unchecked")
    private static List<AccountRepository.Account> parseAccountsList(String userUid, String body) throws Exception {
        Map<String, Object> root = MAPPER.readValue(body, Map.class);
        Object docsObj = root.get("documents");
        if (!(docsObj instanceof List<?> docs)) {
            return List.of();
        }

        long now = Instant.now().getEpochSecond();
        List<AccountRepository.Account> out = new java.util.ArrayList<>();
        for (Object d : docs) {
            if (!(d instanceof Map<?, ?> doc)) {
                continue;
            }
            Object nameObj = doc.get("name");
            if (!(nameObj instanceof String fullName) || fullName.isBlank()) {
                continue;
            }
            String id = fullName.substring(fullName.lastIndexOf('/') + 1);

            Object fieldsObj = doc.get("fields");
            if (!(fieldsObj instanceof Map<?, ?> fields)) {
                continue;
            }

            String name = readStringField(fields, "name");
            if (name == null || name.isBlank()) {
                continue;
            }

            String type = readStringField(fields, "type");
            String currency = readStringField(fields, "currency");
            Long createdAt = readLongField(fields, "createdAtEpochSec");
            Long updatedAt = readLongField(fields, "updatedAtEpochSec");

            String t = (type == null || type.isBlank()) ? "BANK" : type;
            String cur = (currency == null || currency.isBlank()) ? "COP" : currency;
            long cAt = createdAt == null ? now : createdAt;
            long uAt = updatedAt == null ? cAt : updatedAt;

            out.add(new AccountRepository.Account(id, userUid, name, t, cur, cAt, uAt));
        }

        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<TransferRepository.TransferSyncRow> parseTransfersList(String userUid, String body) throws Exception {
        Map<String, Object> root = MAPPER.readValue(body, Map.class);
        Object docsObj = root.get("documents");
        if (!(docsObj instanceof List<?> docs)) {
            return List.of();
        }

        long now = Instant.now().getEpochSecond();
        List<TransferRepository.TransferSyncRow> out = new java.util.ArrayList<>();
        for (Object d : docs) {
            if (!(d instanceof Map<?, ?> doc)) {
                continue;
            }
            Object nameObj = doc.get("name");
            if (!(nameObj instanceof String fullName) || fullName.isBlank()) {
                continue;
            }
            String id = fullName.substring(fullName.lastIndexOf('/') + 1);

            Object fieldsObj = doc.get("fields");
            if (!(fieldsObj instanceof Map<?, ?> fields)) {
                continue;
            }

            String fromAccountId = readStringField(fields, "fromAccountId");
            String toAccountId = readStringField(fields, "toAccountId");
            Long amountCents = readLongField(fields, "amountCents");
            Long occurredAt = readLongField(fields, "occurredAtEpochSec");

            if (fromAccountId == null || fromAccountId.isBlank()) {
                continue;
            }
            if (toAccountId == null || toAccountId.isBlank()) {
                continue;
            }
            if (amountCents == null) {
                continue;
            }
            if (occurredAt == null) {
                continue;
            }

            String note = readStringField(fields, "note");
            Long createdAt = readLongField(fields, "createdAtEpochSec");
            Long updatedAt = readLongField(fields, "updatedAtEpochSec");
            long cAt = createdAt == null ? now : createdAt;
            long uAt = updatedAt == null ? cAt : updatedAt;

            out.add(new TransferRepository.TransferSyncRow(
                id,
                userUid,
                fromAccountId,
                toAccountId,
                amountCents,
                occurredAt,
                note,
                cAt,
                uAt
            ));
        }

        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<TransactionRepository.TransactionSyncRow> parseTransactionsList(String userUid, String body) throws Exception {
        Map<String, Object> root = MAPPER.readValue(body, Map.class);
        Object docsObj = root.get("documents");
        if (!(docsObj instanceof List<?> docs)) {
            return List.of();
        }

        long now = Instant.now().getEpochSecond();
        List<TransactionRepository.TransactionSyncRow> out = new java.util.ArrayList<>();
        for (Object d : docs) {
            if (!(d instanceof Map<?, ?> doc)) {
                continue;
            }
            Object nameObj = doc.get("name");
            if (!(nameObj instanceof String fullName) || fullName.isBlank()) {
                continue;
            }
            String id = fullName.substring(fullName.lastIndexOf('/') + 1);

            Object fieldsObj = doc.get("fields");
            if (!(fieldsObj instanceof Map<?, ?> fields)) {
                continue;
            }

            String accountId = readStringField(fields, "accountId");
            String categoryId = readStringField(fields, "categoryId");
            String kind = readStringField(fields, "kind");
            Long amountCents = readLongField(fields, "amountCents");
            Long occurredAt = readLongField(fields, "occurredAtEpochSec");

            if (accountId == null || accountId.isBlank()) {
                continue;
            }
            if (categoryId == null || categoryId.isBlank()) {
                continue;
            }
            if (kind == null || kind.isBlank()) {
                continue;
            }
            if (amountCents == null) {
                continue;
            }
            if (occurredAt == null) {
                continue;
            }

            String note = readStringField(fields, "note");
            Long createdAt = readLongField(fields, "createdAtEpochSec");
            Long updatedAt = readLongField(fields, "updatedAtEpochSec");
            long cAt = createdAt == null ? now : createdAt;
            long uAt = updatedAt == null ? cAt : updatedAt;

            out.add(new TransactionRepository.TransactionSyncRow(
                id,
                userUid,
                accountId,
                categoryId,
                kind,
                amountCents,
                occurredAt,
                note,
                cAt,
                uAt
            ));
        }

        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<CategoryRepository.Category> parseCategoriesList(String userUid, String body) throws Exception {
        Map<String, Object> root = MAPPER.readValue(body, Map.class);
        Object docsObj = root.get("documents");
        if (!(docsObj instanceof List<?> docs)) {
            return List.of();
        }

        long now = Instant.now().getEpochSecond();
        List<CategoryRepository.Category> out = new java.util.ArrayList<>();
        for (Object d : docs) {
            if (!(d instanceof Map<?, ?> doc)) {
                continue;
            }
            Object nameObj = doc.get("name");
            if (!(nameObj instanceof String fullName) || fullName.isBlank()) {
                continue;
            }
            String id = fullName.substring(fullName.lastIndexOf('/') + 1);

            Object fieldsObj = doc.get("fields");
            if (!(fieldsObj instanceof Map<?, ?> fields)) {
                continue;
            }

            String name = readStringField(fields, "name");
            if (name == null || name.isBlank()) {
                continue;
            }

            String parentId = readStringField(fields, "parentId");
            Long createdAt = readLongField(fields, "createdAtEpochSec");
            Long updatedAt = readLongField(fields, "updatedAtEpochSec");
            long cAt = createdAt == null ? now : createdAt;
            long uAt = updatedAt == null ? cAt : updatedAt;

            out.add(new CategoryRepository.Category(id, userUid, name, parentId, cAt, uAt));
        }

        return out;
    }

    private static String readStringField(Map<?, ?> fields, String key) {
        Object f = fields.get(key);
        if (!(f instanceof Map<?, ?> fm)) {
            return null;
        }
        Object v = fm.get("stringValue");
        if (v instanceof String s) {
            String t = s.trim();
            return t.isBlank() ? null : t;
        }
        return null;
    }

    private static Long readLongField(Map<?, ?> fields, String key) {
        Object f = fields.get(key);
        if (!(f instanceof Map<?, ?> fm)) {
            return null;
        }
        Object iv = fm.get("integerValue");
        if (iv instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (Exception ignored) {
                return null;
            }
        }
        if (iv instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    private void upsertTransaction(AuthSession session, TransactionRepository.TransactionSyncRow t) throws Exception {
        String url = "https://firestore.googleapis.com/v1/projects/" + urlEncode(projectId)
            + "/databases/(default)/documents/users/" + urlEncode(session.uid())
            + "/transactions/" + urlEncode(t.id());

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", stringField(t.id()));
        fields.put("userUid", stringField(t.userUid()));
        fields.put("accountId", stringField(t.accountId()));
        fields.put("categoryId", stringField(t.categoryId()));
        fields.put("kind", stringField(t.kind()));
        fields.put("amountCents", intField(t.amountCents()));
        fields.put("occurredAtEpochSec", intField(t.occurredAtEpochSec()));
        fields.put("note", stringField(t.note()));
        fields.put("createdAtEpochSec", intField(t.createdAtEpochSec()));
        fields.put("updatedAtEpochSec", intField(t.updatedAtEpochSec()));
        fields.put("updatedBy", stringField(DeviceId.get()));

        patchDoc(session, url, fields, "transaction");
    }

    private void upsertTransfer(AuthSession session, TransferRepository.TransferSyncRow tr) throws Exception {
        String url = "https://firestore.googleapis.com/v1/projects/" + urlEncode(projectId)
            + "/databases/(default)/documents/users/" + urlEncode(session.uid())
            + "/transfers/" + urlEncode(tr.id());

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", stringField(tr.id()));
        fields.put("userUid", stringField(tr.userUid()));
        fields.put("fromAccountId", stringField(tr.fromAccountId()));
        fields.put("toAccountId", stringField(tr.toAccountId()));
        fields.put("amountCents", intField(tr.amountCents()));
        fields.put("occurredAtEpochSec", intField(tr.occurredAtEpochSec()));
        fields.put("note", stringField(tr.note()));
        fields.put("createdAtEpochSec", intField(tr.createdAtEpochSec()));
        fields.put("updatedAtEpochSec", intField(tr.updatedAtEpochSec()));
        fields.put("updatedBy", stringField(DeviceId.get()));

        patchDoc(session, url, fields, "transfer");
    }

    private void patchDoc(AuthSession session, String url, Map<String, Object> fields, String kind) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fields", fields);

        String body = MAPPER.writeValueAsString(payload);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + session.idToken())
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Firestore upsert " + kind + " failed (" + resp.statusCode() + "): " + resp.body());
        }
    }

    private static Map<String, Object> stringField(String v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stringValue", v == null ? "" : v);
        return m;
    }

    private static Map<String, Object> nullField() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nullValue", null);
        return m;
    }

    private static Map<String, Object> intField(long v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("integerValue", Long.toString(v));
        return m;
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
