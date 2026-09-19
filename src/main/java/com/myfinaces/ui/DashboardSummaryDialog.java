package com.myfinaces.ui;

import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.GoalRepository;
import com.myfinaces.db.TransactionRepository;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Screen;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class DashboardSummaryDialog {

    private DashboardSummaryDialog() {
    }

    public static void showSummaryDialog(
        String userUid,
        TransactionRepository txRepo,
        AccountRepository accountRepo,
        CategoryRepository categoryRepo,
        GoalRepository goalRepo,
        boolean darkTheme
    ) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Resumen");
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

                        final double normalW = Math.min(1200, bounds.getWidth() * 0.92);
                        final double normalH = Math.min(820, bounds.getHeight() * 0.90);
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

        Label headerTitle = new Label("Resumen mensual");
        headerTitle.getStyleClass().add("app-title");
        Label headerDesc = new Label("Ingresos y gastos agrupados por categoría, por mes.");
        headerDesc.getStyleClass().add("text-secondary");
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

        ChoiceBox<Integer> year = new ChoiceBox<>();
        ChoiceBox<String> kind = new ChoiceBox<>();
        ChoiceBox<String> view = new ChoiceBox<>();
        ChoiceBox<AccountRepository.Account> account = new ChoiceBox<>();
        ChoiceBox<CategoryRepository.Category> rootCategory = new ChoiceBox<>();
        MenuButton subCategory = new MenuButton("(Todas las subcategorías)");

        Set<String> selectedSubIds = new HashSet<>();

        int currentYear = LocalDate.now().getYear();
        for (int y = currentYear; y >= currentYear - 5; y--) {
            year.getItems().add(y);
        }
        year.getSelectionModel().selectFirst();

        kind.getItems().addAll("Gastos", "Ingresos");
        kind.getSelectionModel().select("Gastos");

        view.getItems().addAll("Categorías", "Subcategorías");
        view.getSelectionModel().select("Categorías");

        try {
            account.getItems().add(null);
            account.getItems().addAll(accountRepo.list(userUid));
            account.getSelectionModel().selectFirst();
        } catch (Exception ignored) {
        }
        account.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AccountRepository.Account object) {
                return object == null ? "(Todas las cuentas)" : object.name();
            }

            @Override
            public AccountRepository.Account fromString(String string) {
                return null;
            }
        });

        try {
            rootCategory.getItems().add(null);
            String kindLabel = kind.getValue();
            String k = "Ingresos".equalsIgnoreCase(kindLabel) ? "INCOME" : "EXPENSE";
            for (CategoryRepository.Category r : categoryRepo.listRoots(userUid)) {
                if (r != null && r.kind() != null && r.kind().equalsIgnoreCase(k)) {
                    rootCategory.getItems().add(r);
                }
            }
            rootCategory.getSelectionModel().selectFirst();
        } catch (Exception ignored) {
        }
        rootCategory.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(CategoryRepository.Category object) {
                return object == null ? "(Todas las categorías)" : object.name();
            }

            @Override
            public CategoryRepository.Category fromString(String string) {
                return null;
            }
        });

        Runnable refreshRootCategories = () -> {
            try {
                CategoryRepository.Category selected = rootCategory.getValue();
                rootCategory.getItems().clear();
                rootCategory.getItems().add(null);

                String kindLabel = kind.getValue();
                String k = "Ingresos".equalsIgnoreCase(kindLabel) ? "INCOME" : "EXPENSE";
                for (CategoryRepository.Category r : categoryRepo.listRoots(userUid)) {
                    if (r != null && r.kind() != null && r.kind().equalsIgnoreCase(k)) {
                        rootCategory.getItems().add(r);
                    }
                }

                if (selected == null) {
                    rootCategory.getSelectionModel().selectFirst();
                    return;
                }
                for (CategoryRepository.Category r : rootCategory.getItems()) {
                    if (r != null && selected.id().equals(r.id())) {
                        rootCategory.getSelectionModel().select(r);
                        return;
                    }
                }
                rootCategory.getSelectionModel().selectFirst();
            } catch (Exception ignored) {
            }
        };

        AtomicReference<Runnable> refreshAccountsForSummaryRef = new AtomicReference<>(null);
        AtomicReference<Runnable> refreshSummaryRef = new AtomicReference<>(null);

        Runnable refreshSubcatsSummary = () -> {
            boolean bySub = "Subcategorías".equalsIgnoreCase(view.getValue());
            CategoryRepository.Category root = rootCategory.getValue();

            subCategory.getItems().clear();
            selectedSubIds.clear();
            subCategory.setText("(Todas las subcategorías)");

            if (!bySub || root == null) {
                subCategory.setDisable(true);
                return;
            }

            subCategory.setDisable(false);

            MenuItem all = new MenuItem("(Todas las subcategorías)");
            all.setOnAction(ev -> {
                selectedSubIds.clear();
                subCategory.setText("(Todas las subcategorías)");
                for (MenuItem mi : subCategory.getItems()) {
                    if (mi instanceof CheckMenuItem cmi) {
                        cmi.setSelected(false);
                    }
                }
                Runnable ra = refreshAccountsForSummaryRef.get();
                if (ra != null) {
                    ra.run();
                }
                Runnable rs = refreshSummaryRef.get();
                if (rs != null) {
                    rs.run();
                }
            });
            subCategory.getItems().add(all);

            try {
                List<CategoryRepository.Category> children = categoryRepo.listChildren(userUid, root.id());
                children.sort((c1, c2) -> c1.name().compareToIgnoreCase(c2.name()));
                for (CategoryRepository.Category c : children) {
                    CheckMenuItem item = new CheckMenuItem(c.name());
                    item.setStyle("-fx-font-weight: bold;");
                    item.setOnAction(ev -> {
                        if (item.isSelected()) {
                            selectedSubIds.add(c.id());
                        } else {
                            selectedSubIds.remove(c.id());
                        }

                        if (selectedSubIds.isEmpty()) {
                            subCategory.setText("(Todas las subcategorías)");
                        } else {
                            subCategory.setText(selectedSubIds.size() + " seleccionadas");
                        }

                        Runnable ra = refreshAccountsForSummaryRef.get();
                        if (ra != null) {
                            ra.run();
                        }
                        Runnable rs = refreshSummaryRef.get();
                        if (rs != null) {
                            rs.run();
                        }
                    });
                    subCategory.getItems().add(item);
                }
            } catch (Exception ignored) {
            }
        };
        refreshSubcatsSummary.run();

        Runnable refreshAccountsForSummary = () -> {
            try {
                AccountRepository.Account selected = account.getValue();

                account.getItems().clear();
                account.getItems().add(null);

                boolean bySub = "Subcategorías".equalsIgnoreCase(view.getValue());
                Integer y = year.getValue();
                String kindLabel = kind.getValue();
                String k = "Ingresos".equalsIgnoreCase(kindLabel) ? "INCOME" : "EXPENSE";

                List<AccountRepository.Account> allAccounts;
                try {
                    allAccounts = accountRepo.list(userUid);
                } catch (Exception ignored) {
                    allAccounts = List.of();
                }

                if (bySub && !selectedSubIds.isEmpty()) {
                    Set<String> idSet = new HashSet<>();
                    for (String subId : selectedSubIds) {
                        try {
                            idSet.addAll(txRepo.listAccountIdsUsedInCategory(userUid, y == null ? currentYear : y, k, subId));
                        } catch (Exception ignored) {
                        }
                    }
                    for (AccountRepository.Account a : allAccounts) {
                        if (a != null && idSet.contains(a.id())) {
                            account.getItems().add(a);
                        }
                    }
                } else {
                    account.getItems().addAll(allAccounts);
                }

                if (selected == null) {
                    account.getSelectionModel().selectFirst();
                    return;
                }
                for (AccountRepository.Account a : account.getItems()) {
                    if (a != null && selected.id().equals(a.id())) {
                        account.getSelectionModel().select(a);
                        return;
                    }
                }
                account.getSelectionModel().selectFirst();
            } catch (Exception ignored) {
            }
        };
        refreshAccountsForSummaryRef.set(refreshAccountsForSummary);

        Label fYear = new Label("Año");
        fYear.getStyleClass().add("account-name");
        Label fKind = new Label("Tipo");
        fKind.getStyleClass().add("account-name");
        Label fView = new Label("Vista");
        fView.getStyleClass().add("account-name");
        Label fRoot = new Label("Categoría");
        fRoot.getStyleClass().add("account-name");
        Label fSub = new Label("Subcategoría");
        fSub.getStyleClass().add("account-name");
        Label fAccount = new Label("Cuenta");
        fAccount.getStyleClass().add("account-name");

        year.setPrefWidth(120);
        kind.setPrefWidth(160);
        view.setPrefWidth(180);
        rootCategory.setPrefWidth(240);
        subCategory.setPrefWidth(260);
        account.setPrefWidth(260);

        HBox pYear = new HBox(8, fYear, year);
        pYear.setAlignment(Pos.CENTER_LEFT);
        HBox pKind = new HBox(8, fKind, kind);
        pKind.setAlignment(Pos.CENTER_LEFT);
        HBox pView = new HBox(8, fView, view);
        pView.setAlignment(Pos.CENTER_LEFT);
        HBox pRoot = new HBox(8, fRoot, rootCategory);
        pRoot.setAlignment(Pos.CENTER_LEFT);
        HBox pSub = new HBox(8, fSub, subCategory);
        pSub.setAlignment(Pos.CENTER_LEFT);
        HBox pAccount = new HBox(8, fAccount, account);
        pAccount.setAlignment(Pos.CENTER_LEFT);

        FlowPane filtersRow = new FlowPane(12, 10);
        filtersRow.getChildren().addAll(pYear, pKind, pView, pRoot, pSub, pAccount);
        VBox filtersCard = new VBox(10, filtersRow);
        filtersCard.getStyleClass().addAll("card", "content-card");
        filtersCard.setPadding(new Insets(10));

        Button toggleFilters = new Button("Ocultar filtros");
        toggleFilters.getStyleClass().add("btn-secondary");
        toggleFilters.setOnAction(e -> {
            boolean show = !filtersCard.isVisible();
            filtersCard.setVisible(show);
            filtersCard.setManaged(show);
            toggleFilters.setText(show ? "Ocultar filtros" : "Mostrar filtros");
        });

        GridPane fixedTable = new GridPane();
        fixedTable.setHgap(10);
        fixedTable.setVgap(8);
        fixedTable.setPadding(new Insets(10));
        fixedTable.setMinWidth(Region.USE_PREF_SIZE);
        fixedTable.getStyleClass().add("summary-table");

        try {
            javafx.scene.layout.ColumnConstraints c0 = new javafx.scene.layout.ColumnConstraints();
            c0.setMinWidth(240);
            c0.setPrefWidth(320);
            c0.setHgrow(Priority.ALWAYS);
            javafx.scene.layout.ColumnConstraints c1 = new javafx.scene.layout.ColumnConstraints();
            c1.setMinWidth(120);
            c1.setPrefWidth(130);
            c1.setHgrow(Priority.NEVER);
            fixedTable.getColumnConstraints().setAll(c0, c1);
        } catch (Exception ignored) {
        }

        GridPane monthsTable = new GridPane();
        monthsTable.setHgap(10);
        monthsTable.setVgap(8);
        monthsTable.setPadding(new Insets(10));
        monthsTable.setMinWidth(Region.USE_PREF_SIZE);
        monthsTable.getStyleClass().add("summary-table");

        ScrollPane fixedScroll = new ScrollPane(fixedTable);
        fixedScroll.setFitToWidth(true);
        fixedScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        fixedScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        fixedScroll.setPannable(false);
        fixedScroll.setMinViewportWidth(480);
        fixedScroll.setPrefViewportWidth(480);

        ScrollPane monthsScroll = new ScrollPane(monthsTable);
        monthsScroll.setFitToHeight(true);
        monthsScroll.setFitToWidth(false);
        monthsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        monthsScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        monthsScroll.setPannable(false);

        fixedScroll.vvalueProperty().bindBidirectional(monthsScroll.vvalueProperty());

        HBox tablesRow = new HBox(0, fixedScroll, monthsScroll);
        HBox.setHgrow(monthsScroll, Priority.ALWAYS);
        tablesRow.getStyleClass().addAll("card", "content-card");
        VBox.setVgrow(tablesRow, Priority.ALWAYS);

        AtomicReference<List<List<String>>> exportRowsRef = new AtomicReference<>(List.of());
        Map<String, Boolean> expandedAccountsBySubId = new HashMap<>();

        Runnable refreshSummary = () -> {
            fixedTable.getChildren().clear();
            monthsTable.getChildren().clear();

            Integer y = year.getValue();
            String kindLabel = kind.getValue();
            String k = "Ingresos".equalsIgnoreCase(kindLabel) ? "INCOME" : "EXPENSE";
            boolean bySubcategory = "Subcategorías".equalsIgnoreCase(view.getValue());
            int currentMonth = LocalDate.now().getMonthValue();
            AccountRepository.Account a = account.getValue();
            String accountId = a == null ? null : a.id();
            String currencyCode = "COP";

            int monthsElapsed;
            int selectedYear = y == null ? currentYear : y;
            if (selectedYear == currentYear) {
                monthsElapsed = Math.max(1, Math.min(12, currentMonth - 1));
            } else {
                monthsElapsed = 12;
            }

            List<List<String>> exportRows = new ArrayList<>();

            CategoryRepository.Category rootFilter = rootCategory.getValue();
            Set<String> subFilterIds = bySubcategory ? new HashSet<>(selectedSubIds) : Set.of();

            List<CategoryRepository.Category> roots;
            try {
                roots = new ArrayList<>();
                for (CategoryRepository.Category r : categoryRepo.listRoots(userUid)) {
                    if (r != null && r.kind() != null && r.kind().equalsIgnoreCase(k)) {
                        roots.add(r);
                    }
                }
            } catch (Exception ignored) {
                roots = List.of();
            }

            if (bySubcategory) {
                Map<String, Map<String, String>> subNameByRoot = new HashMap<>();
                Map<String, Map<String, long[]>> byRootSub = new HashMap<>();
                Map<String, Map<String, Map<String, long[]>>> byRootSubAccount = new HashMap<>();
                Map<String, String> accountNameById = new HashMap<>();
                for (CategoryRepository.Category r : roots) {
                    byRootSub.put(r.id(), new HashMap<>());
                    subNameByRoot.put(r.id(), new HashMap<>());
                    try {
                        List<CategoryRepository.Category> children = categoryRepo.listChildren(userUid, r.id());
                        for (CategoryRepository.Category c : children) {
                            subNameByRoot.get(r.id()).put(c.id(), c.name());
                            byRootSub.get(r.id()).put(c.id(), new long[13]);
                            byRootSubAccount.computeIfAbsent(r.id(), __ -> new HashMap<>()).put(c.id(), new HashMap<>());
                        }
                    } catch (Exception ignored) {
                    }
                    subNameByRoot.get(r.id()).put(r.id() + ":NONE", "(Sin subcategoría)");
                    byRootSub.get(r.id()).put(r.id() + ":NONE", new long[13]);
                    byRootSubAccount.computeIfAbsent(r.id(), __ -> new HashMap<>()).put(r.id() + ":NONE", new HashMap<>());
                }

                try {
                    if (accountId == null) {
                        List<TransactionRepository.MonthlyCategoryDetailAccountTotal> rows = txRepo.listMonthlyTotalsBySubcategoryAndAccount(userUid, selectedYear, k);
                        for (TransactionRepository.MonthlyCategoryDetailAccountTotal row : rows) {
                            Map<String, long[]> subs = byRootSub.computeIfAbsent(row.rootCategoryId(), __ -> new HashMap<>());
                            long[] months = subs.computeIfAbsent(row.categoryId(), __ -> new long[13]);
                            int m = row.month();
                            if (m >= 1 && m <= 12) {
                                months[m] += row.totalAmountCents();
                            }

                            Map<String, Map<String, long[]>> subsByAcc = byRootSubAccount
                                .computeIfAbsent(row.rootCategoryId(), __ -> new HashMap<>());
                            Map<String, long[]> accs = subsByAcc.computeIfAbsent(row.categoryId(), __ -> new HashMap<>());
                            long[] accMonths = accs.computeIfAbsent(row.accountId(), __ -> new long[13]);
                            if (m >= 1 && m <= 12) {
                                accMonths[m] += row.totalAmountCents();
                            }

                            subNameByRoot.computeIfAbsent(row.rootCategoryId(), __ -> new HashMap<>())
                                .putIfAbsent(row.categoryId(), row.categoryName());
                            if (row.accountId() != null && row.accountName() != null) {
                                accountNameById.putIfAbsent(row.accountId(), row.accountName());
                            }
                        }
                    } else {
                        List<TransactionRepository.MonthlyCategoryDetailTotal> rows = txRepo.listMonthlyTotalsBySubcategory(userUid, accountId, selectedYear, k);
                        for (TransactionRepository.MonthlyCategoryDetailTotal row : rows) {
                            Map<String, long[]> subs = byRootSub.computeIfAbsent(row.rootCategoryId(), __ -> new HashMap<>());
                            long[] months = subs.computeIfAbsent(row.categoryId(), __ -> new long[13]);
                            int m = row.month();
                            if (m >= 1 && m <= 12) {
                                months[m] = row.totalAmountCents();
                            }
                            subNameByRoot.computeIfAbsent(row.rootCategoryId(), __ -> new HashMap<>())
                                .putIfAbsent(row.categoryId(), row.categoryName());
                        }
                    }
                } catch (Exception ignored) {
                }

                String[] monthNames = new String[] {
                    "DESCRIPCIÓN",
                    "ENERO",
                    "FEBRERO",
                    "MARZO",
                    "ABRIL",
                    "MAYO",
                    "JUNIO",
                    "JULIO",
                    "AGOSTO",
                    "SEPTIEMBRE",
                    "OCTUBRE",
                    "NOVIEMBRE",
                    "DICIEMBRE",
                    "TOTAL",
                    "PROMEDIO"
                };

                exportRows.add(List.of(monthNames));

                for (int col = 0; col < monthNames.length; col++) {
                    Label h = new Label(monthNames[col]);
                    h.getStyleClass().add("account-name");
                    h.getStyleClass().add("summary-header-cell");
                    if (col == currentMonth) {
                        h.getStyleClass().add("summary-current-month");
                    }
                    if (col == 0) {
                        fixedTable.add(h, 0, 0);
                    } else if (col >= 1 && col <= 12) {
                        monthsTable.add(h, col - 1, 0);
                    } else if (col == 13) {
                        fixedTable.add(h, 1, 0);
                    } else if (col == 14) {
                        monthsTable.add(h, 12, 0);
                    }
                }

                long[] totalByMonth = new long[13];
                int rowIdx = 1;
                long grandTotal = 0;
                for (CategoryRepository.Category r : roots) {
                    if (rootFilter != null && !rootFilter.id().equals(r.id())) {
                        continue;
                    }

                    Label rootName = new Label(r.name());
                    rootName.getStyleClass().add("account-name");
                    rootName.getStyleClass().add("summary-root-name");
                    String zebra = (rowIdx % 2 == 0) ? "summary-row-even" : "summary-row-odd";
                    rootName.getStyleClass().add(zebra);
                    fixedTable.add(rootName, 0, rowIdx);
                    for (int m = 1; m <= 12; m++) {
                        Label v = new Label(" ");
                        v.getStyleClass().add(zebra);
                        if (m == currentMonth) {
                            v.getStyleClass().add("summary-current-month");
                        }
                        monthsTable.add(v, m - 1, rowIdx);
                    }
                    Label rootTotalCell = new Label(" ");
                    rootTotalCell.getStyleClass().add(zebra);
                    rootTotalCell.getStyleClass().add("summary-total-col");
                    fixedTable.add(rootTotalCell, 1, rowIdx);
                    Label rootAvgCell = new Label(" ");
                    rootAvgCell.getStyleClass().add(zebra);
                    rootAvgCell.getStyleClass().add("summary-avg-col");
                    monthsTable.add(rootAvgCell, 12, rowIdx);

                    rowIdx++;

                    List<String> keys = new ArrayList<>(subNameByRoot.getOrDefault(r.id(), Map.of()).keySet());
                    keys.sort((a1, a2) -> {
                        String n1 = subNameByRoot.get(r.id()).getOrDefault(a1, a1);
                        String n2 = subNameByRoot.get(r.id()).getOrDefault(a2, a2);
                        return n1.compareToIgnoreCase(n2);
                    });

                    for (String subId : keys) {
                        if (!subFilterIds.isEmpty() && !subFilterIds.contains(subId)) {
                            continue;
                        }
                        long[] months = byRootSub.get(r.id()).getOrDefault(subId, new long[13]);
                        boolean any = false;
                        for (int m = 1; m <= 12; m++) {
                            if (months[m] != 0) {
                                any = true;
                                break;
                            }
                        }
                        if (!any) {
                            continue;
                        }

                        String subLabel = subNameByRoot.get(r.id()).getOrDefault(subId, subId);
                        boolean canToggleAccounts = accountId == null;
                        boolean expanded = expandedAccountsBySubId.getOrDefault(subId, false);
                        String chevron = canToggleAccounts ? (expanded ? "▼" : "▶") : "";
                        Label name = new Label("  - " + (canToggleAccounts ? (chevron + " ") : "") + subLabel);
                        name.getStyleClass().add("text-secondary");
                        name.getStyleClass().add("summary-sub-name");
                        name.setStyle("-fx-font-weight: bold;");
                        name.setMaxWidth(320);
                        name.setTextOverrun(OverrunStyle.ELLIPSIS);
                        Tooltip.install(name, new Tooltip(subLabel));
                        String zebraSub = (rowIdx % 2 == 0) ? "summary-row-even" : "summary-row-odd";
                        name.getStyleClass().add(zebraSub);
                        fixedTable.add(name, 0, rowIdx);

                        long rowTotal = 0;
                        for (int m = 1; m <= 12; m++) {
                            totalByMonth[m] += months[m];
                            rowTotal += months[m];
                            Label v = new Label(DashboardFormatters.formatMoney(months[m], currencyCode));
                            v.setMinWidth(100);
                            v.setAlignment(Pos.CENTER_RIGHT);
                            v.getStyleClass().add("summary-amount-cell");
                            v.getStyleClass().add(zebraSub);
                            v.setStyle("-fx-font-weight: bold;");
                            if (m == currentMonth) {
                                v.getStyleClass().add("summary-current-month");
                            }
                            monthsTable.add(v, m - 1, rowIdx);
                        }

                        grandTotal += rowTotal;
                        Label totalCell = new Label(DashboardFormatters.formatMoney(rowTotal, currencyCode));
                        totalCell.setMinWidth(100);
                        totalCell.setAlignment(Pos.CENTER_RIGHT);
                        totalCell.getStyleClass().add("summary-amount-cell");
                        totalCell.getStyleClass().add("summary-total-col");
                        totalCell.getStyleClass().add(zebraSub);
                        totalCell.setStyle("-fx-font-weight: bold;");
                        fixedTable.add(totalCell, 1, rowIdx);

                        long avgBase = rowTotal;
                        if (selectedYear == currentYear) {
                            long prevSum = 0;
                            for (int m = 1; m < currentMonth; m++) {
                                prevSum += months[m];
                            }
                            avgBase = prevSum;
                        }
                        long avgCents = monthsElapsed <= 0 ? 0 : (avgBase / monthsElapsed);
                        Label avgCell = new Label(DashboardFormatters.formatMoney(avgCents, currencyCode));
                        avgCell.setMinWidth(100);
                        avgCell.setAlignment(Pos.CENTER_RIGHT);
                        avgCell.getStyleClass().add("summary-amount-cell");
                        avgCell.getStyleClass().add("summary-avg-col");
                        avgCell.getStyleClass().add(zebraSub);
                        avgCell.setStyle("-fx-font-weight: bold;");
                        monthsTable.add(avgCell, 12, rowIdx);

                        List<String> exportRow = new ArrayList<>();
                        exportRow.add(subNameByRoot.get(r.id()).getOrDefault(subId, subId));
                        for (int m = 1; m <= 12; m++) {
                            exportRow.add(DashboardFormatters.formatMoney(months[m], currencyCode));
                        }
                        exportRow.add(DashboardFormatters.formatMoney(rowTotal, currencyCode));
                        exportRow.add(DashboardFormatters.formatMoney(avgCents, currencyCode));
                        exportRows.add(exportRow);
                        rowIdx++;

                        if (accountId == null) {
                            List<Node> accountRowNodes = new ArrayList<>();
                            Map<String, long[]> accs = byRootSubAccount
                                .getOrDefault(r.id(), Map.of())
                                .getOrDefault(subId, Map.of());
                            List<String> accountIds = new ArrayList<>(accs.keySet());
                            accountIds.sort((a1, a2) -> {
                                String n1 = accountNameById.getOrDefault(a1, a1);
                                String n2 = accountNameById.getOrDefault(a2, a2);
                                return n1.compareToIgnoreCase(n2);
                            });
                            for (String accId : accountIds) {
                                long[] am = accs.getOrDefault(accId, new long[13]);
                                boolean anyAcc = false;
                                for (int m = 1; m <= 12; m++) {
                                    if (am[m] != 0) {
                                        anyAcc = true;
                                        break;
                                    }
                                }
                                if (!anyAcc) {
                                    continue;
                                }

                                String accName = accountNameById.getOrDefault(accId, accId);
                                Label accLabel = new Label("      • " + accName);
                                accLabel.getStyleClass().add("text-secondary");
                                accLabel.getStyleClass().add("summary-sub-name");
                                accLabel.setMaxWidth(320);
                                accLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
                                Tooltip.install(accLabel, new Tooltip(accName));
                                String zebraAcc = (rowIdx % 2 == 0) ? "summary-row-even" : "summary-row-odd";
                                accLabel.getStyleClass().add(zebraAcc);
                                fixedTable.add(accLabel, 0, rowIdx);
                                accountRowNodes.add(accLabel);

                                long accTotal = 0;
                                for (int m = 1; m <= 12; m++) {
                                    accTotal += am[m];
                                    Label vv = new Label(DashboardFormatters.formatMoney(am[m], currencyCode));
                                    vv.setMinWidth(100);
                                    vv.setAlignment(Pos.CENTER_RIGHT);
                                    vv.getStyleClass().add("summary-amount-cell");
                                    vv.getStyleClass().add(zebraAcc);
                                    if (m == currentMonth) {
                                        vv.getStyleClass().add("summary-current-month");
                                    }
                                    monthsTable.add(vv, m - 1, rowIdx);
                                    accountRowNodes.add(vv);
                                }

                                Label accTotalCell = new Label(DashboardFormatters.formatMoney(accTotal, currencyCode));
                                accTotalCell.setMinWidth(100);
                                accTotalCell.setAlignment(Pos.CENTER_RIGHT);
                                accTotalCell.getStyleClass().add("summary-amount-cell");
                                accTotalCell.getStyleClass().add("summary-total-col");
                                accTotalCell.getStyleClass().add(zebraAcc);
                                fixedTable.add(accTotalCell, 1, rowIdx);
                                accountRowNodes.add(accTotalCell);

                                long accAvgBase = accTotal;
                                if (selectedYear == currentYear) {
                                    long prevSum = 0;
                                    for (int m = 1; m < currentMonth; m++) {
                                        prevSum += am[m];
                                    }
                                    accAvgBase = prevSum;
                                }
                                long accAvg = monthsElapsed <= 0 ? 0 : (accAvgBase / monthsElapsed);
                                Label accAvgCell = new Label(DashboardFormatters.formatMoney(accAvg, currencyCode));
                                accAvgCell.setMinWidth(100);
                                accAvgCell.setAlignment(Pos.CENTER_RIGHT);
                                accAvgCell.getStyleClass().add("summary-amount-cell");
                                accAvgCell.getStyleClass().add("summary-avg-col");
                                accAvgCell.getStyleClass().add(zebraAcc);
                                monthsTable.add(accAvgCell, 12, rowIdx);
                                accountRowNodes.add(accAvgCell);

                                rowIdx++;
                            }

                            boolean initialExpanded = expandedAccountsBySubId.getOrDefault(subId, false);
                            for (Node n : accountRowNodes) {
                                n.setVisible(initialExpanded);
                                n.setManaged(initialExpanded);
                            }

                            if (canToggleAccounts) {
                                name.setOnMouseClicked(ev -> {
                                    boolean cur = expandedAccountsBySubId.getOrDefault(subId, false);
                                    boolean newV = !cur;
                                    expandedAccountsBySubId.put(subId, newV);
                                    String ch = newV ? "▼" : "▶";
                                    name.setText("  - " + ch + " " + subLabel);
                                    for (Node n : accountRowNodes) {
                                        n.setVisible(newV);
                                        n.setManaged(newV);
                                    }
                                });
                            }
                        }
                    }
                }

                Label totalName = new Label("TOTAL");
                totalName.getStyleClass().add("account-name");
                totalName.getStyleClass().add("summary-total-name");
                fixedTable.add(totalName, 0, rowIdx);
                for (int m = 1; m <= 12; m++) {
                    Label v = new Label(DashboardFormatters.formatMoney(totalByMonth[m], currencyCode));
                    v.getStyleClass().add("account-name");
                    v.setMinWidth(100);
                    v.setAlignment(Pos.CENTER_RIGHT);
                    v.getStyleClass().add("summary-total-amount");
                    if (m == currentMonth) {
                        v.getStyleClass().add("summary-current-month");
                    }
                    monthsTable.add(v, m - 1, rowIdx);
                }

                Label grand = new Label(DashboardFormatters.formatMoney(grandTotal, currencyCode));
                grand.getStyleClass().add("account-name");
                grand.setMinWidth(100);
                grand.setAlignment(Pos.CENTER_RIGHT);
                grand.getStyleClass().add("summary-total-amount");
                grand.getStyleClass().add("summary-total-col");
                fixedTable.add(grand, 1, rowIdx);

                long avgTotalBase = grandTotal;
                if (selectedYear == currentYear) {
                    long prevSum = 0;
                    for (int m = 1; m < currentMonth; m++) {
                        prevSum += totalByMonth[m];
                    }
                    avgTotalBase = prevSum;
                }
                long avgTotal = monthsElapsed <= 0 ? 0 : (avgTotalBase / monthsElapsed);
                Label grandAvg = new Label(DashboardFormatters.formatMoney(avgTotal, currencyCode));
                grandAvg.getStyleClass().add("account-name");
                grandAvg.setMinWidth(100);
                grandAvg.setAlignment(Pos.CENTER_RIGHT);
                grandAvg.getStyleClass().add("summary-total-amount");
                grandAvg.getStyleClass().add("summary-avg-col");
                monthsTable.add(grandAvg, 12, rowIdx);

                List<String> totalExportRow = new ArrayList<>();
                totalExportRow.add("TOTAL");
                for (int m = 1; m <= 12; m++) {
                    totalExportRow.add(DashboardFormatters.formatMoney(totalByMonth[m], currencyCode));
                }
                totalExportRow.add(DashboardFormatters.formatMoney(grandTotal, currencyCode));
                totalExportRow.add(DashboardFormatters.formatMoney(avgTotal, currencyCode));
                exportRows.add(totalExportRow);

                exportRowsRef.set(exportRows);

                applySummaryRowHover(fixedTable, monthsTable);

                return;
            }

            Map<String, long[]> byRoot = new HashMap<>();
            for (CategoryRepository.Category r : roots) {
                byRoot.put(r.id(), new long[13]);
            }

            try {
                List<TransactionRepository.MonthlyCategoryTotal> rows = txRepo.listMonthlyTotalsByRootCategory(userUid, accountId, y == null ? currentYear : y, k);
                for (TransactionRepository.MonthlyCategoryTotal row : rows) {
                    long[] months = byRoot.computeIfAbsent(row.rootCategoryId(), __ -> new long[13]);
                    int m = row.month();
                    if (m >= 1 && m <= 12) {
                        months[m] = row.totalAmountCents();
                    }
                }
            } catch (Exception ignored) {
            }

            String[] monthNames = new String[] {
                "DESCRIPCIÓN",
                "ENERO",
                "FEBRERO",
                "MARZO",
                "ABRIL",
                "MAYO",
                "JUNIO",
                "JULIO",
                "AGOSTO",
                "SEPTIEMBRE",
                "OCTUBRE",
                "NOVIEMBRE",
                "DICIEMBRE",
                "TOTAL",
                "PROMEDIO"
            };

            exportRows.add(List.of(monthNames));

            for (int col = 0; col < monthNames.length; col++) {
                Label h = new Label(monthNames[col]);
                h.getStyleClass().add("account-name");
                h.getStyleClass().add("summary-header-cell");
                if (col == currentMonth) {
                    h.getStyleClass().add("summary-current-month");
                }
                if (col == 0) {
                    fixedTable.add(h, 0, 0);
                } else if (col >= 1 && col <= 12) {
                    monthsTable.add(h, col - 1, 0);
                } else if (col == 13) {
                    fixedTable.add(h, 1, 0);
                } else if (col == 14) {
                    monthsTable.add(h, 12, 0);
                }
            }

            long[] totalByMonth = new long[13];
            int rowIdx = 1;
            long grandTotal = 0;
            for (CategoryRepository.Category r : roots) {
                if (rootFilter != null && !rootFilter.id().equals(r.id())) {
                    continue;
                }
                long[] months = byRoot.getOrDefault(r.id(), new long[13]);

                Label name = new Label(r.name());
                name.getStyleClass().add("account-name");
                name.getStyleClass().add("summary-root-name");
                String zebra = (rowIdx % 2 == 0) ? "summary-row-even" : "summary-row-odd";
                name.getStyleClass().add(zebra);
                fixedTable.add(name, 0, rowIdx);

                long rowTotal = 0;
                for (int m = 1; m <= 12; m++) {
                    totalByMonth[m] += months[m];
                    rowTotal += months[m];
                    Label v = new Label(DashboardFormatters.formatMoney(months[m], currencyCode));
                    v.setMinWidth(100);
                    v.setAlignment(Pos.CENTER_RIGHT);
                    v.getStyleClass().add("summary-amount-cell");
                    v.getStyleClass().add(zebra);
                    if (m == currentMonth) {
                        v.getStyleClass().add("summary-current-month");
                    }
                    monthsTable.add(v, m - 1, rowIdx);
                }

                grandTotal += rowTotal;
                Label totalCell = new Label(DashboardFormatters.formatMoney(rowTotal, currencyCode));
                totalCell.setMinWidth(100);
                totalCell.setAlignment(Pos.CENTER_RIGHT);
                totalCell.getStyleClass().add("summary-amount-cell");
                totalCell.getStyleClass().add("summary-total-col");
                totalCell.getStyleClass().add(zebra);
                fixedTable.add(totalCell, 1, rowIdx);

                long avgBase = rowTotal;
                if (selectedYear == currentYear) {
                    long prevSum = 0;
                    for (int m = 1; m < currentMonth; m++) {
                        prevSum += months[m];
                    }
                    avgBase = prevSum;
                }
                long avgCents = monthsElapsed <= 0 ? 0 : (avgBase / monthsElapsed);
                Label avgCell = new Label(DashboardFormatters.formatMoney(avgCents, currencyCode));
                avgCell.setMinWidth(100);
                avgCell.setAlignment(Pos.CENTER_RIGHT);
                avgCell.getStyleClass().add("summary-amount-cell");
                avgCell.getStyleClass().add("summary-avg-col");
                avgCell.getStyleClass().add(zebra);
                monthsTable.add(avgCell, 12, rowIdx);

                List<String> exportRow = new ArrayList<>();
                exportRow.add(r.name());
                for (int m = 1; m <= 12; m++) {
                    exportRow.add(DashboardFormatters.formatMoney(months[m], currencyCode));
                }
                exportRow.add(DashboardFormatters.formatMoney(rowTotal, currencyCode));
                exportRow.add(DashboardFormatters.formatMoney(avgCents, currencyCode));
                exportRows.add(exportRow);
                rowIdx++;
            }

            Label totalName = new Label("TOTAL");
            totalName.getStyleClass().add("account-name");
            totalName.getStyleClass().add("summary-total-name");
            fixedTable.add(totalName, 0, rowIdx);
            for (int m = 1; m <= 12; m++) {
                Label v = new Label(DashboardFormatters.formatMoney(totalByMonth[m], currencyCode));
                v.getStyleClass().add("account-name");
                v.setMinWidth(100);
                v.setAlignment(Pos.CENTER_RIGHT);
                v.getStyleClass().add("summary-total-amount");
                if (m == currentMonth) {
                    v.getStyleClass().add("summary-current-month");
                }
                monthsTable.add(v, m - 1, rowIdx);
            }

            Label grand = new Label(DashboardFormatters.formatMoney(grandTotal, currencyCode));
            grand.getStyleClass().add("account-name");
            grand.setMinWidth(100);
            grand.setAlignment(Pos.CENTER_RIGHT);
            grand.getStyleClass().add("summary-total-amount");
            grand.getStyleClass().add("summary-total-col");
            fixedTable.add(grand, 1, rowIdx);

            int totalMonthCount = 0;
            for (int m = 1; m <= 12; m++) {
                if (totalByMonth[m] != 0) {
                    totalMonthCount++;
                }
            }
            long avgTotal = totalMonthCount == 0 ? 0 : (grandTotal / totalMonthCount);
            Label grandAvg = new Label(DashboardFormatters.formatMoney(avgTotal, currencyCode));
            grandAvg.getStyleClass().add("account-name");
            grandAvg.setMinWidth(100);
            grandAvg.setAlignment(Pos.CENTER_RIGHT);
            grandAvg.getStyleClass().add("summary-total-amount");
            grandAvg.getStyleClass().add("summary-avg-col");
            monthsTable.add(grandAvg, 12, rowIdx);

            List<String> totalExportRow = new ArrayList<>();
            totalExportRow.add("TOTAL");
            for (int m = 1; m <= 12; m++) {
                totalExportRow.add(DashboardFormatters.formatMoney(totalByMonth[m], currencyCode));
            }
            totalExportRow.add(DashboardFormatters.formatMoney(grandTotal, currencyCode));
            totalExportRow.add(DashboardFormatters.formatMoney(avgTotal, currencyCode));
            exportRows.add(totalExportRow);

            exportRowsRef.set(exportRows);

            applySummaryRowHover(fixedTable, monthsTable);
        };
        refreshSummaryRef.set(refreshSummary);

        year.valueProperty().addListener((obs, o, n) -> refreshSummary.run());
        kind.valueProperty().addListener((obs, o, n) -> {
            refreshRootCategories.run();
            refreshSubcatsSummary.run();
            refreshAccountsForSummary.run();
            refreshSummary.run();
        });
        view.valueProperty().addListener((obs, o, n) -> {
            refreshSubcatsSummary.run();
            refreshAccountsForSummary.run();
            refreshSummary.run();
        });
        account.valueProperty().addListener((obs, o, n) -> refreshSummary.run());
        rootCategory.valueProperty().addListener((obs, o, n) -> {
            refreshSubcatsSummary.run();
            refreshAccountsForSummary.run();
            refreshSummary.run();
        });
        year.valueProperty().addListener((obs, o, n) -> refreshAccountsForSummary.run());
        kind.valueProperty().addListener((obs, o, n) -> refreshAccountsForSummary.run());

        refreshAccountsForSummary.run();

        Button exportCsv = new Button("Exportar CSV");
        exportCsv.getStyleClass().add("btn-secondary");
        exportCsv.setOnAction(e -> {
            try {
                String y = year.getValue() == null ? String.valueOf(currentYear) : String.valueOf(year.getValue());
                String k = kind.getValue() == null ? "" : kind.getValue();
                String v = view.getValue() == null ? "" : view.getValue();

                FileChooser chooser = new FileChooser();
                chooser.setTitle("Exportar resumen a CSV");
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
                chooser.setInitialFileName("resumen_" + y + "_" + k + "_" + v + ".csv");
                java.io.File out = chooser.showSaveDialog(dialog.getDialogPane().getScene().getWindow());
                if (out == null) {
                    return;
                }

                List<List<String>> rows = exportRowsRef.get();
                StringBuilder sb = new StringBuilder();
                for (List<String> row : rows) {
                    for (int i = 0; i < row.size(); i++) {
                        if (i > 0) {
                            sb.append(';');
                        }
                        String cell = row.get(i) == null ? "" : row.get(i);
                        cell = cell.replace("\"", "\"\"");
                        sb.append('"').append(cell).append('"');
                    }
                    sb.append("\r\n");
                }
                Path p = out.toPath();
                Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
        });

        HBox actionsRow = new HBox(10, toggleFilters, exportCsv);
        actionsRow.setAlignment(Pos.CENTER_LEFT);

        GridPane goalsTable = new GridPane();
        goalsTable.setHgap(10);
        goalsTable.setVgap(8);
        goalsTable.setPadding(new Insets(10));

        VBox goalsCard = new VBox(10, new Label("Metas"), goalsTable);
        goalsCard.getStyleClass().addAll("card", "content-card");
        goalsCard.getChildren().getFirst().getStyleClass().add("account-name");

        Runnable refreshGoals = () -> {
            goalsTable.getChildren().clear();
            try {
                if (goalRepo == null) {
                    return;
                }
                List<GoalRepository.Goal> goals = goalRepo.listByUser(userUid).stream()
                    .filter(g -> GoalRepository.STATUS_OPEN.equals(g.status()))
                    .toList();
                if (goals.isEmpty()) {
                    goalsTable.add(new Label("Sin metas"), 0, 0);
                    return;
                }

                Label h1 = new Label("Meta");
                Label h2 = new Label("Guardado");
                Label h3 = new Label("Objetivo");
                Label h4 = new Label("Falta");
                Label h5 = new Label("%");
                h1.getStyleClass().add("text-secondary");
                h2.getStyleClass().add("text-secondary");
                h3.getStyleClass().add("text-secondary");
                h4.getStyleClass().add("text-secondary");
                h5.getStyleClass().add("text-secondary");
                goalsTable.add(h1, 0, 0);
                goalsTable.add(h2, 1, 0);
                goalsTable.add(h3, 2, 0);
                goalsTable.add(h4, 3, 0);
                goalsTable.add(h5, 4, 0);

                int row = 1;
                for (GoalRepository.Goal g : goals) {
                    long savedCents;
                    try {
                        savedCents = accountRepo.computeBalanceCents(userUid, g.accountId());
                    } catch (Exception ignored) {
                        savedCents = 0L;
                    }
                    long remaining = Math.max(0L, g.targetCents() - savedCents);
                    double pct = g.targetCents() <= 0 ? 0.0 : Math.min(1.0, (double) savedCents / (double) g.targetCents());

                    Label n = new Label(g.name());
                    Label saved = new Label(DashboardFormatters.formatMoney(savedCents, g.currency()));
                    Label target = new Label(DashboardFormatters.formatMoney(g.targetCents(), g.currency()));
                    Label falta = new Label(DashboardFormatters.formatMoney(remaining, g.currency()));
                    Label p = new Label(String.format(java.util.Locale.ROOT, "%.0f%%", pct * 100.0));

                    goalsTable.add(n, 0, row);
                    goalsTable.add(saved, 1, row);
                    goalsTable.add(target, 2, row);
                    goalsTable.add(falta, 3, row);
                    goalsTable.add(p, 4, row);
                    row++;
                }
            } catch (Exception ignored) {
            }
        };
        refreshGoals.run();

        VBox body = new VBox(12, actionsRow, filtersCard, tablesRow, goalsCard);
        body.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(body);

        refreshSummary.run();
        dialog.showAndWait();
    }

    private static void applySummaryRowHover(GridPane fixedTable, GridPane monthsTable) {
        Map<Integer, List<Node>> nodesByRow = new HashMap<>();

        for (Node n : fixedTable.getChildren()) {
            Integer r = GridPane.getRowIndex(n);
            int row = r == null ? 0 : r;
            nodesByRow.computeIfAbsent(row, __ -> new ArrayList<>()).add(n);
        }

        for (Node n : monthsTable.getChildren()) {
            Integer r = GridPane.getRowIndex(n);
            int row = r == null ? 0 : r;
            nodesByRow.computeIfAbsent(row, __ -> new ArrayList<>()).add(n);
        }

        for (Map.Entry<Integer, List<Node>> e : nodesByRow.entrySet()) {
            int row = e.getKey();
            if (row <= 0) {
                continue;
            }
            List<Node> rowNodes = e.getValue();
            for (Node n : rowNodes) {
                n.setOnMouseEntered(ev -> {
                    for (Node x : rowNodes) {
                        if (!x.getStyleClass().contains("summary-row-hover")) {
                            x.getStyleClass().add("summary-row-hover");
                        }
                    }
                });
                n.setOnMouseExited(ev -> {
                    for (Node x : rowNodes) {
                        x.getStyleClass().remove("summary-row-hover");
                    }
                });
            }
        }
    }
}
