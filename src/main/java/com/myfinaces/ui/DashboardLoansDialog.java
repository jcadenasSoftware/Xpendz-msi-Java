package com.myfinaces.ui;

import com.myfinaces.auth.AuthSession;
import com.myfinaces.config.AppConfig;
import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.LoanPaymentRepository;
import com.myfinaces.db.LoanRepository;
import com.myfinaces.db.TransactionKind;
import com.myfinaces.db.TransactionRepository;
import com.myfinaces.sync.FirestoreSyncService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class DashboardLoansDialog {

    private DashboardLoansDialog() {
    }

    public static void showLoansDialog(
        AuthSession session,
        LoanRepository loanRepo,
        LoanPaymentRepository loanPaymentRepo,
        AccountRepository accountRepo,
        CategoryRepository categoryRepo,
        TransactionRepository txRepo,
        boolean darkTheme
        ,
        Runnable refreshBalances
    ) {
        String userUid = session.uid();
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Préstamos");
        UiDialogs.applyAppTheme(dialog, darkTheme);

        ButtonType closeBtn = new ButtonType("Volver", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(closeBtn);
        dialog.setResizable(true);
        dialog.getDialogPane().setMinWidth(980);
        dialog.getDialogPane().setMinHeight(720);

        javafx.event.EventHandler<javafx.scene.control.DialogEvent> existingOnShown = dialog.getOnShown();
        dialog.setOnShown(ev -> {
            if (existingOnShown != null) {
                existingOnShown.handle(ev);
            }
            Platform.runLater(() -> {
                try {
                    javafx.stage.Window w = dialog.getDialogPane().getScene().getWindow();
                    if (w instanceof javafx.stage.Stage s) {
                        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
                        s.setX(bounds.getMinX());
                        s.setY(bounds.getMinY());
                        s.setWidth(bounds.getWidth());
                        s.setHeight(bounds.getHeight());
                        s.setMaximized(true);

                        final double normalW = Math.min(1100, bounds.getWidth() * 0.92);
                        final double normalH = Math.min(760, bounds.getHeight() * 0.90);
                        s.maximizedProperty().addListener((o, oldV, newV) -> {
                            if (Boolean.TRUE.equals(newV)) {
                                return;
                            }
                            try {
                                s.setWidth(normalW);
                                s.setHeight(normalH);
                                s.centerOnScreen();
                            } catch (Exception ignored) {
                            }
                        });
                    }
                } catch (Exception ignored) {
                }
            });
        });

        Label headerTitle = new Label("Préstamos");
        headerTitle.getStyleClass().add("app-title");
        headerTitle.setStyle("-fx-font-size: 30px; -fx-font-weight: 800;");
        Label headerDesc = new Label("Registra préstamos y devoluciones.");
        headerDesc.getStyleClass().add("text-secondary");
        headerDesc.setStyle("-fx-font-size: 14px;");
        headerDesc.setWrapText(true);

        ImageView headerLogo = new ImageView();
        try {
            var logoStream = DashboardView.class.getResourceAsStream("/images/logo.png");
            if (logoStream != null) {
                headerLogo.setImage(new Image(logoStream));
            }
        } catch (Exception ignored) {
        }
        headerLogo.setPreserveRatio(true);
        headerLogo.setSmooth(true);
        headerLogo.setFitWidth(96);

        VBox headerText = new VBox(4, headerTitle, headerDesc);
        headerText.setAlignment(Pos.CENTER);
        headerText.setMaxWidth(Double.MAX_VALUE);

        BorderPane header = new BorderPane();
        header.getStyleClass().add("dialog-header");
        header.setLeft(headerLogo);
        header.setCenter(headerText);
        BorderPane.setAlignment(headerLogo, Pos.CENTER_LEFT);
        BorderPane.setMargin(headerLogo, new Insets(0, 14, 0, 10));
        dialog.getDialogPane().setHeader(header);

        Label error = new Label();
        error.getStyleClass().add("error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        VBox lentList = new VBox(10);
        lentList.getStyleClass().add("accounts-list");
        ScrollPane lentScroll = new ScrollPane(lentList);
        lentScroll.setFitToWidth(true);
        lentScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        lentScroll.getStyleClass().addAll("card", "content-card");
        VBox.setVgrow(lentScroll, Priority.ALWAYS);

        VBox borrowedList = new VBox(10);
        borrowedList.getStyleClass().add("accounts-list");
        ScrollPane borrowedScroll = new ScrollPane(borrowedList);
        borrowedScroll.setFitToWidth(true);
        borrowedScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        borrowedScroll.getStyleClass().addAll("card", "content-card");
        VBox.setVgrow(borrowedScroll, Priority.ALWAYS);

        TabPane tabs = new TabPane();
        Tab tabLent = new Tab("Me deben", lentScroll);
        tabLent.setClosable(false);
        Tab tabBorrowed = new Tab("Yo debo", borrowedScroll);
        tabBorrowed.setClosable(false);

        VBox historyList = new VBox(10);
        historyList.getStyleClass().add("accounts-list");
        ScrollPane historyScroll = new ScrollPane(historyList);
        historyScroll.setFitToWidth(true);
        historyScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        historyScroll.getStyleClass().addAll("card", "content-card");
        VBox.setVgrow(historyScroll, Priority.ALWAYS);
        Tab tabHistory = new Tab("Historial", historyScroll);
        tabHistory.setClosable(false);

        tabs.getTabs().addAll(tabLent, tabBorrowed, tabHistory);
        tabs.getStyleClass().add("account-summary-tabs");
        VBox.setVgrow(tabs, Priority.ALWAYS);

        AtomicReference<Runnable> refreshRef = new AtomicReference<>();
        Runnable refresh = () -> {
            lentList.getChildren().clear();
            borrowedList.getChildren().clear();
            historyList.getChildren().clear();
            error.setText("");
            error.setVisible(false);
            error.setManaged(false);
            try {
                List<LoanRepository.Loan> lent = loanRepo.listByType(userUid, LoanRepository.TYPE_LENT, null, true);
                List<LoanRepository.Loan> borrowed = loanRepo.listByType(userUid, LoanRepository.TYPE_BORROWED, null, true);

                java.util.function.Consumer<List<LoanRepository.Loan>> render = (items) -> {
                    for (LoanRepository.Loan l : items) {
                        try {
                            long paidCents = loanPaymentRepo.sumPrincipalPaidCents(userUid, l.id());
                            long pendingCents = Math.max(0L, l.principalCents() - paidCents);
                            if (pendingCents <= 0L) {
                                continue;
                            }

                            Label name = new Label(l.counterpartyName());
                            name.getStyleClass().add("account-name");

                            Label totalValue = new Label(DashboardFormatters.formatMoney(l.principalCents(), l.currency()));
                            totalValue.getStyleClass().addAll("account-name", "money-neutral");

                            Label paidValue = new Label(DashboardFormatters.formatMoney(paidCents, l.currency()));
                            paidValue.getStyleClass().addAll("account-name", paidCents > 0 ? "money-positive" : "money-neutral");

                            Label pendingValue = new Label(DashboardFormatters.formatMoney(pendingCents, l.currency()));
                            pendingValue.getStyleClass().addAll("account-name", pendingCents > 0 ? "money-negative" : "money-positive");

                            VBox totalBlock = new VBox(2, new Label("Total"), totalValue);
                            totalBlock.getChildren().getFirst().getStyleClass().add("text-secondary");
                            totalBlock.getStyleClass().add("loan-amount-block");
                            VBox paidBlock = new VBox(2, new Label("Pagado"), paidValue);
                            paidBlock.getChildren().getFirst().getStyleClass().add("text-secondary");
                            paidBlock.getStyleClass().addAll("loan-amount-block", "loan-amount-block-paid");
                            VBox pendingBlock = new VBox(2, new Label("Pendiente"), pendingValue);
                            pendingBlock.getChildren().getFirst().getStyleClass().add("text-secondary");
                            pendingBlock.getStyleClass().addAll("loan-amount-block", "loan-amount-block-pending");

                            HBox amounts = new HBox(18, totalBlock, paidBlock, pendingBlock);
                            amounts.setAlignment(Pos.CENTER_LEFT);

                            Button addPayment = new Button("Registrar pago");
                            addPayment.getStyleClass().add("btn-secondary");
                            addPayment.setOnAction(ev -> {
                                Optional<LoanPaymentDraft> draft = showRegisterLoanPaymentDialog(userUid, l, accountRepo, darkTheme, pendingCents);
                                if (draft.isEmpty()) {
                                    return;
                                }
                                try {
                                    LoanPaymentDraft d = draft.get();

                                    String repaymentCategoryId = ensureSystemLoanCategories(userUid, categoryRepo, session).repaymentCategoryId();
                                    String kind = LoanRepository.TYPE_LENT.equals(l.type())
                                        ? TransactionKind.LOAN_REPAYMENT_PRINCIPAL_IN.name()
                                        : TransactionKind.LOAN_REPAYMENT_PRINCIPAL_OUT.name();
                                    String txId = txRepo.create(
                                        userUid,
                                        d.accountId(),
                                        repaymentCategoryId,
                                        kind,
                                        d.principalCents(),
                                        d.occurredAtEpochSec(),
                                        d.note() == null ? (kind + ": " + l.counterpartyName()) : d.note()
                                    );

                                    String paymentId = loanPaymentRepo.create(userUid, l.id(), d.accountId(), d.principalCents(), d.occurredAtEpochSec(), txId, d.note());

                                    long newPaid = loanPaymentRepo.sumPrincipalPaidCents(userUid, l.id());
                                    long newPending = Math.max(0L, l.principalCents() - newPaid);
                                    if (newPending <= 0L) {
                                        loanRepo.update(userUid, l.id(), l.type(), l.counterpartyName(), l.principalCents(), l.currency(), LoanRepository.STATUS_CLOSED, l.notes());
                                    }

                                    try {
                                        AppConfig cfg = AppConfig.loadDefault();
                                        FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());

                                        try {
                                            TransactionRepository.TransactionSyncRow t = txRepo.getForSyncByIdOrNull(userUid, txId);
                                            if (t != null) {
                                                sync.syncTransaction(session, t);
                                            }
                                        } catch (Exception ignored) {
                                        }

                                        LoanPaymentRepository.LoanPayment p = loanPaymentRepo.getByIdOrNull(userUid, paymentId);
                                        if (p != null) {
                                            sync.syncLoanPayment(session, p);
                                        }
                                        LoanRepository.Loan updatedLoan = loanRepo.getByIdOrNull(userUid, l.id());
                                        if (updatedLoan != null) {
                                            sync.syncLoan(session, updatedLoan);
                                        }
                                    } catch (Exception ignored) {
                                    }

                                    Platform.runLater(() -> {
                                        try {
                                            refreshBalances.run();
                                        } catch (Exception ignored) {
                                        }

                                        Runnable r = refreshRef.get();
                                        if (r != null) {
                                            r.run();
                                        }
                                    });
                                } catch (Exception ex) {
                                    error.setText(ex.getMessage() == null ? "No se pudo registrar el pago" : ex.getMessage());
                                    error.setVisible(true);
                                    error.setManaged(true);
                                }
                            });

                            Region spacer = new Region();
                            HBox.setHgrow(spacer, Priority.ALWAYS);
                            HBox actions = new HBox(10, spacer, addPayment);
                            actions.setAlignment(Pos.CENTER_RIGHT);

                            VBox row = new VBox(8, name, amounts, actions);
                            row.getStyleClass().add("account-item");

                            if (LoanRepository.TYPE_LENT.equals(l.type())) {
                                lentList.getChildren().add(row);
                            } else {
                                borrowedList.getChildren().add(row);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                };

                render.accept(lent);
                render.accept(borrowed);

                if (lentList.getChildren().isEmpty()) {
                    Label empty = new Label("No hay préstamos");
                    empty.getStyleClass().add("text-secondary");
                    lentList.getChildren().add(empty);
                }

                if (borrowedList.getChildren().isEmpty()) {
                    Label empty = new Label("No hay préstamos");
                    empty.getStyleClass().add("text-secondary");
                    borrowedList.getChildren().add(empty);
                }

                try {
                    List<LoanPaymentRepository.LoanPayment> payments = loanPaymentRepo.listAllByUser(userUid);
                    if (payments.isEmpty()) {
                        Label empty = new Label("No hay pagos registrados");
                        empty.getStyleClass().add("text-secondary");
                        historyList.getChildren().add(empty);
                    } else {
                        for (LoanPaymentRepository.LoanPayment p : payments) {
                            LoanRepository.Loan loan;
                            try {
                                loan = loanRepo.getByIdOrNull(userUid, p.loanId());
                            } catch (Exception ignored) {
                                loan = null;
                            }
                            String title = loan == null ? p.loanId() : loan.counterpartyName();
                            Label name = new Label(title);
                            name.getStyleClass().add("account-name");

                            String cur = loan == null ? "COP" : loan.currency();
                            Label amount = new Label(DashboardFormatters.formatMoney(p.principalCents(), cur));
                            amount.getStyleClass().addAll("account-name", "money-neutral");

                            Region pSpacer = new Region();
                            HBox.setHgrow(pSpacer, Priority.ALWAYS);
                            HBox row = new HBox(10, name, pSpacer, amount);
                            row.getStyleClass().add("account-item");
                            row.setMinHeight(Region.USE_PREF_SIZE);
                            historyList.getChildren().add(row);
                        }
                    }
                } catch (Exception ignored) {
                }
            } catch (Exception ex) {
                error.setText(ex.getMessage() == null ? "No se pudieron cargar los préstamos" : ex.getMessage());
                error.setVisible(true);
                error.setManaged(true);
            }
        };

        refreshRef.set(refresh);

        Button create = new Button("Nuevo préstamo");
        create.getStyleClass().add("btn-primary");
        create.setOnAction(e -> {
            Optional<LoanDraft> draft = showCreateLoanDialog(userUid, accountRepo, darkTheme);
            if (draft.isEmpty()) {
                return;
            }
            try {
                LoanDraft d = draft.get();

                String loanCategoryId = ensureSystemLoanCategories(userUid, categoryRepo, session).loanCategoryId();
                String kind = LoanRepository.TYPE_LENT.equals(d.type())
                    ? TransactionKind.LOAN_LENT_OUT.name()
                    : TransactionKind.LOAN_BORROWED_IN.name();
                long occ = Instant.now().getEpochSecond();
                String txId = txRepo.create(
                    userUid,
                    d.accountId(),
                    loanCategoryId,
                    kind,
                    d.principalCents(),
                    occ,
                    kind + ": " + d.counterpartyName()
                );

                String id = loanRepo.create(
                    userUid,
                    d.type(),
                    d.counterpartyName(),
                    d.accountId(),
                    d.principalCents(),
                    d.currency(),
                    occ,
                    d.notes()
                );

                try {
                    LoanRepository.Loan created = loanRepo.getByIdOrNull(userUid, id);
                    if (created != null) {
                        new Thread(() -> {
                            try {
                                AppConfig cfg = AppConfig.loadDefault();
                                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());

                                try {
                                    TransactionRepository.TransactionSyncRow t = txRepo.getForSyncByIdOrNull(userUid, txId);
                                    if (t != null) {
                                        sync.syncTransaction(session, t);
                                    }
                                } catch (Exception ignored) {
                                }

                                sync.syncLoan(session, created);
                            } catch (Exception ignored) {
                            }
                        }).start();
                    }
                } catch (Exception ignored) {
                }

                Platform.runLater(() -> {
                    try {
                        if (LoanRepository.TYPE_LENT.equals(d.type())) {
                            tabs.getSelectionModel().select(tabLent);
                        } else if (LoanRepository.TYPE_BORROWED.equals(d.type())) {
                            tabs.getSelectionModel().select(tabBorrowed);
                        }
                    } catch (Exception ignored) {
                    }

                    Runnable r = refreshRef.get();
                    if (r != null) {
                        r.run();
                    }

                    try {
                        refreshBalances.run();
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception ex) {
                error.setText(ex.getMessage() == null ? "No se pudo crear el préstamo" : ex.getMessage());
                error.setVisible(true);
                error.setManaged(true);
            }
        });

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        VBox tabsWrap = new VBox(tabs);
        tabsWrap.getStyleClass().addAll("card", "content-card");
        tabsWrap.setPadding(new Insets(8, 10, 0, 10));
        VBox.setVgrow(tabsWrap, Priority.ALWAYS);

        HBox headerBar = new HBox(12, headerSpacer, create);
        headerBar.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(12, headerBar, tabsWrap, error);
        content.setPadding(new Insets(10));

        dialog.getDialogPane().setContent(content);
        Runnable r = refreshRef.get();
        if (r != null) {
            r.run();
        }
        dialog.showAndWait();
    }

    private record LoanPaymentDraft(
        String accountId,
        long principalCents,
        long occurredAtEpochSec,
        String note
    ) {
    }

    private static Optional<LoanPaymentDraft> showRegisterLoanPaymentDialog(
        String userUid,
        LoanRepository.Loan loan,
        AccountRepository accountRepo,
        boolean darkTheme,
        long maxPendingCents
    ) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Registrar pago");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        UiDialogs.applyAppTheme(dialog, darkTheme);
        dialog.getDialogPane().setMinWidth(680);
        dialog.getDialogPane().setPrefWidth(680);

        ChoiceBox<AccountRepository.Account> account = new ChoiceBox<>();
        try {
            account.getItems().addAll(accountRepo.list(userUid));
            if (!account.getItems().isEmpty()) {
                account.getSelectionModel().selectFirst();
            }
        } catch (Exception ignored) {
        }
        account.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AccountRepository.Account object) {
                if (object == null) {
                    return "";
                }
                return object.name() + " · " + object.currency();
            }

            @Override
            public AccountRepository.Account fromString(String string) {
                return null;
            }
        });

        DatePicker date = new DatePicker(LocalDate.now());

        TextField amount = new TextField();
        amount.setPromptText("Ej: 50000.00");

        UiDialogs.restrictToDecimalAmount(amount);

        TextField note = new TextField();
        note.setPromptText("Nota (opcional)");

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(14));
        grid.setPrefWidth(640);

        Label lLoan = new Label("Préstamo");
        lLoan.getStyleClass().add("account-name");
        grid.add(lLoan, 0, 0);
        grid.add(new Label(loan.counterpartyName() + " · " + loan.currency()), 1, 0);

        Label lAccount = new Label("Cuenta");
        lAccount.getStyleClass().add("account-name");
        grid.add(lAccount, 0, 1);
        grid.add(account, 1, 1);

        Label lDate = new Label("Fecha");
        lDate.getStyleClass().add("account-name");
        grid.add(lDate, 0, 2);
        grid.add(date, 1, 2);

        Label lAmt = new Label("Monto");
        lAmt.getStyleClass().add("account-name");
        grid.add(lAmt, 0, 3);
        grid.add(amount, 1, 3);

        Label lNote = new Label("Nota");
        lNote.getStyleClass().add("account-name");
        grid.add(lNote, 0, 4);
        grid.add(note, 1, 4);

        dialog.getDialogPane().setContent(grid);
        dialog.setResultConverter(btn -> btn);
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return Optional.empty();
        }

        if (account.getValue() == null || date.getValue() == null) {
            return Optional.empty();
        }

        long cents;
        try {
            BigDecimal v = DashboardFormatters.parseAmount(amount.getText());
            cents = v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (Exception ignored) {
            return Optional.empty();
        }
        if (cents <= 0L || cents > maxPendingCents) {
            return Optional.empty();
        }

        long epoch = date.getValue().atTime(java.time.LocalTime.now().withNano(0)).atZone(ZoneId.systemDefault()).toEpochSecond();
        String n = note.getText() == null ? null : note.getText().trim();
        if (n != null && n.isBlank()) {
            n = null;
        }
        return Optional.of(new LoanPaymentDraft(account.getValue().id(), cents, epoch, n));
    }

    private record LoanDraft(
        String type,
        String counterpartyName,
        String accountId,
        String currency,
        long principalCents,
        String notes
    ) {
    }

    private static Optional<LoanDraft> showCreateLoanDialog(String userUid, AccountRepository accountRepo, boolean darkTheme) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Nuevo préstamo");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        UiDialogs.applyAppTheme(dialog, darkTheme);
        dialog.getDialogPane().setMinWidth(640);
        dialog.getDialogPane().setPrefWidth(640);

        ChoiceBox<String> type = new ChoiceBox<>();
        type.getItems().addAll(LoanRepository.TYPE_LENT, LoanRepository.TYPE_BORROWED);
        type.getSelectionModel().selectFirst();
        type.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String object) {
                if (LoanRepository.TYPE_LENT.equals(object)) {
                    return "Prestar";
                }
                if (LoanRepository.TYPE_BORROWED.equals(object)) {
                    return "Solicitar";
                }
                return object == null ? "" : object;
            }

            @Override
            public String fromString(String string) {
                return null;
            }
        });

        ChoiceBox<AccountRepository.Account> account = new ChoiceBox<>();
        try {
            account.getItems().addAll(accountRepo.list(userUid));
            if (!account.getItems().isEmpty()) {
                account.getSelectionModel().selectFirst();
            }
        } catch (Exception ignored) {
        }
        account.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AccountRepository.Account object) {
                if (object == null) {
                    return "";
                }
                return object.name();
            }

            @Override
            public AccountRepository.Account fromString(String string) {
                return null;
            }
        });

        Label accountBalance = new Label();
        accountBalance.getStyleClass().add("text-secondary");
        accountBalance.setWrapText(true);
        Runnable refreshBalance = () -> {
            AccountRepository.Account a = account.getValue();
            if (a == null) {
                accountBalance.setText("");
                return;
            }
            try {
                long cents = accountRepo.computeBalanceCents(userUid, a.id());
                accountBalance.setText("Saldo disponible: " + DashboardFormatters.formatMoney(cents, a.currency()));
            } catch (Exception ignored) {
                accountBalance.setText("");
            }
        };
        account.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> refreshBalance.run());
        refreshBalance.run();

        Label accountLabel = new Label("Cuenta");
        accountLabel.getStyleClass().add("account-name");
        Runnable refreshAccountLabel = () -> {
            String t = type.getValue();
            if (LoanRepository.TYPE_LENT.equals(t)) {
                accountLabel.setText("Cuenta (desde donde prestas)");
            } else {
                accountLabel.setText("Cuenta (donde recibes)");
            }
        };
        type.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> refreshAccountLabel.run());
        refreshAccountLabel.run();

        TextField counterparty = new TextField();
        counterparty.setPromptText("Ej: Juan / Banco X");

        TextField amount = new TextField();
        amount.setPromptText("Ej: 100000.00");

        UiDialogs.restrictToDecimalAmount(amount);

        TextField notes = new TextField();
        notes.setPromptText("Nota (opcional)");

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(14));
        grid.setPrefWidth(600);

        Label lType = new Label("Tipo");
        lType.getStyleClass().add("account-name");
        grid.add(lType, 0, 0);
        grid.add(type, 1, 0);

        grid.add(accountLabel, 0, 1);
        VBox accountBox = new VBox(4, account, accountBalance);
        grid.add(accountBox, 1, 1);

        Label lCp = new Label("Persona/Entidad");
        lCp.getStyleClass().add("account-name");
        grid.add(lCp, 0, 2);
        grid.add(counterparty, 1, 2);
        Label lAmt = new Label("Monto");
        lAmt.getStyleClass().add("account-name");
        grid.add(lAmt, 0, 3);
        grid.add(amount, 1, 3);
        Label lNotes = new Label("Nota");
        lNotes.getStyleClass().add("account-name");
        grid.add(lNotes, 0, 4);
        grid.add(notes, 1, 4);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> btn);
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return Optional.empty();
        }

        String t = type.getValue() == null ? LoanRepository.TYPE_LENT : type.getValue();
        AccountRepository.Account acc = account.getValue();
        String cp = counterparty.getText() == null ? "" : counterparty.getText().trim();
        if (acc == null) {
            return Optional.empty();
        }
        String cur = acc.currency() == null ? "" : acc.currency().trim().toUpperCase(Locale.ROOT);
        if (cp.isBlank() || cur.isBlank()) {
            return Optional.empty();
        }

        long cents;
        try {
            BigDecimal v = DashboardFormatters.parseAmount(amount.getText());
            cents = v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (Exception ignored) {
            return Optional.empty();
        }
        if (cents < 0) {
            return Optional.empty();
        }

        String n = notes.getText() == null ? null : notes.getText().trim();
        if (n != null && n.isBlank()) {
            n = null;
        }
        return Optional.of(new LoanDraft(t, cp, acc.id(), cur, cents, n));
    }

    private record SystemLoanCategories(String loanCategoryId, String repaymentCategoryId) {
    }

    private static SystemLoanCategories ensureSystemLoanCategories(
        String userUid,
        CategoryRepository categoryRepo,
        AuthSession session
    ) throws Exception {
        String loanCategoryId = "system-loan-" + userUid;
        String repaymentCategoryId = "system-loan-repayment-" + userUid;

        ensureSystemRootCategory(userUid, categoryRepo, session, loanCategoryId, "Préstamos");
        ensureSystemRootCategory(userUid, categoryRepo, session, repaymentCategoryId, "Devoluciones");
        return new SystemLoanCategories(loanCategoryId, repaymentCategoryId);
    }

    private static void ensureSystemRootCategory(
        String userUid,
        CategoryRepository categoryRepo,
        AuthSession session,
        String id,
        String name
    ) throws Exception {
        CategoryRepository.Category existing;
        try {
            existing = categoryRepo.getById(userUid, id);
        } catch (Exception ignored) {
            existing = null;
        }
        if (existing != null) {
            return;
        }
        CategoryRepository.Category created = categoryRepo.createWithId(userUid, id, name, null);
        try {
            AppConfig cfg = AppConfig.loadDefault();
            FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
            sync.syncCategory(session, created);
        } catch (Exception ignored) {
        }
    }
}
