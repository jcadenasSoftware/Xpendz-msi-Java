package com.myfinaces.ui;

import com.myfinaces.auth.AuthSession;
import com.myfinaces.config.AppConfig;
import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.BudgetRepository;
import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.GoalRepository;
import com.myfinaces.db.TransferRepository;
import com.myfinaces.sync.FirestoreSyncService;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.util.Duration;

import org.kordamp.ikonli.javafx.FontIcon;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

public final class DashboardBudgetDialog {

    private DashboardBudgetDialog() {
    }

    public static void showBudgetDialog(
        AuthSession session,
        BudgetRepository budgetRepo,
        GoalRepository goalRepo,
        CategoryRepository categoryRepo,
        AccountRepository accountRepo,
        TransferRepository transferRepo,
        boolean darkTheme,
        Runnable refreshBalances
    ) {
        showBudgetDialog(session, budgetRepo, goalRepo, categoryRepo, accountRepo, transferRepo, darkTheme, refreshBalances, 0);
    }

    public static void showBudgetDialog(
        AuthSession session,
        BudgetRepository budgetRepo,
        GoalRepository goalRepo,
        CategoryRepository categoryRepo,
        AccountRepository accountRepo,
        TransferRepository transferRepo,
        boolean darkTheme,
        Runnable refreshBalances,
        int initialTabIndex
    ) {
        String userUid = session.uid();
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Presupuesto");
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
                    }
                } catch (Exception ignored) {
                }
            });
        });

        Label headerTitle = new Label("Presupuesto");
        headerTitle.getStyleClass().add("app-title");
        headerTitle.setStyle("-fx-font-size: 34px; -fx-font-weight: 800;");
        Label headerDesc = new Label("Presupuesto mensual y metas.");
        headerDesc.getStyleClass().add("text-secondary");
        headerDesc.setStyle("-fx-font-size: 16px;");
        headerDesc.setWrapText(true);

        ImageView headerLogo = new ImageView();
        try {
            var logoStream = DashboardBudgetDialog.class.getResourceAsStream("/images/logo.png");
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

        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("account-summary-tabs");
        VBox monthly = buildMonthlyBudgetPane(userUid, budgetRepo, categoryRepo);
        VBox goals = buildGoalsPane(session, goalRepo, accountRepo, transferRepo, darkTheme, refreshBalances);
        Tab tabMonthly = new Tab("Mensual", monthly);
        tabMonthly.setClosable(false);
        Tab tabGoals = new Tab("Metas", goals);
        tabGoals.setClosable(false);
        tabs.getTabs().addAll(tabMonthly, tabGoals);

        if (initialTabIndex >= 0 && initialTabIndex < tabs.getTabs().size()) {
            tabs.getSelectionModel().select(initialTabIndex);
        }

        VBox tabsWrap = new VBox(tabs);
        tabsWrap.getStyleClass().addAll("card", "content-card");
        tabsWrap.setPadding(new Insets(8, 10, 0, 10));
        VBox.setVgrow(tabsWrap, Priority.ALWAYS);

        VBox content = new VBox(12, tabsWrap);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.showAndWait();
    }

    private static VBox buildMonthlyBudgetPane(
        String userUid,
        BudgetRepository budgetRepo,
        CategoryRepository categoryRepo
    ) {
        ChoiceBox<String> month = new ChoiceBox<>();
        LocalDate now = LocalDate.now();
        String currentMonth = String.format("%04d-%02d", now.getYear(), now.getMonthValue());
        month.getItems().add(currentMonth);
        month.getSelectionModel().selectFirst();

        ComboBox<String> currency = new ComboBox<>();
        currency.getItems().addAll("COP", "USD", "EUR", "GBP", "MXN", "ARS", "CLP", "PEN", "VES");
        currency.getSelectionModel().select("COP");

        ChoiceBox<CategoryRepository.Category> rootCategory = new ChoiceBox<>();
        ChoiceBox<CategoryRepository.Category> subCategory = new ChoiceBox<>();
        try {
            rootCategory.getItems().addAll(categoryRepo.listRoots(userUid));
            if (!rootCategory.getItems().isEmpty()) {
                rootCategory.getSelectionModel().selectFirst();
            }
        } catch (Exception ignored) {
        }

        rootCategory.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(CategoryRepository.Category object) {
                return object == null ? "" : object.name();
            }

            @Override
            public CategoryRepository.Category fromString(String string) {
                return null;
            }
        });
        subCategory.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(CategoryRepository.Category object) {
                return object == null ? "(Categoría raíz)" : object.name();
            }

            @Override
            public CategoryRepository.Category fromString(String string) {
                return null;
            }
        });

        Runnable refreshSubcategories = () -> {
            subCategory.getItems().clear();
            subCategory.getItems().add(null);
            CategoryRepository.Category root = rootCategory.getValue();
            if (root == null) {
                subCategory.getSelectionModel().selectFirst();
                return;
            }
            try {
                subCategory.getItems().addAll(categoryRepo.listChildren(userUid, root.id()));
            } catch (Exception ignored) {
            }
            subCategory.getSelectionModel().selectFirst();
        };

        TextField limit = new TextField();
        limit.setPromptText("Ej: 500000.00");

        Label error = new Label();
        error.getStyleClass().add("error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        VBox list = new VBox(10);
        list.getStyleClass().add("accounts-list");
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().addAll("card", "content-card");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        AtomicReference<Runnable> refreshRef = new AtomicReference<>();
        Runnable refresh = () -> {
            list.getChildren().clear();
            error.setText("");
            error.setVisible(false);
            error.setManaged(false);
            try {
                String m = month.getValue() == null ? currentMonth : month.getValue();
                String cur = currency.getValue() == null ? "COP" : currency.getValue();
                List<BudgetRepository.BudgetProgress> rows = budgetRepo.listProgressByMonthAndCurrency(userUid, m, cur);
                if (rows.isEmpty()) {
                    Label empty = new Label("No hay presupuestos");
                    empty.getStyleClass().add("text-secondary");
                    list.getChildren().add(empty);
                    return;
                }

                for (BudgetRepository.BudgetProgress p : rows) {
                    CategoryRepository.Category cat;
                    try {
                        cat = categoryRepo.getById(userUid, p.budget().categoryId());
                    } catch (Exception ex) {
                        cat = null;
                    }
                    String catName;
                    if (cat == null) {
                        catName = p.budget().categoryId();
                    } else if (cat.parentId() != null && !cat.parentId().isBlank()) {
                        try {
                            CategoryRepository.Category parent = categoryRepo.getById(userUid, cat.parentId());
                            String parentName = parent == null ? cat.parentId() : parent.name();
                            catName = parentName + " / " + cat.name();
                        } catch (Exception ignored) {
                            catName = cat.name();
                        }
                    } else {
                        catName = cat.name();
                    }

                    Label name = new Label(catName);
                    name.getStyleClass().add("account-name");

                    Label limitLabel = new Label(formatMoney(p.budget().limitCents(), p.budget().currency()));
                    limitLabel.getStyleClass().addAll("account-name", "money-neutral");

                    Label spentLabel = new Label(formatMoney(p.spentCents(), p.budget().currency()));
                    spentLabel.getStyleClass().addAll("account-name", p.spentCents() > 0 ? "money-negative" : "money-neutral");

                    long remaining = p.remainingCents();
                    Label remainingLabel = new Label(formatMoney(remaining, p.budget().currency()));
                    remainingLabel.getStyleClass().addAll("account-name", remaining >= 0 ? "money-positive" : "money-negative");

                    VBox limitBlock = new VBox(2, new Label("Límite"), limitLabel);
                    limitBlock.getChildren().getFirst().getStyleClass().add("text-secondary");
                    limitBlock.getStyleClass().add("loan-amount-block");

                    VBox spentBlock = new VBox(2, new Label("Gastado"), spentLabel);
                    spentBlock.getChildren().getFirst().getStyleClass().add("text-secondary");
                    spentBlock.getStyleClass().addAll("loan-amount-block", "loan-amount-block-pending");

                    VBox remainingBlock = new VBox(2, new Label("Disponible"), remainingLabel);
                    remainingBlock.getChildren().getFirst().getStyleClass().add("text-secondary");
                    remainingBlock.getStyleClass().addAll("loan-amount-block", "loan-amount-block-paid");

                    HBox amounts = new HBox(18, limitBlock, spentBlock, remainingBlock);
                    amounts.setAlignment(Pos.CENTER_LEFT);

                    Button del = new Button("Eliminar");
                    del.getStyleClass().add("btn-danger");
                    del.setOnAction(ev -> {
                        try {
                            budgetRepo.delete(userUid, p.budget().id());
                            Runnable r = refreshRef.get();
                            if (r != null) {
                                r.run();
                            }
                        } catch (Exception ex) {
                            error.setText(ex.getMessage() == null ? "No se pudo eliminar el límite" : ex.getMessage());
                            error.setVisible(true);
                            error.setManaged(true);
                        }
                    });
                    Region spacer = new Region();
                    HBox.setHgrow(spacer, Priority.ALWAYS);
                    HBox top = new HBox(10, name, spacer, del);
                    top.setAlignment(Pos.CENTER_LEFT);

                    VBox row = new VBox(8, top, amounts);
                    row.getStyleClass().add("account-item");
                    list.getChildren().add(row);
                }
            } catch (Exception ex) {
                error.setText(ex.getMessage() == null ? "No se pudo cargar el presupuesto" : ex.getMessage());
                error.setVisible(true);
                error.setManaged(true);
            }
        };

        refreshRef.set(refresh);

        Button upsert = new Button("Guardar límite");
        upsert.getStyleClass().add("btn-primary");
        upsert.setOnAction(e -> {
            error.setText("");
            error.setVisible(false);
            error.setManaged(false);
            try {
                CategoryRepository.Category root = rootCategory.getValue();
                if (root == null) {
                    return;
                }
                String m = month.getValue() == null ? currentMonth : month.getValue();
                String cur = currency.getValue() == null ? "COP" : currency.getValue().trim().toUpperCase(Locale.ROOT);
                BigDecimal v = parseAmount(limit.getText());
                long cents = v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
                if (cents < 0) {
                    return;
                }
                CategoryRepository.Category sub = subCategory.getValue();
                String categoryId = (sub == null) ? root.id() : sub.id();
                BudgetRepository.Budget existing = budgetRepo.getByUniqueKeyOrNull(userUid, m, cur, categoryId);
                if (existing == null) {
                    budgetRepo.create(userUid, m, categoryId, cents, cur);
                } else {
                    budgetRepo.update(userUid, existing.id(), m, categoryId, cents, cur);
                }
                refresh.run();
            } catch (Exception ex) {
                error.setText(ex.getMessage() == null ? "No se pudo guardar el límite" : ex.getMessage());
                error.setVisible(true);
                error.setManaged(true);
            }
        });

        Label fMonth = new Label("Mes");
        fMonth.getStyleClass().add("account-name");
        Label fCur = new Label("Moneda");
        fCur.getStyleClass().add("account-name");
        Label fRoot = new Label("Categoría");
        fRoot.getStyleClass().add("account-name");
        Label fSub = new Label("Subcategoría");
        fSub.getStyleClass().add("account-name");
        Label fLimit = new Label("Límite");
        fLimit.getStyleClass().add("account-name");

        month.setPrefWidth(120);
        currency.setPrefWidth(110);
        rootCategory.setPrefWidth(240);
        subCategory.setPrefWidth(240);
        limit.setPrefWidth(160);

        HBox filters = new HBox(10,
            fMonth, month,
            fCur, currency,
            fRoot, rootCategory,
            fSub, subCategory,
            fLimit, limit,
            upsert
        );
        filters.setAlignment(Pos.CENTER_LEFT);
        filters.setPadding(new Insets(10));
        filters.getStyleClass().addAll("card", "content-card");

        month.valueProperty().addListener((obs, o, n) -> refresh.run());
        currency.valueProperty().addListener((obs, o, n) -> refresh.run());
        rootCategory.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            refreshSubcategories.run();
            refresh.run();
        });
        subCategory.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> refresh.run());

        refreshSubcategories.run();
        refresh.run();
        VBox out = new VBox(12, filters, scroll, error);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return out;
    }

    private static VBox buildGoalsPane(
        AuthSession session,
        GoalRepository goalRepo,
        AccountRepository accountRepo,
        TransferRepository transferRepo,
        boolean darkTheme,
        Runnable refreshBalances
    ) {
        String userUid = session.uid();

        Label error = new Label();
        error.getStyleClass().add("error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        VBox list = new VBox(10);
        list.getStyleClass().add("accounts-list");
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().addAll("card", "content-card");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        AtomicReference<Runnable> refreshListRef = new AtomicReference<>();
        Runnable refreshList = () -> {
            error.setText("");
            error.setVisible(false);
            error.setManaged(false);
            list.getChildren().clear();

            try {
                List<GoalRepository.Goal> goals = goalRepo.listByUser(userUid);
                if (goals.isEmpty()) {
                    Label empty = new Label("Aún no tienes metas. Crea tu primera meta.");
                    empty.getStyleClass().add("text-secondary");
                    list.getChildren().add(empty);
                    return;
                }

                for (GoalRepository.Goal g : goals) {
                    final long savedCents = safeComputeBalanceCents(accountRepo, userUid, g.accountId());
                    long remaining = g.targetCents() - savedCents;

                    Label name = new Label(g.name());
                    name.getStyleClass().add("account-name");

                    Label goalAmount = new Label(formatMoney(g.targetCents(), g.currency()));
                    goalAmount.getStyleClass().addAll("account-name", "money-neutral");

                    Label savedAmount = new Label(formatMoney(savedCents, g.currency()));
                    savedAmount.getStyleClass().addAll("account-name", savedCents > 0 ? "money-positive" : "money-neutral");

                    Label remainingAmount = new Label(formatMoney(Math.max(0L, remaining), g.currency()));
                    remainingAmount.getStyleClass().addAll("account-name", remaining > 0 ? "money-negative" : "money-positive");

                    VBox goalBlock = new VBox(2, new Label("Objetivo"), goalAmount);
                    goalBlock.getChildren().getFirst().getStyleClass().add("text-secondary");
                    goalBlock.getStyleClass().add("loan-amount-block");

                    VBox savedBlock = new VBox(2, new Label("Guardado"), savedAmount);
                    savedBlock.getChildren().getFirst().getStyleClass().add("text-secondary");
                    savedBlock.getStyleClass().addAll("loan-amount-block", "loan-amount-block-paid");

                    VBox remainingBlock = new VBox(2, new Label("Falta"), remainingAmount);
                    remainingBlock.getChildren().getFirst().getStyleClass().add("text-secondary");
                    remainingBlock.getStyleClass().addAll("loan-amount-block", "loan-amount-block-pending");

                    HBox amounts = new HBox(18, goalBlock, savedBlock, remainingBlock);
                    amounts.setAlignment(Pos.CENTER_LEFT);

                    Button deposit = new Button("Depositar");
                    deposit.getStyleClass().add("btn-primary");
                    deposit.setOnAction(ev -> {
                        Optional<DashboardGoalsDialog.GoalTransfer> t = DashboardGoalsDialog.showGoalDepositDialog(userUid, g, accountRepo, darkTheme);
                        if (t.isEmpty()) {
                            return;
                        }
                        try {
                            DashboardGoalsDialog.GoalTransfer gt = t.get();
                            String transferId = transferRepo.create(userUid, gt.otherAccountId(), g.accountId(), gt.amountCents(), gt.occurredAtEpochSec(), gt.note());
                            try {
                                AppConfig cfg = AppConfig.loadDefault();
                                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                                sync.syncTransfer(session, transferRepo.getForSyncById(userUid, transferId));
                            } catch (Exception ignored) {
                            }
                            refreshBalances.run();
                            Runnable r = refreshListRef.get();
                            if (r != null) {
                                r.run();
                            }
                        } catch (Exception ignored) {
                        }
                    });

                    Button withdraw = new Button("Retirar");
                    withdraw.getStyleClass().add("btn-secondary");
                    withdraw.setOnAction(ev -> {
                        Optional<DashboardGoalsDialog.GoalTransfer> t = DashboardGoalsDialog.showGoalWithdrawDialog(userUid, g, accountRepo, darkTheme);
                        if (t.isEmpty()) {
                            return;
                        }
                        try {
                            DashboardGoalsDialog.GoalTransfer gt = t.get();
                            String transferId = transferRepo.create(userUid, g.accountId(), gt.otherAccountId(), gt.amountCents(), gt.occurredAtEpochSec(), gt.note());
                            try {
                                AppConfig cfg = AppConfig.loadDefault();
                                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                                sync.syncTransfer(session, transferRepo.getForSyncById(userUid, transferId));
                            } catch (Exception ignored) {
                            }
                            refreshBalances.run();
                            Runnable r = refreshListRef.get();
                            if (r != null) {
                                r.run();
                            }
                        } catch (Exception ignored) {
                        }
                    });

                    Button delete = new Button("Eliminar");
                    delete.getStyleClass().add("btn-danger");
                    boolean canDelete = savedCents == 0L || savedCents >= g.targetCents();
                    delete.setDisable(!canDelete);
                    delete.setOnAction(ev -> {
                        if (!canDelete) {
                            Alert alert = buildAlert(
                                AlertType.WARNING,
                                "No se puede eliminar",
                                "Primero retira todo el dinero",
                                "Para eliminar la meta, el saldo guardado debe estar en 0 o la meta debe estar completada.\n\n" +
                                    "Guardado: " + formatMoney(savedCents, g.currency()),
                                darkTheme
                            );
                            alert.showAndWait();
                            return;
                        }
                        Dialog<ButtonType> confirm = new Dialog<>();
                        confirm.setTitle("Eliminar");
                        UiDialogs.applyAppTheme(confirm, darkTheme);
                        confirm.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
                        confirm.setContentText("¿Eliminar la meta '" + g.name() + "'?\n\nNota: la cuenta vinculada no se eliminará automáticamente.");
                        confirm.showAndWait().ifPresent(btn -> {
                            if (btn != ButtonType.OK) {
                                return;
                            }
                            try {
                                goalRepo.delete(userUid, g.id());
                                try {
                                    AppConfig cfg = AppConfig.loadDefault();
                                    FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                                    sync.deleteGoal(session, g.id());
                                } catch (Exception ignored) {
                                }
                                refreshBalances.run();
                                Runnable r = refreshListRef.get();
                                if (r != null) {
                                    r.run();
                                }
                            } catch (Exception ignored) {
                            }
                        });
                    });

                    Region spacer = new Region();
                    HBox.setHgrow(spacer, Priority.ALWAYS);
                    HBox actions = new HBox(8, deposit, withdraw, delete);
                    actions.setAlignment(Pos.CENTER_RIGHT);

                    HBox top = new HBox(10, spacer, actions);
                    top.setAlignment(Pos.CENTER_LEFT);

                    VBox row = new VBox(8, name, amounts, top);
                    row.getStyleClass().add("account-item");
                    list.getChildren().add(row);
                }
            } catch (Exception ex) {
                error.setText(ex.getMessage() == null ? "Error" : ex.getMessage());
                error.setVisible(true);
                error.setManaged(true);
            }
        };
        refreshListRef.set(refreshList);

        Button create = new Button("Nueva meta");
        create.getStyleClass().add("btn-primary");
        create.setOnAction(e -> {
            Optional<DashboardGoalsDialog.NewGoal> ng = DashboardGoalsDialog.showCreateGoalDialog(darkTheme);
            if (ng.isEmpty()) {
                return;
            }
            try {
                DashboardGoalsDialog.NewGoal g = ng.get();
                AccountRepository.Account savings = accountRepo.create(userUid, "Meta: " + g.name(), "SAVINGS", g.currency());
                GoalRepository.Goal created = goalRepo.create(userUid, g.name(), g.currency(), g.targetCents(), g.targetDateEpochSec(), savings.id());
                try {
                    AppConfig cfg = AppConfig.loadDefault();
                    FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                    sync.syncAccount(session, savings);
                    sync.syncGoal(session, created);
                } catch (Exception ignored) {
                }
                refreshBalances.run();
                Runnable r = refreshListRef.get();
                if (r != null) {
                    r.run();
                }
            } catch (Exception ex) {
                error.setText(ex.getMessage() == null ? "No se pudo crear la meta" : ex.getMessage());
                error.setVisible(true);
                error.setManaged(true);
            }
        });

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox headerBar = new HBox(12, headerSpacer, create);
        headerBar.setAlignment(Pos.CENTER_RIGHT);

        refreshList.run();
        VBox out = new VBox(12, headerBar, scroll, error);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return out;
    }

    private static Alert buildAlert(AlertType type, String title, String header, String content, boolean darkTheme) {
        Alert a = new Alert(type);
        a.setTitle(title);

        FontIcon icon;
        String iconClass;
        if (type == AlertType.ERROR) {
            icon = new FontIcon("fas-times-circle");
            iconClass = "text-danger";
        } else if (type == AlertType.WARNING) {
            icon = new FontIcon("fas-exclamation-triangle");
            iconClass = "text-danger";
        } else if (type == AlertType.CONFIRMATION) {
            icon = new FontIcon("fas-question-circle");
            iconClass = "text-secondary";
        } else {
            icon = new FontIcon("fas-info-circle");
            iconClass = "text-secondary";
        }
        icon.setIconSize(22);
        icon.getStyleClass().add(iconClass);

        Label headerLabel = new Label(header == null ? "" : header);
        headerLabel.getStyleClass().add("account-name");

        HBox headerBox = new HBox(10, icon, headerLabel);
        headerBox.setAlignment(Pos.CENTER_LEFT);

        Label contentLabel = new Label(content == null ? "" : content);
        contentLabel.setWrapText(true);
        contentLabel.setMaxWidth(520);

        VBox body = new VBox(10, headerBox, contentLabel);
        body.setPadding(new Insets(4, 0, 0, 0));

        a.setHeaderText(null);
        a.setContentText(null);
        a.getDialogPane().setContent(body);

        a.getDialogPane().setMinWidth(560);
        a.getDialogPane().setPrefWidth(560);
        a.getDialogPane().setGraphic(null);
        UiDialogs.applyAppTheme(a, darkTheme);
        return a;
    }

    private static long safeComputeBalanceCents(AccountRepository accountRepo, String userUid, String accountId) {
        try {
            if (accountRepo == null || userUid == null || accountId == null) {
                return 0L;
            }
            return accountRepo.computeBalanceCents(userUid, accountId);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String formatMoney(long cents, String currencyCode) {
        return DashboardFormatters.formatMoney(cents, currencyCode);
    }

    private static BigDecimal parseAmount(String raw) {
        return DashboardFormatters.parseAmount(raw);
    }

    private static String formatUserDecimal(long cents) {
        return DashboardFormatters.formatUserDecimal(cents);
    }

    private static String formatSignedUserDecimal(long cents) {
        return DashboardFormatters.formatSignedUserDecimal(cents);
    }

    private static Long parseUserDecimalToCents(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }

        s = s.replace(" ", "");
        boolean hasDot = s.indexOf('.') >= 0;
        boolean hasComma = s.indexOf(',') >= 0;
        if (hasDot && hasComma) {
            return null;
        }

        char decSep = hasComma ? ',' : (hasDot ? '.' : 0);
        String intPart;
        String decPart;
        if (decSep == 0) {
            intPart = s;
            decPart = "";
        } else {
            int idx = s.indexOf(decSep);
            intPart = s.substring(0, idx);
            decPart = s.substring(idx + 1);
        }

        if (intPart.isEmpty()) {
            intPart = "0";
        }
        if (!intPart.matches("[0-9]+")) {
            return null;
        }
        if (!decPart.matches("[0-9]*")) {
            return null;
        }
        if (decPart.length() > 2) {
            return null;
        }

        long whole;
        try {
            whole = Long.parseLong(intPart);
        } catch (NumberFormatException ex) {
            return null;
        }

        int dec = 0;
        if (decPart.length() == 1) {
            dec = Integer.parseInt(decPart) * 10;
        } else if (decPart.length() == 2) {
            dec = Integer.parseInt(decPart);
        }
        return whole * 100L + dec;
    }
}
