package com.myfinaces.ui;

import com.myfinaces.auth.AuthSession;
import com.myfinaces.config.AccountStyles;
import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.BudgetRepository;
import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.GoalRepository;
import com.myfinaces.db.TransferRepository;
import com.myfinaces.service.GoalService;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.PauseTransition;
import javafx.animation.RotateTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.text.NumberFormat;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.Consumer;

public final class BudgetView {

    private BudgetView() {
    }

    // ── Account avatar helpers for dropdown rendering ─────────────────────────
    private static String resolveAccountColor(String typeKey, String storedColor) {
        return AccountStyles.resolveColor(typeKey, storedColor);
    }

    private static String resolveAccountTypeIcon(String typeKey) {
        return AccountStyles.resolveIcon(typeKey);
    }

    private static String accountTypeLabelShort(String type) {
        String t = AccountRepository.normalizeType(type);
        return switch (t) {
            case "VIRTUAL_WALLET"  -> "Billetera";
            case "DIGITAL_ACCOUNT" -> "Digital";
            default                -> AccountStyles.resolveLabel(t);
        };
    }

    private static StackPane buildAccountAvatar(String typeKey, String storedColor) {
        String hex = resolveAccountColor(typeKey, storedColor);
        Circle bg = new Circle(14);
        try {
            Color base = Color.web(hex);
            bg.setFill(base.deriveColor(0, 1.0, 1.0, 0.18));
        } catch (Exception ignored) {
            bg.setFill(Color.web(AccountStyles.BANK.color(), 0.18));
        }
        FontIcon icon = new FontIcon(resolveAccountTypeIcon(typeKey));
        icon.setIconSize(11);
        try {
            icon.setIconColor(Color.web(hex));
        } catch (Exception ignored) {
            icon.setIconColor(Color.web(AccountStyles.BANK.color()));
        }
        StackPane avatar = new StackPane(bg, icon);
        avatar.setMinSize(28, 28);
        avatar.setPrefSize(28, 28);
        avatar.setMaxSize(28, 28);
        avatar.setAlignment(Pos.CENTER);
        return avatar;
    }

    private static Node buildMonthlySummaryCard(
            String userUid, BudgetRepository budgetRepo, String month, String currency,
            List<YearMonth> availableMonths, Consumer<YearMonth> onMonthChange) {

        long totalLimit = 0L, totalSpent = 0L;
        try {
            List<BudgetRepository.BudgetProgress> rows =
                budgetRepo.listProgressByMonthAndCurrency(userUid, month, currency);
            for (BudgetRepository.BudgetProgress p : rows) {
                totalLimit += p.budget().limitCents();
                totalSpent += p.spentCents();
            }
        } catch (Exception ignored) {
        }

        long totalAvailable = totalLimit - totalSpent;
        int pct = totalLimit > 0 ? (int) Math.min(100, totalSpent * 100L / totalLimit) : 0;
        String pctColor      = budgetStateColor(pct);
        boolean isOverBudget = totalAvailable < 0;
        String availColor    = isOverBudget ? "#EF4444" : "#22C55E";
        String availIcon     = isOverBudget ? "fas-exclamation-circle" : "fas-check-circle";

        // ── Bloque izquierdo: título + mes ───────────────────────
        FontIcon calIcon = new FontIcon("fas-calendar-alt");
        calIcon.setIconSize(16);
        calIcon.setIconColor(javafx.scene.paint.Color.web("#2563EB"));

        Label headerSmall = new Label("Presupuesto de");
        headerSmall.getStyleClass().add("text-secondary");
        headerSmall.setStyle("-fx-font-size: 11px;");

        YearMonth ym = YearMonth.parse(month);
        String monthName = ym.getMonth().getDisplayName(TextStyle.FULL, Locale.forLanguageTag("es"));
        String displayMonth = monthName.substring(0, 1).toUpperCase(Locale.ROOT)
            + monthName.substring(1) + " " + ym.getYear();
        Label monthLabel = new Label(displayMonth);
        monthLabel.setStyle("-fx-font-size: 20px; -fx-font-weight: 900;");

        Label statusBadge = new Label(
            pct < 60 ? "✓ En control" : pct <= 85 ? "⚠ Atención" : "✕ Excedido");
        statusBadge.setStyle(
            "-fx-font-size: 10px; -fx-font-weight: 700; "
            + "-fx-text-fill: white; "
            + "-fx-background-color: " + pctColor + "; "
            + "-fx-background-radius: 20; "
            + "-fx-padding: 2 8 2 8;");

        HBox calRow = new HBox(8, calIcon, headerSmall);
        calRow.setAlignment(Pos.CENTER_LEFT);

        // ── Selector de mes desplegable ─────────────────────────
        YearMonth currentYm = YearMonth.parse(month);
        ComboBox<YearMonth> monthCombo = new ComboBox<>();
        monthCombo.getItems().addAll(availableMonths);
        monthCombo.setValue(currentYm);

        javafx.util.StringConverter<YearMonth> conv = new javafx.util.StringConverter<>() {
            @Override public String toString(YearMonth v) {
                if (v == null) return "";
                if (v.equals(YearMonth.now())) return "Este mes";
                String mn = v.getMonth().getDisplayName(TextStyle.FULL, Locale.forLanguageTag("es"));
                return mn.substring(0, 1).toUpperCase(Locale.ROOT) + mn.substring(1) + " " + v.getYear();
            }
            @Override public YearMonth fromString(String s) { return null; }
        };
        monthCombo.setConverter(conv);
        monthCombo.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(YearMonth item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : conv.toString(item));
            }
        });
        monthCombo.getStyleClass().add("combo-box-custom");
        monthCombo.setPrefWidth(160);
        monthCombo.setPrefHeight(30);
        monthCombo.valueProperty().addListener((obs, o, n) -> {
            if (n != null && !n.equals(currentYm)) onMonthChange.accept(n);
        });

        VBox leftBlock = new VBox(6, calRow, monthLabel, monthCombo, statusBadge);
        leftBlock.setAlignment(Pos.CENTER_LEFT);
        leftBlock.setPadding(new Insets(0, 24, 0, 0));
        leftBlock.setMinWidth(185);

        // ── Divisor vertical ───────────────────────────────────
        Region vDivider = new Region();
        vDivider.setStyle("-fx-background-color: #E2E8F0; -fx-min-width: 1; -fx-pref-width: 1; -fx-max-width: 1;");
        vDivider.setMinHeight(60);

        // ── Métricas con iconos ─────────────────────────────────
        VBox[] blocks = {
            metricBlock("LÍMITE TOTAL", formatMoney(totalLimit, currency),    null,       "fas-wallet",       "#64748B"),
            metricBlock("GASTADO",      formatMoney(totalSpent, currency),    "#EF4444",  "fas-minus-circle", "#EF4444"),
            metricBlock("DISPONIBLE",   formatMoney(Math.abs(totalAvailable), currency),
                                                                               availColor, availIcon,          availColor),
            metricBlock("USO TOTAL",    pct + "%",                            pctColor,   "fas-chart-pie",    pctColor)
        };

        HBox metrics = new HBox();
        metrics.setFillHeight(true);
        HBox.setHgrow(metrics, Priority.ALWAYS);

        for (int i = 0; i < blocks.length; i++) {
            HBox.setHgrow(blocks[i], Priority.ALWAYS);
            metrics.getChildren().add(blocks[i]);
            if (i < blocks.length - 1) {
                Region sep = new Region();
                sep.setStyle("-fx-background-color: #E2E8F0; -fx-pref-width: 1; -fx-min-width: 1; -fx-max-width: 1;");
                sep.setMinHeight(40);
                metrics.getChildren().add(sep);
            }
        }

        // ── Barra de progreso ──────────────────────────────────────
        StackPane progressBar = buildProgressBar(pct / 100.0, pctColor, budgetTrackColor(pct));
        progressBar.setPrefHeight(10);
        progressBar.setMaxHeight(10);

        String supportMsg = totalLimit == 0
            ? "No hay presupuesto configurado para este mes"
            : isOverBudget
                ? "¡Excedido en " + formatMoney(Math.abs(totalAvailable), currency) + "!"
                : pct + "% utilizado · " + formatMoney(totalAvailable, currency) + " disponible";
        Label supportText = new Label(supportMsg);
        supportText.getStyleClass().add("text-secondary");
        supportText.setStyle("-fx-font-size: 11px; -fx-font-weight: 700;"
            + (isOverBudget ? " -fx-text-fill: #EF4444;" : ""));
        supportText.setMaxWidth(Double.MAX_VALUE);
        supportText.setAlignment(Pos.CENTER);

        VBox metricsAndBar = new VBox(12, metrics, progressBar, supportText);
        metricsAndBar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(metricsAndBar, Priority.ALWAYS);

        HBox cardRow = new HBox(0, leftBlock, vDivider, metricsAndBar);
        cardRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setMargin(metricsAndBar, new Insets(0, 0, 0, 24));

        VBox card = new VBox(0, cardRow);
        card.setPadding(new Insets(20));
        card.getStyleClass().addAll("content-card", "dashboard-monthly-card", "dashboard-monthly-card-redesign");
        card.setStyle("-fx-effect: dropshadow(gaussian, rgba(15,23,42,0.08), 12, 0, 0, 4);");
        return card;
    }

    private static final String[] CAT_ICON_COLORS = {
        "#7C3AED", "#D97706", "#059669", "#2563EB", "#DC2626", "#0891B2", "#65A30D"
    };
    private static final String[] CAT_ICON_BGS = {
        "#EDE9FE", "#FEF3C7", "#D1FAE5", "#DBEAFE", "#FEE2E2", "#CFFAFE", "#ECFCCB"
    };
    private static final String[] CAT_ICONS = {
        "fas-home", "fas-shopping-cart", "fas-car", "fas-briefcase",
        "fas-heartbeat", "fas-graduation-cap", "fas-utensils"
    };

    private static Node buildCategoriesSection(
            String userUid, BudgetRepository budgetRepo,
            CategoryRepository categoryRepo, String month, String currency,
            Runnable[] openRef, Object[] preloadHolder) {

        Label sectionTitle = new Label("Categorías de gastos");
        sectionTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: 800;");

        VBox categoriesContainer = new VBox(12);

        try {
            List<BudgetRepository.BudgetProgress> allProgress =
                budgetRepo.listProgressByMonthAndCurrency(userUid, month, currency);

            Map<String, BudgetRepository.BudgetProgress> byId = new LinkedHashMap<>();
            for (BudgetRepository.BudgetProgress p : allProgress) {
                byId.put(p.budget().categoryId(), p);
            }

            List<CategoryRepository.Category> roots = categoryRepo.listRoots(userUid);
            int colorIdx = 0;

            for (CategoryRepository.Category root : roots) {
                if (!isExpenseCategory(root)) continue;
                List<CategoryRepository.Category> children;
                try {
                    children = categoryRepo.listChildren(userUid, root.id());
                } catch (Exception ex) {
                    children = List.of();
                }

                long sumLimit = 0L, sumSpent = 0L;
                List<CategoryRepository.Category> childrenWithBudget = new java.util.ArrayList<>();

                if (children == null || children.isEmpty()) {
                    BudgetRepository.BudgetProgress p = byId.get(root.id());
                    if (p != null) {
                        sumLimit = p.budget().limitCents();
                        sumSpent = p.spentCents();
                    }
                } else {
                    // Budget puede estar en la raíz aunque tenga hijos
                    BudgetRepository.BudgetProgress rootP = byId.get(root.id());
                    if (rootP != null) {
                        sumLimit += rootP.budget().limitCents();
                        sumSpent += rootP.spentCents();
                    }
                    for (CategoryRepository.Category child : children) {
                        BudgetRepository.BudgetProgress cp = byId.get(child.id());
                        if (cp == null) continue;
                        sumLimit += cp.budget().limitCents();
                        sumSpent += cp.spentCents();
                        childrenWithBudget.add(child);
                    }
                }

                long available = sumLimit - sumSpent;
                int pct = sumLimit > 0 ? (int) Math.min(100, sumSpent * 100L / sumLimit) : 0;
                String barColor = pct < 50 ? "#22C55E" : pct <= 80 ? "#F59E0B" : "#EF4444";

                int ci = colorIdx % CAT_ICON_COLORS.length;
                colorIdx++;

                // Construir subcategorías reales
                final CategoryRepository.Category rootFinal = root;
                List<Object[]> subData = new java.util.ArrayList<>();
                for (CategoryRepository.Category child : childrenWithBudget) {
                    BudgetRepository.BudgetProgress cp = byId.get(child.id());
                    if (cp == null) continue;
                    long cAvail = cp.remainingCents();
                    int cPct = cp.budget().limitCents() > 0
                        ? (int) Math.min(100, cp.spentCents() * 100L / cp.budget().limitCents()) : 0;
                    Runnable editAction = () -> {
                        preloadHolder[0] = rootFinal;
                        preloadHolder[1] = child;
                        if (openRef[0] != null) openRef[0].run();
                    };
                    subData.add(new Object[]{
                        child.name(),
                        formatMoney(cp.budget().limitCents(), currency),
                        formatMoney(cp.spentCents(), currency),
                        formatMoney(cAvail, currency),
                        cPct,
                        editAction
                    });
                }

                categoriesContainer.getChildren().add(
                    buildCategoryCard(
                        root.name(),
                        CAT_ICONS[ci], CAT_ICON_COLORS[ci], CAT_ICON_BGS[ci],
                        formatMoney(sumLimit, currency),
                        formatMoney(sumSpent, currency),
                        formatMoney(available, currency),
                        pct, barColor,
                        subData.toArray(new Object[0][])
                    )
                );
            }
        } catch (Exception ignored) {
        }

        if (categoriesContainer.getChildren().isEmpty()) {
            Label empty = new Label("No hay presupuestos configurados para este mes.");
            empty.getStyleClass().add("text-secondary");
            categoriesContainer.getChildren().add(empty);
        }

        VBox section = new VBox(12, sectionTitle, categoriesContainer);
        return section;
    }

    private static String budgetStateColor(int pct) {
        if (pct < 60)  return "#2563EB";
        if (pct <= 85) return "#F59E0B";
        return "#EF4444";
    }

    private static String budgetTrackColor(int pct) {
        if (pct < 60)  return "rgba(37,99,235,0.12)";
        if (pct <= 85) return "rgba(245,158,11,0.15)";
        return "rgba(239,68,68,0.15)";
    }

    private static String cardNormalShadow() {
        return "-fx-effect: dropshadow(gaussian, rgba(15,23,42,0.07), 10, 0, 0, 3);";
    }

    private static String cardHoverShadow() {
        return "-fx-effect: dropshadow(gaussian, rgba(37,99,235,0.18), 18, 0, 0, 5);";
    }

    private static Node buildCategoryCard(
            String name, String iconLiteral, String iconColor, String iconBg,
            String limit, String spent, String available, int pct, String barColor,
            Object[][] subcategories) {

        String stateColor = budgetStateColor(pct);

        // ── Ícono en círculo ───────────────────────────────────
        FontIcon catIcon = new FontIcon(iconLiteral);
        catIcon.setIconSize(18);
        catIcon.setIconColor(javafx.scene.paint.Color.web(iconColor));
        StackPane iconCircle = new StackPane(catIcon);
        iconCircle.setStyle("-fx-background-color: " + iconBg + "; "
            + "-fx-background-radius: 999; "
            + "-fx-min-width: 40; -fx-pref-width: 40; -fx-max-width: 40; "
            + "-fx-min-height: 40; -fx-pref-height: 40; -fx-max-height: 40;");
        StackPane.setAlignment(catIcon, Pos.CENTER);

        // ── Nombre ──────────────────────────────────────────
        Label nameLabel = new Label(name.toUpperCase());
        nameLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: 800; -fx-letter-spacing: 0.5;");
        VBox nameBox = new VBox(2, nameLabel);
        nameBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(nameBox, Priority.ALWAYS);

        // ── Métricas (Límite / Gastado / Disponible) ───────────────
        HBox metricsBox = new HBox(28);
        metricsBox.setAlignment(Pos.CENTER_LEFT);
        metricsBox.getChildren().addAll(
            smallMetric("LÍMITE",     limit,     null),
            smallMetric("GASTADO",    spent,     "#EF4444"),
            smallMetric("DISPONIBLE", available, "#22C55E")
        );

        // ── Porcentaje ───────────────────────────────────────
        Label pctLabel = new Label(pct + "%");
        pctLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: 900; -fx-text-fill: " + stateColor + ";");
        pctLabel.setMinWidth(56);
        pctLabel.setAlignment(Pos.CENTER_RIGHT);

        // ── Barra de progreso ────────────────────────────────
        StackPane bar = buildProgressBar(pct / 100.0, stateColor, budgetTrackColor(pct));
        HBox.setHgrow(bar, Priority.ALWAYS);

        VBox barAndPct = new VBox(4, bar);
        barAndPct.setAlignment(Pos.CENTER_RIGHT);
        barAndPct.setMinWidth(160);
        HBox.setHgrow(barAndPct, Priority.NEVER);

        // ── Flecha chevron ──────────────────────────────────
        FontIcon chevron = new FontIcon("fas-chevron-down");
        chevron.setIconSize(11);
        chevron.setIconColor(javafx.scene.paint.Color.web("#94A3B8"));
        StackPane chevronBtn = new StackPane(chevron);
        chevronBtn.setStyle("-fx-background-color: #F1F5F9; -fx-background-radius: 999; "
            + "-fx-min-width: 30; -fx-pref-width: 30; -fx-max-width: 30; "
            + "-fx-min-height: 30; -fx-pref-height: 30; -fx-max-height: 30; "
            + "-fx-cursor: hand;");
        StackPane.setAlignment(chevron, Pos.CENTER);

        // ── Fila central: icon + nombre + métricas + porcentaje+barra + chevron ─
        HBox centerRow = new HBox(16);
        centerRow.setAlignment(Pos.CENTER_LEFT);
        centerRow.setPadding(new Insets(4, 0, 4, 0));
        centerRow.getChildren().addAll(iconCircle, nameBox);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        centerRow.getChildren().addAll(spacer, metricsBox, pctLabel, barAndPct, chevronBtn);

        // ── Subcategorías (grid, oculto por defecto) ──────────────
        boolean hasSubs = subcategories != null && subcategories.length > 0;

        VBox subGrid = new VBox(12);
        if (hasSubs) {
            HBox currentRow = null;
            int col = 0;
            for (Object[] sub : subcategories) {
                if (col == 0) {
                    currentRow = new HBox(12);
                    subGrid.getChildren().add(currentRow);
                }
                Runnable editAction = sub.length > 5 ? (Runnable) sub[5] : null;
                VBox subCard = buildSubCategoryCard(
                    (String) sub[0], (String) sub[1], (String) sub[2],
                    (String) sub[3], (int) sub[4], editAction);
                HBox.setHgrow(subCard, Priority.ALWAYS);
                currentRow.getChildren().add(subCard);
                col++;
                if (col == 3) col = 0;
            }
            // Rellenar la última fila con spacers si no está completa
            if (currentRow != null && col > 0 && col < 3) {
                for (int i = col; i < 3; i++) {
                    Region filler = new Region();
                    HBox.setHgrow(filler, Priority.ALWAYS);
                    currentRow.getChildren().add(filler);
                }
            }
        }

        Region divider = new Region();
        divider.setStyle("-fx-background-color: #E2E8F0;");
        divider.setPrefHeight(1);
        divider.setMaxHeight(1);

        VBox subcategoriesContainer = new VBox(12, divider, subGrid);
        VBox.setMargin(subcategoriesContainer, new Insets(12, 0, 0, 0));
        subcategoriesContainer.setVisible(false);
        subcategoriesContainer.setManaged(false);
        subcategoriesContainer.setOpacity(0);

        // ── Ocultar chevron si no hay subcategorías ───────────────
        if (!hasSubs) {
            chevronBtn.setVisible(false);
            chevronBtn.setManaged(false);
        }

        // ── Toggle expand/collapse con fade ───────────────────────
        RotateTransition rotate = new RotateTransition(Duration.millis(180), chevron);
        FadeTransition fadeIn  = new FadeTransition(Duration.millis(160), subcategoriesContainer);
        FadeTransition fadeOut = new FadeTransition(Duration.millis(120), subcategoriesContainer);
        fadeIn.setFromValue(0); fadeIn.setToValue(1);
        fadeOut.setFromValue(1); fadeOut.setToValue(0);
        final boolean[] expanded = {false};

        if (hasSubs) {
            javafx.event.EventHandler<javafx.event.ActionEvent> toggleAction = e -> {
                expanded[0] = !expanded[0];
                if (expanded[0]) {
                    subcategoriesContainer.setVisible(true);
                    subcategoriesContainer.setManaged(true);
                    fadeOut.stop();
                    fadeIn.playFromStart();
                } else {
                    fadeIn.stop();
                    fadeOut.playFromStart();
                    fadeOut.setOnFinished(ev -> {
                        subcategoriesContainer.setVisible(false);
                        subcategoriesContainer.setManaged(false);
                    });
                }
                rotate.stop();
                rotate.setByAngle(expanded[0] ? 180 : -180);
                rotate.playFromStart();
            };
            chevronBtn.setOnMouseClicked(e -> {
                e.consume();
                toggleAction.handle(null);
            });
            centerRow.setOnMouseClicked(e -> toggleAction.handle(null));
            centerRow.setStyle("-fx-cursor: hand;");
        }

        // ── Card ─────────────────────────────────────────────
        String normalStyle = "-fx-border-color: transparent; -fx-border-width: 1; "
            + "-fx-border-radius: 14; " + cardNormalShadow();
        String hoverStyle  = "-fx-border-color: #2563EB; -fx-border-width: 1; "
            + "-fx-border-radius: 14; " + cardHoverShadow();

        VBox card = new VBox(0, centerRow, subcategoriesContainer);
        card.setPadding(new Insets(16, 20, 16, 20));
        card.getStyleClass().addAll("content-card", "dashboard-monthly-card", "dashboard-monthly-card-redesign");
        card.setStyle(normalStyle);
        if (hasSubs) {
            card.setOnMouseEntered(e -> card.setStyle(hoverStyle));
            card.setOnMouseExited(e  -> card.setStyle(normalStyle));
        }
        return card;
    }

    private static VBox buildSubCategoryCard(
            String name, String limit, String spent, String available, int pct, Runnable editAction) {

        String stateColor = budgetStateColor(pct);
        String trackColor = budgetTrackColor(pct);
        boolean isDanger  = pct > 85;

        Label nameLbl = new Label(name);
        nameLbl.getStyleClass().add("budget-sub-name");
        if (isDanger) {
            nameLbl.getStyleClass().add("budget-sub-name-danger");
        }

        HBox metricsRow = new HBox(12);
        metricsRow.getChildren().addAll(
            smallMetric("LÍMITE",     limit,     null),
            smallMetric("GASTADO",    spent,     "#EF4444"),
            smallMetric("DISPONIBLE", available, "#22C55E")
        );

        StackPane bar = buildProgressBar(pct / 100.0, stateColor, trackColor);
        bar.setMaxHeight(6);
        bar.setPrefHeight(6);

        Label pctLbl = new Label(pct + "%");
        pctLbl.getStyleClass().add("budget-sub-pct");
        pctLbl.setStyle("-fx-text-fill: " + stateColor + ";");
        HBox pctRow = new HBox(pctLbl);
        pctRow.setAlignment(Pos.CENTER_RIGHT);

        // ── Botón Editar límite ────────────────────────────────────────
        FontIcon editIcon = new FontIcon("fas-pen");
        editIcon.setIconSize(11);
        editIcon.setIconColor(javafx.scene.paint.Color.web("#2563EB"));
        Button btnEdit = new Button("Editar límite");
        btnEdit.setGraphic(editIcon);
        btnEdit.setGraphicTextGap(6);
        btnEdit.setMaxWidth(Double.MAX_VALUE);
        btnEdit.getStyleClass().add("budget-sub-edit-btn");
        if (editAction != null) {
            btnEdit.setOnAction(ev -> editAction.run());
        }

        VBox subCard = new VBox(8, nameLbl, metricsRow, bar, pctRow, btnEdit);
        subCard.setPadding(new Insets(14, 14, 12, 14));
        subCard.setMaxWidth(Double.MAX_VALUE);
        subCard.getStyleClass().add("budget-sub-card");
        if (isDanger) {
            subCard.getStyleClass().add("budget-sub-card-danger");
        }
        return subCard;
    }

    private static StackPane buildProgressBar(double ratio, String fillColor, String trackColor) {
        Rectangle track = new Rectangle();
        track.setHeight(8);
        track.setArcWidth(8);
        track.setArcHeight(8);
        track.setStyle("-fx-fill: " + trackColor + ";");

        Rectangle fill = new Rectangle();
        fill.setHeight(8);
        fill.setArcWidth(8);
        fill.setArcHeight(8);
        fill.setStyle("-fx-fill: " + fillColor + ";");

        StackPane pane = new StackPane(track, fill);
        pane.setAlignment(Pos.CENTER_LEFT);
        pane.setPrefHeight(8);
        pane.setMaxHeight(8);
        pane.widthProperty().addListener((obs, o, w) -> {
            track.setWidth(w.doubleValue());
            fill.setWidth(Math.min(w.doubleValue(), w.doubleValue() * ratio));
        });
        return pane;
    }

    private static VBox smallMetric(String label, String value, String valueColor) {
        Label lbl = new Label(label);
        lbl.getStyleClass().add("text-secondary");
        lbl.setStyle("-fx-font-size: 9px; -fx-font-weight: 700; -fx-letter-spacing: 0.3;");

        Label val = new Label(value);
        val.setStyle("-fx-font-size: 13px; -fx-font-weight: 800;"
            + (valueColor != null ? " -fx-text-fill: " + valueColor + ";" : ""));

        VBox box = new VBox(3, lbl, val);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static VBox metricBlock(String label, String value, String valueColor,
                                    String iconLiteral, String iconColor) {
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(22);
        icon.setIconColor(javafx.scene.paint.Color.web(iconColor));

        StackPane iconCircle = new StackPane(icon);
        String alphaBg = iconColor.startsWith("#")
            ? iconColor + "1A"
            : "rgba(100,116,139,0.10)";
        iconCircle.setStyle(
            "-fx-background-color: " + alphaBg + "; "
            + "-fx-background-radius: 10; "
            + "-fx-min-width: 42; -fx-min-height: 42; "
            + "-fx-max-width: 42; -fx-max-height: 42;");

        Label descLabel = new Label(label);
        descLabel.getStyleClass().add("text-secondary");
        descLabel.setStyle("-fx-font-size: 9px; -fx-font-weight: 700; -fx-letter-spacing: 0.5;");

        Label valLabel = new Label(value);
        valLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: 900;"
            + (valueColor != null ? " -fx-text-fill: " + valueColor + ";" : ""));

        VBox textCol = new VBox(2, descLabel, valLabel);
        textCol.setAlignment(Pos.CENTER_LEFT);

        HBox row = new HBox(10, iconCircle, textCol);
        row.setAlignment(Pos.CENTER_LEFT);

        VBox block = new VBox(row);
        block.setAlignment(Pos.CENTER_LEFT);
        block.setPadding(new Insets(0, 20, 0, 20));
        return block;
    }

    public static Node buildBudgetView(
        AuthSession session,
        BudgetRepository budgetRepo,
        GoalRepository goalRepo,
        CategoryRepository categoryRepo,
        AccountRepository accountRepo,
        TransferRepository transferRepo,
        java.util.function.Supplier<Boolean> darkTheme,
        Runnable refreshBalances
    ) {
        // ── Header (título + subtítulo) ──────────────────────────
        Label title = new Label("Presupuesto");
        title.getStyleClass().add("app-title");

        Label subtitle = new Label("Planea tus gastos y controla tus límites");
        subtitle.getStyleClass().add("text-secondary");

        // Referencias para cambio dinámico
        final Label[] subtitleRef = { subtitle };

        VBox titleBox = new VBox(2, title, subtitle);
        titleBox.setAlignment(Pos.CENTER_LEFT);

        // ── Segment control ──────────────────────────────────────
        Button btnMonthly = new Button("Presupuesto mensual");
        Button btnGoals   = new Button("Metas de ahorro");

        FontIcon iconMonthly = new FontIcon("fas-wallet");
        iconMonthly.setIconSize(14);
        iconMonthly.getStyleClass().add("icon");
        btnMonthly.setGraphic(iconMonthly);
        btnMonthly.setGraphicTextGap(6);

        FontIcon iconGoals = new FontIcon("fas-piggy-bank");
        iconGoals.setIconSize(14);
        iconGoals.getStyleClass().add("icon");
        btnGoals.setGraphic(iconGoals);
        btnGoals.setGraphicTextGap(6);

        javafx.scene.paint.Color colorActive   = javafx.scene.paint.Color.WHITE;
        javafx.scene.paint.Color colorInactive = javafx.scene.paint.Color.web("#64748B");

        String activeStyle   = "-fx-background-color: #2563EB; -fx-text-fill: white; "
                             + "-fx-background-radius: 10; -fx-border-radius: 10; "
                             + "-fx-font-weight: 700; -fx-cursor: hand; "
                             + "-fx-padding: 0 20 0 20; -fx-pref-height: 38; "
                             + "-fx-min-height: 38; -fx-max-height: 38;";
        String inactiveStyle = "-fx-background-color: transparent; -fx-text-fill: #64748B; "
                             + "-fx-background-radius: 10; -fx-border-radius: 10; "
                             + "-fx-border-color: #CBD5E1; -fx-border-width: 1; "
                             + "-fx-font-weight: 600; -fx-cursor: hand; "
                             + "-fx-padding: 0 20 0 20; -fx-pref-height: 38; "
                             + "-fx-min-height: 38; -fx-max-height: 38;";

        btnMonthly.setStyle(activeStyle);
        btnGoals.setStyle(inactiveStyle);
        iconMonthly.setIconColor(colorActive);
        iconGoals.setIconColor(colorInactive);

        String currency = "COP";
        String userUid = session.uid();

        // ── Meses disponibles desde presupuestos y transacciones ──────────────
        List<YearMonth> availableMonths = new ArrayList<>();
        try {
            // Meses con presupuestos configurados (excluir __BASE__)
            List<String> budgetMonths = budgetRepo.listDistinctExpenseMonths(userUid);
            for (String ym : budgetMonths) {
                // Filtrar __BASE__ y valores no válidos
                if (ym == null || ym.isBlank() || ym.equals(BudgetRepository.BASE_BUDGET_MONTH)) {
                    continue;
                }
                try {
                    YearMonth parsed = YearMonth.parse(ym);
                    if (!availableMonths.contains(parsed)) {
                        availableMonths.add(parsed);
                    }
                } catch (Exception ignored) {
                    // Ignorar meses con formato inválido
                }
            }
            // Meses con transacciones de gastos
            List<String> transactionMonths = budgetRepo.listDistinctTransactionMonths(userUid);
            for (String ym : transactionMonths) {
                if (ym == null || ym.isBlank()) {
                    continue;
                }
                try {
                    YearMonth parsed = YearMonth.parse(ym);
                    if (!availableMonths.contains(parsed)) {
                        availableMonths.add(parsed);
                    }
                } catch (Exception ignored) {
                    // Ignorar meses con formato inválido
                }
            }
            // Ordenar descendente (más recientes primero)
            availableMonths.sort((a, b) -> b.compareTo(a));
        } catch (Exception e) {
            e.printStackTrace();
        }
        // Siempre incluir el mes actual si no está
        YearMonth thisMonth = YearMonth.now();
        if (!availableMonths.contains(thisMonth)) {
            availableMonths.add(0, thisMonth);
        }

        final String[] selectedMonth = { String.format("%04d-%02d", thisMonth.getYear(), thisMonth.getMonthValue()) };

        VBox contentContainer = new VBox(16);
        VBox.setVgrow(contentContainer, Priority.ALWAYS);

        Runnable[] refreshHolder = { null };
        Consumer<YearMonth> onMonthChange = ym -> {
            selectedMonth[0] = String.format("%04d-%02d", ym.getYear(), ym.getMonthValue());
            if (refreshHolder[0] != null) refreshHolder[0].run();
        };

        Runnable[] openRef = { null };
        Object[] preloadHolder = { null, null, null };

        Runnable refreshMonthlyContent = () -> {
            String m = selectedMonth[0];
            contentContainer.getChildren().setAll(
                buildMonthlySummaryCard(userUid, budgetRepo, m, currency, availableMonths, onMonthChange),
                buildCategoriesSection(userUid, budgetRepo, categoryRepo, m, currency, openRef, preloadHolder)
            );
        };
        refreshHolder[0] = refreshMonthlyContent;
        refreshMonthlyContent.run();

        // Referencia para el botón principal (se asigna más abajo)
        final Button[] btnNewRef = { null };

        // Referencias para drawers
        final Runnable[] goalOpenRef = { null };
        final Runnable[] goalCloseRef = { null };

        // Referencia al servicio de metas y refresh del grid
        final Runnable[] metasRefreshHolder = { null };
        final GoalService[] goalServiceRef = { null };
        // Meta a editar (null = modo crear)
        final GoalRepository.Goal[] metaEditRef = { null };
        final GoalService.MetaInfo[] selectedMetaRef = { null };
        // Drawer de nueva/editar meta
        final Runnable[] newGoalEditRef  = { null };
        final VBox[] newGoalDrawerRef = { null };

        btnMonthly.setOnAction(e -> {
            btnMonthly.setStyle(activeStyle);
            btnGoals.setStyle(inactiveStyle);
            iconMonthly.setIconColor(colorActive);
            iconGoals.setIconColor(colorInactive);
            // Restaurar subtítulo y botón
            subtitleRef[0].setText("Planea tus gastos y controla tus límites");
            btnNewRef[0].setText("Nuevo presupuesto");
            if (refreshHolder[0] != null) refreshHolder[0].run();
        });

        // Contenedores de metas (reutilizados por el refresh)
        final VBox[] metasSummaryContainerRef = { new VBox() };
        final VBox[] metasGridWrapperRef = { new VBox() };

        Runnable refreshMetasContent = () -> {
            boolean dk = darkTheme != null && Boolean.TRUE.equals(darkTheme.get());
            GoalService goalService = goalServiceRef[0];
            if (goalService == null) return;
            VBox sumContainer = metasSummaryContainerRef[0];
            VBox gridWrapper = metasGridWrapperRef[0];
            sumContainer.getChildren().clear();
            gridWrapper.getChildren().clear();
            try {
                HBox summaryCard = buildMetasSummaryCard(dk, goalService, userUid);
                sumContainer.getChildren().add(summaryCard);
                VBox metasGrid = buildMetasGrid(dk, goalService, userUid, goalOpenRef, selectedMetaRef,
                    newGoalEditRef, metaEditRef, newGoalDrawerRef);
                ScrollPane gridScroll = new ScrollPane(metasGrid);
                gridScroll.setFitToWidth(true);
                gridScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
                gridScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
                String scrollBg = dk ? "-fx-background-color: #0F172A; -fx-background: #0F172A;"
                                     : "-fx-background-color: transparent; -fx-background: transparent;";
                gridScroll.setStyle(scrollBg);
                gridWrapper.getChildren().add(gridScroll);
            } catch (Exception ex) {
                System.out.println("[Metas] Error al refrescar: " + ex.getMessage());
                ex.printStackTrace();
            }
        };
        metasRefreshHolder[0] = () -> {
            refreshMetasContent.run();
            if (refreshBalances != null) refreshBalances.run();
        };

        btnGoals.setOnAction(e -> {
            btnGoals.setStyle(activeStyle);
            btnMonthly.setStyle(inactiveStyle);
            iconGoals.setIconColor(colorActive);
            iconMonthly.setIconColor(colorInactive);
            // Cambiar subtítulo y botón
            subtitleRef[0].setText("Planea tus gastos y tus objetivos");
            btnNewRef[0].setText("Nueva meta");
            // Limpiar y crear estructura de Metas
            contentContainer.getChildren().clear();
            VBox metasRoot = new VBox(16);
            metasRoot.setFillWidth(true);
            VBox metasSummaryContainer = metasSummaryContainerRef[0];
            metasSummaryContainer.setFillWidth(true);
            metasSummaryContainer.setSpacing(0);
            VBox metasGridWrapper = metasGridWrapperRef[0];
            metasGridWrapper.setFillWidth(true);
            VBox.setVgrow(metasGridWrapper, Priority.ALWAYS);
            metasRoot.getChildren().addAll(metasSummaryContainer, metasGridWrapper);
            contentContainer.getChildren().add(metasRoot);
            try {
                GoalService goalService = new GoalService(goalRepo, accountRepo, transferRepo);
                goalServiceRef[0] = goalService;
                refreshMetasContent.run();
            } catch (Exception ex) {
                System.out.println("[Metas] Error al crear servicio: " + ex.getMessage());
                ex.printStackTrace();
                Label errorLbl = new Label("Error al cargar metas: " + ex.getMessage());
                errorLbl.setStyle("-fx-text-fill: red; -fx-padding: 20;");
                metasSummaryContainerRef[0].getChildren().add(errorLbl);
            }
        });

        HBox segmentControl = new HBox(8, btnMonthly, btnGoals);
        segmentControl.setAlignment(Pos.CENTER_LEFT);

        // ── Botón "+ Nuevo presupuesto" ───────────────────────────
        FontIcon iconNew = new FontIcon("fas-plus");
        iconNew.setIconSize(13);
        iconNew.getStyleClass().add("icon");
        iconNew.setIconColor(javafx.scene.paint.Color.WHITE);
        Button btnNew = new Button("Nuevo presupuesto");
        btnNew.setGraphic(iconNew);
        btnNew.setGraphicTextGap(6);
        btnNew.getStyleClass().add("btn-primary");
        btnNew.setStyle("-fx-pref-height: 38; -fx-min-height: 38; -fx-max-height: 38;");

        // Asignar referencia para cambio dinámico
        btnNewRef[0] = btnNew;

        // ── Top bar (todo en una sola línea) ─────────────────────
        Region spacerLeft = new Region();
        Region spacerRight = new Region();
        HBox.setHgrow(spacerLeft, Priority.ALWAYS);
        HBox.setHgrow(spacerRight, Priority.ALWAYS);
        HBox topBar = new HBox(16, titleBox, spacerLeft, segmentControl, spacerRight, btnNew);
        topBar.setAlignment(Pos.CENTER_LEFT);

        // ── Scroll sobre el contenido ─────────────────────────────
        ScrollPane scroll = new ScrollPane(contentContainer);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        scroll.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        // ── Root (VBox principal del módulo) ────────────────────
        VBox root = new VBox(16, topBar, scroll);
        root.getStyleClass().add("content");
        root.setPadding(new Insets(24));
        root.setFillWidth(true);
        VBox.setVgrow(root, Priority.ALWAYS);

        // ── Overlay oscuro ────────────────────────────────────────
        Pane overlay = new Pane();
        overlay.setStyle("-fx-background-color: rgba(0,0,0,0.35);");
        overlay.setVisible(false);
        overlay.setMouseTransparent(false);
        AnchorPane.setTopAnchor(overlay, 0.0);
        AnchorPane.setBottomAnchor(overlay, 0.0);
        AnchorPane.setLeftAnchor(overlay, 0.0);
        AnchorPane.setRightAnchor(overlay, 0.0);

        // ── StackPane raíz con overlay ────────────────────────────
        final double DRAWER_WIDTH = 400.0;
        StackPane stackRoot = new StackPane(root, overlay);
        VBox.setVgrow(stackRoot, Priority.ALWAYS);
        
        // overlay se expande al tamaño del StackPane
        overlay.prefWidthProperty().bind(stackRoot.widthProperty());
        overlay.prefHeightProperty().bind(stackRoot.heightProperty());

        // overlay se expande al tamaño del StackPane
        overlay.prefWidthProperty().bind(stackRoot.widthProperty());
        overlay.prefHeightProperty().bind(stackRoot.heightProperty());

        // ── Abrir / cerrar drawer presupuesto ──────────────────────────
        Runnable[] closeRef      = { null };
        Runnable[] afterCloseRef = { null };

        // ── Drawer presupuesto ─────────────────────────────────────
        VBox drawer = buildNewBudgetDrawer(DRAWER_WIDTH, stackRoot, closeRef, afterCloseRef,
            userUid, categoryRepo, budgetRepo, currency, refreshHolder, preloadHolder, darkTheme);
        drawer.setTranslateX(DRAWER_WIDTH);
        drawer.prefHeightProperty().bind(stackRoot.heightProperty());
        stackRoot.getChildren().add(drawer);
        StackPane.setAlignment(drawer, Pos.CENTER_RIGHT);

        closeRef[0] = () -> closeDrawer(drawer, overlay, afterCloseRef[0]);
        openRef[0]  = () -> openDrawer(drawer, overlay, DRAWER_WIDTH, closeRef[0]);

        // ── Abrir / cerrar drawer nueva meta ───────────────────────────
        Runnable[] newGoalCloseRef = { null };
        Runnable[] newGoalOpenRef  = { null };

        // ── Drawer nueva meta ──────────────────────────────────────
        VBox newGoalDrawer = buildNewGoalDrawer(DRAWER_WIDTH, stackRoot, newGoalCloseRef,
            userUid, currency, goalServiceRef, metaEditRef, metasRefreshHolder, darkTheme);
        newGoalDrawer.setTranslateX(DRAWER_WIDTH);
        newGoalDrawer.prefHeightProperty().bind(stackRoot.heightProperty());
        stackRoot.getChildren().add(newGoalDrawer);
        StackPane.setAlignment(newGoalDrawer, Pos.CENTER_RIGHT);
        newGoalDrawerRef[0] = newGoalDrawer;

        newGoalCloseRef[0] = () -> closeDrawer(newGoalDrawer, overlay, null);
        newGoalOpenRef[0]  = () -> {
            metaEditRef[0] = null;
            loadNewGoalDrawerData(newGoalDrawer, null);
            openDrawer(newGoalDrawer, overlay, DRAWER_WIDTH, newGoalCloseRef[0]);
        };
        newGoalEditRef[0] = () -> openDrawer(newGoalDrawer, overlay, DRAWER_WIDTH, newGoalCloseRef[0]);

        // ── Handler del botón según pestaña activa ───────────────────
        btnNew.setOnAction(e -> {
            if ("Nueva meta".equals(btnNew.getText())) {
                newGoalOpenRef[0].run();
            } else {
                openRef[0].run();
            }
        });

        // ── Drawer metas ───────────────────────────────────────
        VBox goalDrawer = buildGoalDetailDrawer(DRAWER_WIDTH, stackRoot, goalCloseRef,
            darkTheme, goalServiceRef, accountRepo, userUid, selectedMetaRef, metasRefreshHolder);
        goalDrawer.setTranslateX(DRAWER_WIDTH);
        goalDrawer.prefHeightProperty().bind(stackRoot.heightProperty());
        stackRoot.getChildren().add(goalDrawer);
        StackPane.setAlignment(goalDrawer, Pos.CENTER_RIGHT);

        goalCloseRef[0] = () -> closeDrawer(goalDrawer, overlay, null);
        goalOpenRef[0]  = () -> {
            if (selectedMetaRef[0] != null) {
                refreshGoalDrawer(goalDrawer, selectedMetaRef[0], goalServiceRef[0], userUid);
            }
            openDrawer(goalDrawer, overlay, DRAWER_WIDTH, goalCloseRef[0]);
        };

        // ── ESC cierra el drawer ────────────────────────────────
        stackRoot.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && overlay.isVisible()) {
                closeRef[0].run();
                goalCloseRef[0].run();
                e.consume();
            }
        });

        return stackRoot;
    }

    // ── Drawer: estructura visual + detección de estado ────────────
    private static VBox buildNewBudgetDrawer(double width, StackPane stackRoot,
                                              Runnable[] closeRef, Runnable[] afterCloseRef,
                                              String userUid, CategoryRepository categoryRepo,
                                              BudgetRepository budgetRepo, String currency,
                                              Runnable[] refreshHolder, Object[] preloadHolder,
                                              java.util.function.Supplier<Boolean> darkTheme) {
        final boolean dk = darkTheme != null && Boolean.TRUE.equals(darkTheme.get());

        // ── Colores dinámicos según tema ────────────────────────────
        String drawerBg      = dk ? "#0F172A"  : "white";
        String dividerColor  = dk ? "rgba(255,255,255,0.10)" : "#E2E8F0";
        String subtitleColor = dk ? "#94A3B8"  : "#64748B";
        String titleColor    = dk ? "#E5E7EB"  : "#0F172A";
        String closeBtnBg    = dk ? "rgba(255,255,255,0.08)" : "rgba(100,116,139,0.10)";
        String closeIconColor= dk ? "#94A3B8"  : "#64748B";
        String cancelBg      = dk ? "#1E293B"  : "#F1F5F9";
        String cancelFg      = dk ? "#E5E7EB"  : "#374151";
        String statusSubFg   = dk ? "#CBD5E1"  : "#374151";
        String footerDivClr  = dk ? "rgba(255,255,255,0.08)" : "#E2E8F0";
        String limitBg       = dk ? "#0B1220"  : "#F8FAFC";
        String limitBorder   = dk ? "#334155"  : "#CBD5E1";
        String limitFg       = dk ? "#E5E7EB"  : null;
        String prefixBg      = dk ? "#0F172A"  : "#F1F5F9";
        String prefixFg      = dk ? "#94A3B8"  : "#64748B";
        String prefixBorder  = dk ? "#334155"  : "#CBD5E1";
        String hintFg        = dk ? "#64748B"  : "#94A3B8";
        String mesRowBg      = dk ? "rgba(37,99,235,0.12)" : "rgba(37,99,235,0.07)";
        String mesRowBorder  = dk ? "rgba(37,99,235,0.30)" : "rgba(37,99,235,0.18)";
        String infoBoxBg     = dk ? "rgba(37,99,235,0.12)" : "rgba(37,99,235,0.07)";
        String infoBoxBorder = dk ? "rgba(37,99,235,0.30)" : "rgba(37,99,235,0.18)";
        String drawerShadow  = dk ? "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.60), 24, 0.20, -6, 0);"
                                  : "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.22), 24, 0.15, -6, 0);";
        String fieldLabelFg  = dk ? "#E5E7EB"  : null;
        String scrollBg      = dk ? "-fx-background-color: #0F172A; -fx-background: #0F172A;"
                                  : "-fx-background-color: transparent; -fx-background: transparent;";

        // ── Botón cerrar (X) ──────────────────────────────────────
        FontIcon closeIcon = new FontIcon("fas-times");
        closeIcon.setIconSize(15);
        closeIcon.setIconColor(javafx.scene.paint.Color.web(closeIconColor));
        Button btnClose = new Button();
        btnClose.setGraphic(closeIcon);
        btnClose.setStyle(
            "-fx-background-color: " + closeBtnBg + "; "
            + "-fx-background-radius: 8; -fx-border-radius: 8; "
            + "-fx-cursor: hand; "
            + "-fx-min-width: 30; -fx-min-height: 30; "
            + "-fx-max-width: 30; -fx-max-height: 30;");
        btnClose.setId("drawerCloseBtn");

        // ── Título + subtítulo ────────────────────────────────────
        Label drawerTitle = new Label("Nuevo presupuesto");
        drawerTitle.setStyle("-fx-font-size: 18px; -fx-font-weight: 800;"
            + (dk ? " -fx-text-fill: " + titleColor + ";" : ""));

        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        HBox titleRow = new HBox(12, drawerTitle, titleSpacer, btnClose);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label drawerSubtitle = new Label("Define o actualiza el límite mensual de una subcategoría.");
        drawerSubtitle.setStyle("-fx-font-size: 12px; -fx-text-fill: " + subtitleColor + ";");
        drawerSubtitle.setWrapText(true);

        VBox drawerHeader = new VBox(4, titleRow, drawerSubtitle);

        // ── Separador ─────────────────────────────────────────────
        Region divider = new Region();
        divider.setPrefHeight(1);
        divider.setMaxHeight(1);
        divider.setStyle("-fx-background-color: " + dividerColor + ";");
        divider.setMaxWidth(Double.MAX_VALUE);

        // ── Converter reutilizable para Category ──────────────────
        javafx.util.StringConverter<CategoryRepository.Category> catConverter =
            new javafx.util.StringConverter<>() {
                @Override public String toString(CategoryRepository.Category c) {
                    return c == null ? "" : c.name();
                }
                @Override public CategoryRepository.Category fromString(String s) { return null; }
            };

        // ── Campo 1: Categoría (datos reales) ─────────────────────
        ComboBox<CategoryRepository.Category> catCombo = new ComboBox<>();
        catCombo.setPromptText("Seleccionar categoría");
        catCombo.setMaxWidth(Double.MAX_VALUE);
        catCombo.getStyleClass().add("combo-box-custom");
        catCombo.setConverter(catConverter);
        try {
            for (CategoryRepository.Category r : categoryRepo.listRoots(userUid)) {
                if (isExpenseCategory(r)) catCombo.getItems().add(r);
            }
        } catch (Exception ignored) {}
        VBox fieldCategoria = buildDrawerField(1, "Categoría", catCombo);

        // ── Campo 2: Subcategoría (carga dinámica) ────────────────
        ComboBox<CategoryRepository.Category> subCombo = new ComboBox<>();
        subCombo.setPromptText("Seleccionar subcategoría");
        subCombo.setMaxWidth(Double.MAX_VALUE);
        subCombo.getStyleClass().add("combo-box-custom");
        subCombo.setConverter(catConverter);
        subCombo.setDisable(true);
        VBox fieldSubcategoria = buildDrawerField(2, "Subcategoría", subCombo);

        catCombo.valueProperty().addListener((obs, ov, nv) -> {
            subCombo.setValue(null);
            subCombo.getItems().clear();
            if (nv == null) { subCombo.setDisable(true); return; }
            try {
                List<CategoryRepository.Category> children =
                    categoryRepo.listChildren(userUid, nv.id());
                subCombo.getItems().addAll(children);
                subCombo.setDisable(children.isEmpty());
            } catch (Exception ignored) { subCombo.setDisable(true); }
        });

        // ── Campo 3: Límite mensual ───────────────────────────────
        final String LIMIT_NORMAL_STYLE =
            "-fx-font-size: 14px; -fx-font-weight: 700; "
            + "-fx-alignment: center-right; "
            + "-fx-background-color: " + limitBg + "; "
            + "-fx-border-color: " + limitBorder + "; -fx-border-width: 1 1 1 0; "
            + "-fx-background-radius: 0 10 10 0; -fx-border-radius: 0 10 10 0; "
            + "-fx-padding: 10 14 10 8; -fx-pref-height: 44;"
            + (limitFg != null ? " -fx-text-fill: " + limitFg + ";" : "");
        final String LIMIT_ERROR_STYLE =
            "-fx-font-size: 14px; -fx-font-weight: 700; "
            + "-fx-alignment: center-right; "
            + "-fx-background-color: " + (dk ? "#1A0A0A" : "#FEF2F2") + "; "
            + "-fx-border-color: #FCA5A5; -fx-border-width: 1 1 1 0; "
            + "-fx-background-radius: 0 10 10 0; -fx-border-radius: 0 10 10 0; "
            + "-fx-padding: 10 14 10 8; -fx-pref-height: 44;"
            + (dk ? " -fx-text-fill: #FECACA;" : "");
        final String PREFIX_NORMAL_STYLE =
            "-fx-font-size: 14px; -fx-font-weight: 700; -fx-text-fill: " + prefixFg + "; "
            + "-fx-background-color: " + prefixBg + "; "
            + "-fx-background-radius: 10 0 0 10; "
            + "-fx-border-color: " + prefixBorder + "; -fx-border-width: 1 0 1 1; "
            + "-fx-border-radius: 10 0 0 10; "
            + "-fx-padding: 10 10 10 14; -fx-min-height: 44; -fx-max-height: 44;";
        final String PREFIX_ERROR_STYLE =
            "-fx-font-size: 14px; -fx-font-weight: 700; -fx-text-fill: #EF4444; "
            + "-fx-background-color: " + (dk ? "#1A0A0A" : "#FEE2E2") + "; "
            + "-fx-background-radius: 10 0 0 10; "
            + "-fx-border-color: #FCA5A5; -fx-border-width: 1 0 1 1; "
            + "-fx-border-radius: 10 0 0 10; "
            + "-fx-padding: 10 10 10 14; -fx-min-height: 44; -fx-max-height: 44;";

        Label currencyPrefix = new Label("$");
        currencyPrefix.setStyle(PREFIX_NORMAL_STYLE);

        TextField limitField = new TextField();
        limitField.setPromptText("0");
        limitField.setMaxWidth(Double.MAX_VALUE);
        limitField.setStyle(LIMIT_NORMAL_STYLE);

        HBox limitRow = new HBox(0, currencyPrefix, limitField);
        limitRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(limitField, Priority.ALWAYS);

        Label limitHint = new Label("Ingresa el monto máximo permitido.");
        limitHint.setStyle("-fx-font-size: 11px; -fx-text-fill: " + hintFg + ";");

        Label limitError = new Label();
        limitError.setStyle("-fx-font-size: 11px; -fx-text-fill: #EF4444;");
        limitError.setVisible(false);
        limitError.setManaged(false);

        VBox fieldLimite = buildDrawerField(3, "Límite mensual", limitRow);
        fieldLimite.getChildren().addAll(limitHint, limitError);

        final long[] rawValue = { 0L };
        final boolean[] formatting = { false };

        limitField.textProperty().addListener((obs, oldText, newText) -> {
            if (formatting[0]) return;
            formatting[0] = true;
            String digits = newText.replaceAll("[^0-9]", "");
            rawValue[0] = digits.isEmpty() ? 0L : Long.parseLong(digits);
            String formatted = digits.isEmpty() ? "" : formatThousands(digits);
            limitField.setText(formatted);
            if (!formatted.isEmpty()) limitField.positionCaret(formatted.length());
            formatting[0] = false;
        });

        Button[] btnSaveRef = { null };
        limitField.setOnAction(ev -> { if (btnSaveRef[0] != null && !btnSaveRef[0].isDisabled()) btnSaveRef[0].fire(); });

        limitField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (isFocused) {
                if (limitError.isVisible()) {
                    limitError.setVisible(false); limitError.setManaged(false);
                    limitHint.setVisible(true);   limitHint.setManaged(true);
                    limitField.setStyle(LIMIT_NORMAL_STYLE);
                    currencyPrefix.setStyle(PREFIX_NORMAL_STYLE);
                }
            } else {
                if (rawValue[0] == 0 && !limitField.getText().isEmpty()) {
                    limitError.setText("El límite debe ser mayor a 0");
                    limitError.setVisible(true);  limitError.setManaged(true);
                    limitHint.setVisible(false);  limitHint.setManaged(false);
                    limitField.setStyle(LIMIT_ERROR_STYLE);
                    currencyPrefix.setStyle(PREFIX_ERROR_STYLE);
                } else {
                    limitError.setVisible(false); limitError.setManaged(false);
                    limitHint.setVisible(true);   limitHint.setManaged(true);
                    limitField.setStyle(LIMIT_NORMAL_STYLE);
                    currencyPrefix.setStyle(PREFIX_NORMAL_STYLE);
                }
            }
        });

        // ── Campo 4: Vigencia (informativo) ───────────────────────
        FontIcon calIcon = new FontIcon("fas-calendar-check");
        calIcon.setIconSize(14);
        calIcon.setIconColor(javafx.scene.paint.Color.web("#2563EB"));
        Label mesLabel = new Label("Válido para todos los meses");
        mesLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: " + (dk ? "#93C5FD" : "#1E40AF") + ";");
        HBox mesRow = new HBox(10, calIcon, mesLabel);
        mesRow.setAlignment(Pos.CENTER_LEFT);
        mesRow.setStyle("-fx-background-color: " + mesRowBg + "; "
            + "-fx-background-radius: 10; "
            + "-fx-border-color: " + mesRowBorder + "; -fx-border-width: 1; "
            + "-fx-border-radius: 10; -fx-padding: 12 14 12 14;");

        VBox fieldMes = buildDrawerField(4, "Vigencia", mesRow);

        // ── Nota informativa ──────────────────────────────────────
        FontIcon infoIcon = new FontIcon("fas-info-circle");
        infoIcon.setIconSize(13);
        infoIcon.setIconColor(javafx.scene.paint.Color.web("#2563EB"));
        Label infoText = new Label("El límite mensual se aplica a todos los meses de forma continua.");
        infoText.setWrapText(true);
        infoText.setStyle("-fx-font-size: 11px; -fx-text-fill: " + (dk ? "#60A5FA" : "#3B82F6") + ";");
        HBox infoBox = new HBox(8, infoIcon, infoText);
        infoBox.setAlignment(Pos.TOP_LEFT);
        infoBox.setStyle(
            "-fx-background-color: " + infoBoxBg + "; "
            + "-fx-background-radius: 10; "
            + "-fx-border-color: " + infoBoxBorder + "; -fx-border-width: 1; "
            + "-fx-border-radius: 10; -fx-padding: 12 14 12 14;");
        HBox.setHgrow(infoText, Priority.ALWAYS);

        // ── StatusBox ─────────────────────────────────────────────
        FontIcon statusIcon = new FontIcon("fas-check-circle");
        statusIcon.setIconSize(14);
        Label statusTitle = new Label();
        statusTitle.setStyle("-fx-font-size: 12px; -fx-font-weight: 700;");
        HBox statusTitleRow = new HBox(7, statusIcon, statusTitle);
        statusTitleRow.setAlignment(Pos.CENTER_LEFT);
        Label statusSub = new Label();
        statusSub.setStyle("-fx-font-size: 12px; -fx-text-fill: " + statusSubFg + ";");
        statusSub.setWrapText(true);
        VBox statusBox = new VBox(5, statusTitleRow, statusSub);
        statusBox.setMaxWidth(Double.MAX_VALUE);
        statusBox.setVisible(false);
        statusBox.setManaged(false);
        statusBox.setOpacity(0.0);

        // ── Botones footer ────────────────────────────────────────
        Button btnCancel = new Button("Cancelar");
        btnCancel.setId("drawerCloseBtn");
        btnCancel.setMaxWidth(Double.MAX_VALUE);
        btnCancel.setStyle(
            "-fx-background-color: " + cancelBg + "; -fx-text-fill: " + cancelFg + "; -fx-font-weight: 700; "
            + "-fx-background-radius: 10; -fx-border-radius: 10; -fx-cursor: hand; "
            + "-fx-pref-height: 44; -fx-font-size: 13px;");

        final String SAVE_STYLE_ON  = "-fx-background-color: #2563EB; -fx-text-fill: white; "
            + "-fx-font-weight: 700; -fx-background-radius: 10; -fx-border-radius: 10; "
            + "-fx-cursor: hand; -fx-pref-height: 44; -fx-font-size: 13px;";
        final String SAVE_STYLE_OFF = SAVE_STYLE_ON + "-fx-opacity: 0.5;";

        Button btnSave = new Button("Guardar límite");
        btnSaveRef[0] = btnSave;
        btnSave.setMaxWidth(Double.MAX_VALUE);
        btnSave.setDisable(true);
        btnSave.setStyle(SAVE_STYLE_OFF);
        HBox.setHgrow(btnCancel, Priority.ALWAYS);
        HBox.setHgrow(btnSave, Priority.ALWAYS);

        limitField.textProperty().addListener((obs, ov, nv) -> {
            boolean valid = rawValue[0] > 0;
            btnSave.setDisable(!valid);
            btnSave.setStyle(valid ? SAVE_STYLE_ON : SAVE_STYLE_OFF);
        });

        // ── Listeners checkState ──────────────────────────────────
        Runnable checkState = () -> {
            CategoryRepository.Category cat = catCombo.getValue();
            CategoryRepository.Category sub = subCombo.getValue();
            if (cat == null || sub == null) return;

            boolean exists;
            try {
                exists = budgetRepo.getByUniqueKeyOrNull(
                    userUid, BudgetRepository.BASE_BUDGET_MONTH, currency, sub.id()) != null;
            } catch (Exception ex) { exists = false; }

            if (exists) {
                BudgetRepository.Budget existingBudget;
                try {
                    existingBudget = budgetRepo.getByUniqueKeyOrNull(
                        userUid, BudgetRepository.BASE_BUDGET_MONTH, currency, sub.id());
                } catch (Exception ex2) { existingBudget = null; }
                if (existingBudget != null) {
                    long units = existingBudget.limitCents() / 100L;
                    rawValue[0] = units;
                    String formatted = formatThousands(String.valueOf(units));
                    formatting[0] = true;
                    limitField.setText(formatted);
                    if (!formatted.isEmpty()) limitField.positionCaret(formatted.length());
                    formatting[0] = false;
                    boolean valid = units > 0;
                    btnSave.setDisable(!valid);
                    btnSave.setStyle(valid ? SAVE_STYLE_ON : SAVE_STYLE_OFF);
                }
                statusIcon.setIconLiteral("fas-check-circle");
                statusIcon.setIconColor(javafx.scene.paint.Color.web("#2563EB"));
                statusTitle.setText("Ya tienes un límite configurado");
                statusTitle.setStyle("-fx-font-size: 12px; -fx-font-weight: 700; -fx-text-fill: #1D4ED8;");
                statusSub.setText("Se actualizará el límite existente para esta subcategoría.");
                statusBox.setStyle("-fx-background-color: rgba(37,99,235,0.08); "
                    + "-fx-border-color: rgba(37,99,235,0.22); -fx-border-width: 1; "
                    + "-fx-background-radius: 10; -fx-border-radius: 10; -fx-padding: 12 14 12 14;");
                btnSave.setText("Actualizar límite");
            } else {
                statusIcon.setIconLiteral("fas-plus-circle");
                statusIcon.setIconColor(javafx.scene.paint.Color.web("#16A34A"));
                statusTitle.setText("Crear nuevo límite");
                statusTitle.setStyle("-fx-font-size: 12px; -fx-font-weight: 700; -fx-text-fill: #15803D;");
                statusSub.setText("No hay límite configurado para \"" + sub.name() + "\".");
                statusBox.setStyle("-fx-background-color: rgba(22,163,74,0.08); "
                    + "-fx-border-color: rgba(22,163,74,0.22); -fx-border-width: 1; "
                    + "-fx-background-radius: 10; -fx-border-radius: 10; -fx-padding: 12 14 12 14;");
                btnSave.setText("Guardar límite");
            }
            if (!statusBox.isVisible()) {
                statusBox.setVisible(true);
                statusBox.setManaged(true);
                FadeTransition ft = new FadeTransition(Duration.millis(150), statusBox);
                ft.setFromValue(0.0); ft.setToValue(1.0); ft.play();
            }
        };

        catCombo.valueProperty().addListener((o, ov, nv) -> checkState.run());
        subCombo.valueProperty().addListener((o, ov, nv) -> checkState.run());

        // ── Acción guardar (BD real) ───────────────────────────────
        btnSave.setOnAction(e -> {
            CategoryRepository.Category selSub = subCombo.getValue();
            if (selSub == null || rawValue[0] <= 0) return;

            String categoryId = selSub.id();
            String month = BudgetRepository.BASE_BUDGET_MONTH;

            try {
                BudgetRepository.Budget existing =
                    budgetRepo.getByUniqueKeyOrNull(userUid, month, currency, categoryId);
                long limitCents = rawValue[0] * 100L;
                if (existing != null) {
                    budgetRepo.update(userUid, existing.id(), month, categoryId,
                        limitCents, currency);
                } else {
                    budgetRepo.create(userUid, month, categoryId, limitCents, currency);
                }
            } catch (Exception ex) {
                showToast(stackRoot, "✗  Error al guardar: " + ex.getMessage());
                return;
            }
            afterCloseRef[0] = refreshHolder[0];
            if (closeRef[0] != null) closeRef[0].run();
        });

        HBox footer = new HBox(12, btnCancel, btnSave);
        footer.setAlignment(Pos.CENTER);

        // ── Formulario + scroll ───────────────────────────────────
        VBox form = new VBox(20, fieldCategoria, fieldSubcategoria,
            fieldLimite, fieldMes, statusBox, infoBox);
        VBox.setVgrow(form, Priority.ALWAYS);
        form.setPadding(new Insets(0, 24, 16, 24));

        ScrollPane formScroll = new ScrollPane(form);
        formScroll.setFitToWidth(true);
        formScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        formScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        formScroll.setStyle(scrollBg);
        VBox.setVgrow(formScroll, Priority.ALWAYS);

        Region footerDivider = new Region();
        footerDivider.setPrefHeight(1);
        footerDivider.setMaxHeight(1);
        footerDivider.setStyle("-fx-background-color: " + footerDivClr + ";");
        footerDivider.setMaxWidth(Double.MAX_VALUE);

        // ── Drawer VBox ───────────────────────────────────────────
        VBox drawer = new VBox(0);
        drawer.setPrefWidth(width);
        drawer.setMaxWidth(width);
        drawer.setStyle("-fx-background-color: " + drawerBg + "; "
            + "-fx-background-radius: 16 0 0 16; -fx-border-radius: 16 0 0 16; "
            + drawerShadow);
        // Aplicar stylesheet del tema para combos, text-field, text-secondary
        java.net.URL themeUrl = BudgetView.class.getResource(dk ? "/styles/dark.css" : "/styles/light.css");
        if (themeUrl != null) drawer.getStylesheets().add(themeUrl.toExternalForm());

        VBox headerSection = new VBox(16, drawerHeader, divider);
        headerSection.setPadding(new Insets(24, 24, 16, 24));

        VBox footerSection = new VBox(12, footerDivider, footer);
        footerSection.setPadding(new Insets(12, 24, 20, 24));

        drawer.getChildren().addAll(headerSection, formScroll, footerSection);

        // ── Pre-carga: se aplica cuando openRef invoca openDrawer ──
        // Se expone via Runnable[] para que openDrawer la llame tras la animación
        Runnable applyPreload = () -> {
            if (preloadHolder[0] instanceof CategoryRepository.Category preRoot) {
                CategoryRepository.Category preSub = preloadHolder[1] instanceof CategoryRepository.Category c ? c : null;
                preloadHolder[0] = null;
                preloadHolder[1] = null;
                catCombo.setValue(preRoot);
                if (preSub != null) {
                    javafx.application.Platform.runLater(() -> subCombo.setValue(preSub));
                }
            } else {
                catCombo.setValue(null);
                subCombo.setValue(null);
                limitField.clear();
                rawValue[0] = 0L;
                statusBox.setVisible(false);
                statusBox.setManaged(false);
                statusBox.setOpacity(0.0);
            }
        };
        drawer.getProperties().put("applyPreload", applyPreload);

        return drawer;
    }

    // ── Drawer para nueva / editar meta ─────────────────────────
    private static VBox buildNewGoalDrawer(double width, StackPane stackRoot,
                                           Runnable[] closeRef,
                                           String userUid,
                                           String currency,
                                           GoalService[] goalServiceRef,
                                           GoalRepository.Goal[] metaEditRef,
                                           Runnable[] metasRefreshHolder,
                                           java.util.function.Supplier<Boolean> darkTheme) {
        final boolean dk = darkTheme != null && Boolean.TRUE.equals(darkTheme.get());

        String drawerBg      = dk ? "#0F172A"  : "white";
        String dividerColor  = dk ? "rgba(255,255,255,0.10)" : "#E2E8F0";
        String subtitleColor = dk ? "#94A3B8"  : "#64748B";
        String titleColor    = dk ? "#F8FAFC"  : "#0F172A";
        String inputBg       = dk ? "rgba(255,255,255,0.05)" : "#F1F5F9";
        String inputBorder   = dk ? "rgba(255,255,255,0.10)" : "#E2E8F0";
        String errorColor    = "#EF4444";

        // ── Drawer root ───────────────────────────────────────────
        VBox drawer = new VBox(0);
        drawer.setPrefWidth(width);
        drawer.setMaxWidth(width);
        drawer.setMinWidth(width);
        drawer.setStyle("-fx-background-color: " + drawerBg + ";");

        // ── Header ──────────────────────────────────────────────────
        FontIcon closeIcon = new FontIcon("fas-times");
        closeIcon.setIconSize(18);
        closeIcon.setIconColor(javafx.scene.paint.Color.web(subtitleColor));
        Button btnClose = new Button();
        btnClose.setGraphic(closeIcon);
        btnClose.setId("drawerCloseBtn");
        btnClose.setStyle("-fx-background-color: transparent; -fx-padding: 8;");

        Label titleLabel = new Label("Nueva meta");
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: 800; -fx-text-fill: " + titleColor + ";");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(0, titleLabel, spacer, btnClose);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(16, 16, 16, 20));

        Region divider = new Region();
        divider.setPrefHeight(1);
        divider.setMaxHeight(1);
        divider.setStyle("-fx-background-color: " + dividerColor + ";");

        // ── Preview icono dinámico ──────────────────────────────────
        FontIcon previewIcon = new FontIcon("fas-piggy-bank");
        previewIcon.setIconSize(22);
        previewIcon.setIconColor(javafx.scene.paint.Color.web("#3B82F6"));

        StackPane iconCircle = new StackPane(previewIcon);
        iconCircle.setPrefSize(46, 46);
        iconCircle.setMaxSize(46, 46);
        iconCircle.setMinSize(46, 46);
        iconCircle.setStyle("-fx-background-color: rgba(59,130,246,0.12); -fx-background-radius: 50;");

        Label previewHint = new Label("El icono se asigna automáticamente");
        previewHint.setStyle("-fx-font-size: 11px; -fx-text-fill: " + subtitleColor + ";");

        HBox iconPreviewRow = new HBox(12, iconCircle, previewHint);
        iconPreviewRow.setAlignment(Pos.CENTER_LEFT);
        iconPreviewRow.setPadding(new Insets(0, 0, 4, 0));

        // ── Campo nombre ────────────────────────────────────────────
        TextField nameField = new TextField();
        nameField.setPromptText("Ej: Viaje a Cancún");
        nameField.setStyle(
            "-fx-background-color: " + inputBg + "; -fx-border-color: " + inputBorder + "; "
            + "-fx-border-radius: 10; -fx-background-radius: 10; "
            + "-fx-padding: 12 14; -fx-font-size: 13px; -fx-text-fill: " + titleColor + ";"
        );
        nameField.textProperty().addListener((obs, oldVal, newVal) -> {
            String ico = com.myfinaces.service.GoalService.determinarIcono(newVal);
            javafx.animation.FadeTransition ft = new javafx.animation.FadeTransition(
                javafx.util.Duration.millis(110), iconCircle);
            ft.setFromValue(1.0); ft.setToValue(0.3);
            ft.setOnFinished(ev -> {
                previewIcon.setIconLiteral(ico);
                javafx.animation.FadeTransition fi = new javafx.animation.FadeTransition(
                    javafx.util.Duration.millis(110), iconCircle);
                fi.setFromValue(0.3); fi.setToValue(1.0); fi.play();
            });
            ft.play();
        });

        VBox fieldNombreContent = new VBox(8, iconPreviewRow, nameField);
        VBox fieldNombre = buildDrawerField(1, "Nombre de la meta", fieldNombreContent);

        // ── Campo monto ─────────────────────────────────────────────
        TextField amountField = new TextField();
        amountField.setPromptText("$ 1.500.000");
        amountField.setStyle(
            "-fx-background-color: " + inputBg + "; -fx-border-color: " + inputBorder + "; "
            + "-fx-border-radius: 10; -fx-background-radius: 10; "
            + "-fx-padding: 12 14; -fx-font-size: 13px; -fx-text-fill: " + titleColor + ";"
        );

        UiDialogs.restrictToDecimalAmount(amountField);
        Label amountError = new Label("El monto debe ser mayor a 0");
        amountError.setStyle("-fx-font-size: 11px; -fx-text-fill: " + errorColor + ";");
        amountError.setVisible(false);
        amountError.setManaged(false);
        VBox fieldMontoContent = new VBox(4, amountField, amountError);
        VBox fieldMonto = buildDrawerField(2, "Monto objetivo", fieldMontoContent);

        // ── Campo fecha ─────────────────────────────────────────────
        DatePicker datePicker = new DatePicker();
        datePicker.setPromptText("Opcional");
        datePicker.setMaxWidth(Double.MAX_VALUE);
        datePicker.setStyle(
            "-fx-background-color: " + inputBg + "; -fx-border-color: " + inputBorder + "; "
            + "-fx-border-radius: 10; -fx-background-radius: 10;"
        );
        VBox fieldFecha = buildDrawerField(3, "Fecha objetivo (opcional)", datePicker);

        // ── Botón guardar ───────────────────────────────────────────
        Button btnGuardar = new Button("Crear meta");
        btnGuardar.setStyle(
            "-fx-background-color: #2563EB; -fx-text-fill: white; "
            + "-fx-font-weight: 700; -fx-font-size: 14px; "
            + "-fx-background-radius: 10; -fx-padding: 14 24; -fx-cursor: hand;"
        );
        btnGuardar.setMaxWidth(Double.MAX_VALUE);
        btnGuardar.setDisable(true);

        // Habilitar botón si nombre no está vacío (cualquier campo puede disparar el cambio)
        Runnable checkEnabled = () ->
            btnGuardar.setDisable(nameField.getText() == null || nameField.getText().isBlank());
        nameField.textProperty().addListener((obs, o, n) -> checkEnabled.run());
        amountField.textProperty().addListener((obs, o, n) -> checkEnabled.run());
        datePicker.valueProperty().addListener((obs, o, n) -> checkEnabled.run());

        // Acción guardar
        btnGuardar.setOnAction(ev -> {
            String nombre = nameField.getText().trim();
            if (nombre.isBlank()) return;

            // Parsear monto
            long targetCents = 0L;
            String amountText = amountField.getText().replaceAll("[^0-9]", "");
            if (!amountText.isBlank()) {
                try { targetCents = Long.parseLong(amountText) * 100L; } catch (NumberFormatException ignored) {}
            }
            if (targetCents <= 0) {
                amountError.setVisible(true);
                amountError.setManaged(true);
                return;
            }
            amountError.setVisible(false);
            amountError.setManaged(false);

            // Parsear fecha
            long targetDateEpoch = 0L;
            if (datePicker.getValue() != null) {
                targetDateEpoch = datePicker.getValue()
                    .atStartOfDay(java.time.ZoneId.systemDefault())
                    .toEpochSecond();
            }

            GoalService service = goalServiceRef[0];
            if (service == null) {
                showToast(stackRoot, "✗ Servicio no disponible");
                return;
            }

            GoalRepository.Goal editando = metaEditRef[0];
            final long finalTargetCents = targetCents;
            final long finalTargetDate  = targetDateEpoch;

            btnGuardar.setDisable(true);
            new Thread(() -> {
                try {
                    if (editando == null) {
                        service.crearMeta(userUid, nombre, currency, finalTargetCents, finalTargetDate);
                    } else {
                        service.actualizarMeta(userUid, editando.id(), nombre, currency, finalTargetCents, finalTargetDate);
                    }
                    javafx.application.Platform.runLater(() -> {
                        if (closeRef[0] != null) closeRef[0].run();
                        if (metasRefreshHolder[0] != null) metasRefreshHolder[0].run();
                    });
                } catch (Exception ex) {
                    javafx.application.Platform.runLater(() -> {
                        showToast(stackRoot, "✗ Error: " + ex.getMessage());
                        btnGuardar.setDisable(false);
                    });
                }
            }).start();
        });

        VBox buttonBox = new VBox(btnGuardar);
        buttonBox.setPadding(new Insets(8, 0, 0, 0));

        VBox formBox = new VBox(16, fieldNombre, fieldMonto, fieldFecha, buttonBox);
        formBox.setPadding(new Insets(20, 20, 20, 20));

        ScrollPane scroll = new ScrollPane(formBox);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("-fx-background-color: transparent; -fx-padding: 0;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        drawer.getChildren().addAll(header, divider, scroll);

        // Guardar refs en UserData para loadNewGoalDrawerData
        java.util.Map<String, Object> refs = new java.util.HashMap<>();
        refs.put("titleLabel", titleLabel);
        refs.put("nameField", nameField);
        refs.put("amountField", amountField);
        refs.put("datePicker", datePicker);
        refs.put("btnGuardar", btnGuardar);
        refs.put("previewIcon", previewIcon);
        drawer.setUserData(refs);

        btnClose.setOnAction(e -> { if (closeRef[0] != null) closeRef[0].run(); });

        java.net.URL themeUrl = BudgetView.class.getResource("/styles/theme.css");
        if (themeUrl != null) drawer.getStylesheets().add(themeUrl.toExternalForm());

        return drawer;
    }

    // ── Carga datos en el drawer según modo CREATE / EDIT ────────
    @SuppressWarnings("unchecked")
    private static void loadNewGoalDrawerData(VBox drawer, GoalRepository.Goal meta) {
        if (drawer == null) return;
        Object ud = drawer.getUserData();
        if (!(ud instanceof java.util.Map)) return;
        java.util.Map<String, Object> refs = (java.util.Map<String, Object>) ud;

        Label  titleLabel  = (Label)      refs.get("titleLabel");
        TextField nameField  = (TextField)  refs.get("nameField");
        TextField amountField = (TextField)  refs.get("amountField");
        DatePicker datePicker = (DatePicker) refs.get("datePicker");
        Button btnGuardar   = (Button)     refs.get("btnGuardar");
        FontIcon previewIcon = (FontIcon)   refs.get("previewIcon");

        if (meta == null) {
            // Modo crear
            if (titleLabel  != null) titleLabel.setText("Nueva meta");
            if (nameField   != null) nameField.clear();
            if (amountField != null) amountField.clear();
            if (datePicker  != null) datePicker.setValue(null);
            if (btnGuardar  != null) btnGuardar.setText("Crear meta");
            if (previewIcon != null) previewIcon.setIconLiteral("fas-piggy-bank");
        } else {
            // Modo editar
            if (titleLabel  != null) titleLabel.setText("Editar meta");
            if (nameField   != null) nameField.setText(meta.name());
            if (amountField != null) amountField.setText(String.valueOf(meta.targetCents() / 100));
            if (datePicker  != null && meta.targetDateEpochSec() > 0) {
                datePicker.setValue(java.time.Instant.ofEpochSecond(meta.targetDateEpochSec())
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate());
            } else if (datePicker != null) {
                datePicker.setValue(null);
            }
            if (btnGuardar  != null) btnGuardar.setText("Guardar cambios");
            if (previewIcon != null)
                previewIcon.setIconLiteral(com.myfinaces.service.GoalService.determinarIcono(meta.name()));
        }
    }

    private static VBox buildDrawerField(int step, String label, Node control) {
        Label stepBadge = new Label(String.valueOf(step));
        stepBadge.setStyle(
            "-fx-background-color: #2563EB; -fx-text-fill: white; "
            + "-fx-font-size: 11px; -fx-font-weight: 800; "
            + "-fx-background-radius: 50; "
            + "-fx-min-width: 22; -fx-min-height: 22; "
            + "-fx-max-width: 22; -fx-max-height: 22; "
            + "-fx-alignment: center;");

        Label fieldLabel = new Label(label);
        fieldLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: 700;");

        HBox labelRow = new HBox(8, stepBadge, fieldLabel);
        labelRow.setAlignment(Pos.CENTER_LEFT);

        VBox field = new VBox(8, labelRow, control);
        field.setAlignment(Pos.TOP_LEFT);
        return field;
    }

    private static ComboBox<String> buildDrawerComboBox(String prompt) {
        ComboBox<String> combo = new ComboBox<>();
        combo.setPromptText(prompt);
        combo.setMaxWidth(Double.MAX_VALUE);
        combo.getStyleClass().add("budget-month-picker");
        combo.setStyle(
            "-fx-pref-height: 44; -fx-font-size: 13px; "
            + "-fx-background-radius: 10; -fx-border-radius: 10;");
        return combo;
    }

    private static void openDrawer(VBox drawer, Pane overlay, double drawerWidth, Runnable onClose) {
        overlay.setVisible(true);
        overlay.setOnMouseClicked(e -> onClose.run());

        // Cerrar botón
        drawer.lookupAll("#drawerCloseBtn").forEach(n -> {
            if (n instanceof Button b) b.setOnAction(e -> onClose.run());
        });

        // Aplicar pre-carga si existe (inyectada en preloadHolder[2])
        Object applyFn = drawer.getProperties().get("applyPreload");
        if (applyFn instanceof Runnable r) r.run();

        TranslateTransition slide = new TranslateTransition(Duration.millis(220), drawer);
        slide.setFromX(drawerWidth);
        slide.setToX(0);
        slide.setInterpolator(Interpolator.EASE_OUT);
        slide.play();

        FadeTransition fade = new FadeTransition(Duration.millis(200), overlay);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        fade.play();
    }

    private static void closeDrawer(VBox drawer, Pane overlay) {
        closeDrawer(drawer, overlay, null);
    }

    private static void closeDrawer(VBox drawer, Pane overlay, Runnable onClosed) {
        final double drawerWidth = drawer.getPrefWidth();

        FadeTransition fade = new FadeTransition(Duration.millis(180), overlay);
        fade.setFromValue(1.0);
        fade.setToValue(0.0);
        fade.setOnFinished(e -> {
            overlay.setVisible(false);
            if (onClosed != null) onClosed.run();
        });
        fade.play();

        TranslateTransition slide = new TranslateTransition(Duration.millis(200), drawer);
        slide.setFromX(0);
        slide.setToX(drawerWidth);
        slide.setInterpolator(Interpolator.EASE_IN);
        slide.play();
    }

    private static void showToast(StackPane root, String message) {
        FontIcon checkIcon = new FontIcon("fas-check-circle");
        checkIcon.setIconSize(14);
        checkIcon.setIconColor(javafx.scene.paint.Color.WHITE);

        Label toastLabel = new Label(message);
        toastLabel.setStyle(
            "-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: white;");

        HBox toast = new HBox(8, checkIcon, toastLabel);
        toast.setAlignment(Pos.CENTER_LEFT);
        toast.setStyle(
            "-fx-background-color: #1E293B; "
            + "-fx-background-radius: 12; "
            + "-fx-padding: 14 20 14 16; "
            + "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.28), 16, 0.15, 0, 4);");
        toast.setMouseTransparent(true);
        toast.setOpacity(0.0);

        StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toast, new Insets(0, 0, 32, 0));
        root.getChildren().add(toast);

        FadeTransition fadeIn = new FadeTransition(Duration.millis(200), toast);
        fadeIn.setFromValue(0.0);
        fadeIn.setToValue(1.0);

        PauseTransition hold = new PauseTransition(Duration.millis(2200));

        FadeTransition fadeOut = new FadeTransition(Duration.millis(300), toast);
        fadeOut.setFromValue(1.0);
        fadeOut.setToValue(0.0);
        fadeOut.setOnFinished(e -> root.getChildren().remove(toast));

        fadeIn.setOnFinished(e -> hold.play());
        hold.setOnFinished(e -> fadeOut.play());
        fadeIn.play();
    }

    private static String formatMoney(long cents, String currency) {
        return DashboardFormatters.formatMoney(cents, currency);
    }

    private static boolean isExpenseCategory(CategoryRepository.Category root) {
        if (root == null) return false;
        String kind = root.kind();
        if (kind != null && !kind.isBlank() && !"BOTH".equalsIgnoreCase(kind.trim())) {
            return "EXPENSE".equalsIgnoreCase(kind.trim());
        }
        String name = root.name() == null ? "" : root.name().trim().toUpperCase(Locale.ROOT);
        return name.startsWith("GASTO") || name.startsWith("EGRESO");
    }

    private static String formatThousands(String digits) {
        try {
            long value = Long.parseLong(digits);
            NumberFormat nf = NumberFormat.getNumberInstance(Locale.forLanguageTag("es-CO"));
            nf.setGroupingUsed(true);
            return nf.format(value);
        } catch (NumberFormatException e) {
            return digits;
        }
    }

    // ── Card resumen de metas ────────────────────────────────────
    private static HBox buildMetasSummaryCard(boolean dk, GoalService goalService, String userUid) {
        // Paleta de colores según tema
        String cardBg       = dk ? "#0F172A" : "white";
        String cardBorder   = dk ? "rgba(255,255,255,0.08)" : "rgba(0,0,0,0.06)";
        String shadow       = dk ? "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.50), 16, 0.18, 0, 4);"
                                : "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.10), 16, 0.18, 0, 4);";
        String labelColor   = dk ? "#94A3B8" : "#64748B";
        String valueColor   = dk ? "#E5E7EB" : "#0F172A";
        String subtextColor = dk ? "#64748B" : "#94A3B8";
        String dividerColor = dk ? "rgba(255,255,255,0.08)" : "rgba(0,0,0,0.06)";

        // Calcular valores reales
        long totalAhorrado = 0;
        int metasActivas = 0;
        String metaMasCercana = "Sin metas";
        String metaCercanaSubtext = "Crea tu primera meta";
        String logroEstimado = "-";
        String logroSubtext = "Sin metas activas";
        
        try {
            java.util.List<com.myfinaces.db.GoalRepository.Goal> goals = goalService.obtenerMetas(userUid);
            metasActivas = goals.size();
            
            GoalService.MetaInfo metaCercana = null;
            int maxPorcentaje = -1;
            
            for (com.myfinaces.db.GoalRepository.Goal g : goals) {
                long saldo = goalService.calcularSaldo(userUid, g.accountId());
                totalAhorrado += saldo;
                int porcentaje = goalService.calcularPorcentaje(saldo, g.targetCents());
                
                if (porcentaje < 100 && porcentaje > maxPorcentaje) {
                    maxPorcentaje = porcentaje;
                    long faltan = Math.max(0, g.targetCents() - saldo);
                    metaCercana = new GoalService.MetaInfo(g, saldo, faltan, porcentaje, 
                        goalService.determinarEstado(porcentaje), "", "", null, 0L, null);
                }
            }
            
            if (metaCercana != null) {
                metaMasCercana = metaCercana.name();
                metaCercanaSubtext = metaCercana.percent() + "% completada";
                
                // Calcular fecha estimada
                if (metaCercana.goal().targetDateEpochSec() > 0) {
                    java.time.Instant instant = java.time.Instant.ofEpochSecond(metaCercana.goal().targetDateEpochSec());
                    java.time.LocalDate date = java.time.LocalDate.ofInstant(instant, java.time.ZoneId.systemDefault());
                    logroEstimado = date.format(java.time.format.DateTimeFormatter.ofPattern("MMM yyyy", java.util.Locale.forLanguageTag("es-CO")));
                    logroSubtext = "próxima meta";
                }
            }
        } catch (Exception e) {
            System.out.println("[Metas] Error calculando resumen: " + e.getMessage());
        }
        
        java.text.NumberFormat nf = java.text.NumberFormat.getCurrencyInstance(java.util.Locale.forLanguageTag("es-CO"));
        nf.setMaximumFractionDigits(0);
        String totalAhorradoStr = nf.format(totalAhorrado / 100.0);

        // Iconos circulares con colores suaves distintos
        String[] iconColors = { "#10B981", "#3B82F6", "#F59E0B", "#8B5CF6" };
        String[] iconBgColors = {
            dk ? "rgba(16,185,129,0.15)" : "rgba(16,185,129,0.12)",
            dk ? "rgba(59,130,246,0.15)" : "rgba(59,130,246,0.12)",
            dk ? "rgba(245,158,11,0.15)"  : "rgba(245,158,11,0.12)",
            dk ? "rgba(139,92,246,0.15)"  : "rgba(139,92,246,0.12)"
        };
        String[] iconCodes = { "fas-wallet", "fas-check-circle", "fas-chart-line", "fas-calendar" };
        String[] labels    = { "Total ahorrado", "Metas activas", "Meta más cercana", "Logro estimado" };
        String[] values    = { totalAhorradoStr, String.valueOf(metasActivas), metaMasCercana, logroEstimado };
        String[] subtexts  = { "en todas tus metas", "en progreso", metaCercanaSubtext, logroSubtext };

        HBox card = new HBox(0);
        card.setPadding(new Insets(20));
        card.setSpacing(24);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(card, Priority.ALWAYS);
        card.setStyle(
            "-fx-background-color: " + cardBg + "; "
            + "-fx-background-radius: 12; -fx-border-radius: 12; "
            + "-fx-border-color: " + cardBorder + "; -fx-border-width: 1; "
            + shadow
        );

        for (int i = 0; i < 4; i++) {
            // Icono circular
            FontIcon icon = new FontIcon(iconCodes[i]);
            icon.setIconSize(16);
            icon.setIconColor(javafx.scene.paint.Color.web(iconColors[i]));

            StackPane iconCircle = new StackPane(icon);
            iconCircle.setAlignment(Pos.CENTER);
            iconCircle.setMinSize(44, 44);
            iconCircle.setPrefSize(44, 44);
            iconCircle.setMaxSize(44, 44);
            iconCircle.setStyle(
                "-fx-background-color: " + iconBgColors[i] + "; "
                + "-fx-background-radius: 22;"
            );

            // Label
            Label lbl = new Label(labels[i]);
            lbl.setStyle("-fx-font-size: 11px; -fx-text-fill: " + labelColor + ";");

            // Valor (bold)
            Label val = new Label(values[i]);
            val.setStyle("-fx-font-size: 16px; -fx-font-weight: 700; -fx-text-fill: " + valueColor + ";");

            // Subtexto
            Label sub = new Label(subtexts[i]);
            sub.setStyle("-fx-font-size: 10px; -fx-text-fill: " + subtextColor + ";");

            // Textos apilados verticalmente (label arriba, valor medio, subtexto abajo)
            VBox textColumn = new VBox(2, lbl, val, sub);
            textColumn.setAlignment(Pos.CENTER_LEFT);

            // Bloque horizontal: icono a la izquierda, textos a la derecha
            HBox block = new HBox(12, iconCircle, textColumn);
            block.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(block, Priority.ALWAYS);

            card.getChildren().add(block);

            // Separador vertical (excepto último)
            if (i < 3) {
                Region sep = new Region();
                sep.setPrefWidth(1);
                sep.setPrefHeight(45);
                sep.setStyle("-fx-background-color: " + dividerColor + ";");
                card.getChildren().add(sep);
            }
        }

        return card;
    }

    // ── Grid de metas agrupado por estado ─────────────────────────
    private static VBox buildMetasGrid(boolean dk, GoalService goalService, String userUid, Runnable[] goalOpenRef,
                                        GoalService.MetaInfo[] selectedMetaRef,
                                        Runnable[] newGoalEditRef,
                                        GoalRepository.Goal[] metaEditRef,
                                        VBox[] newGoalDrawerRef) {
        // Paleta de colores
        String cardBg       = dk ? "#0F172A" : "white";
        String cardBorder   = dk ? "rgba(255,255,255,0.08)" : "rgba(0,0,0,0.06)";
        String shadow       = dk ? "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.50), 16, 0.18, 0, 4);"
                                : "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.10), 16, 0.18, 0, 4);";
        String titleColor   = dk ? "#E5E7EB" : "#0F172A";
        String descColor    = dk ? "#94A3B8" : "#64748B";
        String labelColor   = dk ? "#94A3B8" : "#64748B";
        String valueColor   = dk ? "#E5E7EB" : "#0F172A";
        String savedColor   = "#10B981";
        String missingColor = "#EF4444";
        String dateColor    = dk ? "#64748B" : "#94A3B8";
        String barTrack     = dk ? "#1E293B" : "#E2E8F0";
        String insightBg    = dk ? "rgba(59,130,246,0.08)" : "rgba(59,130,246,0.06)";
        String insightColor = "#3B82F6";

        // ── Cargar metas reales ────────────────────────────────────
        java.util.List<GoalService.MetaInfo> metas = new java.util.ArrayList<>();
        try {
            java.util.List<com.myfinaces.db.GoalRepository.Goal> goalsDb = goalService.obtenerMetas(userUid);
            for (com.myfinaces.db.GoalRepository.Goal g : goalsDb) {
                long saldo = goalService.calcularSaldo(userUid, g.accountId());
                int porcentaje = goalService.calcularPorcentaje(saldo, g.targetCents());
                long faltan = Math.max(0, g.targetCents() - saldo);
                GoalService.EstadoMeta estado = goalService.determinarEstado(porcentaje);
                
                // Obtener movimientos para calcular proyecciones y alertas
                java.util.List<com.myfinaces.db.TransferRepository.TransferRow> movimientos = 
                    goalService.obtenerMovimientos(userUid, g.accountId());
                // Usar promedio mensual basado en tiempo transcurrido (más preciso)
                long promedioAporte = goalService.calcularPromedioMensual(movimientos, g.accountId());
                
                // Calcular días sin movimiento para alertas
                int diasSinMovimiento = goalService.diasSinMovimiento(movimientos, g.accountId());
                GoalService.AlertaMeta alerta = goalService.generarAlerta(porcentaje, diasSinMovimiento, promedioAporte);
                
                // Calcular proyecciones
                GoalService.ProyeccionMeta proyeccion = null;
                String aportesRestantes = null;
                String insight = goalService.generarInsight(porcentaje, faltan);
                
                // Si hay alerta de estancamiento, mostrarla en lugar del insight
                if (alerta != null && alerta.tipo() == GoalService.TipoAlerta.ESTANCADA) {
                    insight = alerta.tipo().icono + " " + alerta.mensaje();
                }
                
                // Solo mostrar proyecciones si hay datos y la meta no está completada
                if (porcentaje < 100 && promedioAporte > 0) {
                    proyeccion = goalService.estimarCumplimiento(faltan, promedioAporte);
                    aportesRestantes = goalService.estimarAportesRestantes(faltan, promedioAporte);
                    
                    // Si tenemos proyección, usarla como insight principal
                    if (proyeccion != null) {
                        insight = "💡 " + proyeccion.mensajeMotivacional();
                        if (aportesRestantes != null) {
                            insight += " — " + aportesRestantes;
                        }
                    }
                }
                
                // Formatear fecha
                String fechaStr = "Sin fecha límite";
                if (g.targetDateEpochSec() > 0) {
                    java.time.Instant instant = java.time.Instant.ofEpochSecond(g.targetDateEpochSec());
                    java.time.LocalDate date = java.time.LocalDate.ofInstant(instant, java.time.ZoneId.systemDefault());
                    fechaStr = date.format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy", java.util.Locale.forLanguageTag("es-CO")));
                }
                
                metas.add(new GoalService.MetaInfo(g, saldo, faltan, porcentaje, estado, insight, fechaStr, proyeccion, promedioAporte, aportesRestantes));
            }
        } catch (Exception e) {
            System.out.println("[Metas] Error cargando metas reales: " + e.getMessage());
        }

        // ── Clasificar metas ───────────────────────────────────────
        java.util.List<GoalService.MetaInfo> activas = new java.util.ArrayList<>();
        java.util.List<GoalService.MetaInfo> cercanas = new java.util.ArrayList<>();
        java.util.List<GoalService.MetaInfo> completadas = new java.util.ArrayList<>();

        for (GoalService.MetaInfo g : metas) {
            if (g.percent() == 100) completadas.add(g);
            else if (g.percent() >= 70) cercanas.add(g);
            else activas.add(g);
        }

        activas.sort((a, b) -> Integer.compare(b.percent(), a.percent()));
        cercanas.sort((a, b) -> Integer.compare(b.percent(), a.percent()));
        completadas.sort((a, b) -> Integer.compare(b.percent(), a.percent()));

        // ── Función para crear una card ───────────────────────────
        java.util.function.Function<GoalService.MetaInfo, javafx.scene.Node> createCard = (GoalService.MetaInfo g) -> {
            long missing = g.missing();
            NumberFormat nf = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-CO"));
            nf.setMaximumFractionDigits(0);

            String stateLabel, stateEmoji, stateColor, stateBgColor;
            int percent = g.percent();
            if (percent == 0) {
                stateLabel = "Nueva"; stateEmoji = "💡";
                stateColor = dk ? "#94A3B8" : "#64748B";
                stateBgColor = dk ? "rgba(148,163,184,0.15)" : "rgba(100,116,139,0.12)";
            } else if (percent < 70) {
                stateLabel = "En progreso"; stateEmoji = "🚀";
                stateColor = "#3B82F6";
                stateBgColor = dk ? "rgba(59,130,246,0.15)" : "rgba(59,130,246,0.12)";
            } else if (percent < 100) {
                stateLabel = "Casi"; stateEmoji = "🔥";
                stateColor = "#F59E0B";
                stateBgColor = dk ? "rgba(245,158,11,0.15)" : "rgba(245,158,11,0.12)";
            } else {
                stateLabel = "Completada"; stateEmoji = "🎉";
                stateColor = "#10B981";
                stateBgColor = dk ? "rgba(16,185,129,0.20)" : "rgba(16,185,129,0.15)";
            }

            // Insight contextual (Opción D)
            GoalService.InsightContextual insightCtx = goalService.generarInsightContextual(
                percent, g.missing(), g.promedioAporte(), g.goal().targetDateEpochSec());
            final String insightText = insightCtx.texto();
            final String insightColorFinal = insightCtx.color();
            String insightBgAlpha = insightCtx.color().replace("#", "");
            int ir = Integer.parseInt(insightBgAlpha.substring(0,2), 16);
            int ig = Integer.parseInt(insightBgAlpha.substring(2,4), 16);
            int ib = Integer.parseInt(insightBgAlpha.substring(4,6), 16);
            final String insightBgFinal = dk ? "rgba(" + ir + "," + ig + "," + ib + ",0.12)"
                                            : "rgba(" + ir + "," + ig + "," + ib + ",0.08)";

            VBox cardContent = new VBox(10);
            cardContent.setPadding(new Insets(14, 16, 14, 16));
            cardContent.setFillWidth(true);
            cardContent.setMaxWidth(Double.MAX_VALUE);

            FontIcon goalIcon = new FontIcon(g.icon());
            goalIcon.setIconSize(16);
            goalIcon.setIconColor(javafx.scene.paint.Color.web(g.iconColor()));
            String rgba = g.iconColor().replace("#", "");
            int r = Integer.parseInt(rgba.substring(0,2), 16);
            int gVal = Integer.parseInt(rgba.substring(2,4), 16);
            int b = Integer.parseInt(rgba.substring(4,6), 16);
            String bgRgba = dk ? "rgba(" + r + "," + gVal + "," + b + ",0.15)" : "rgba(" + r + "," + gVal + "," + b + ",0.12)";
            StackPane iconBg = new StackPane(goalIcon);
            iconBg.setMinSize(32, 32); iconBg.setPrefSize(32, 32); iconBg.setMaxSize(32, 32);
            iconBg.setStyle("-fx-background-color: " + bgRgba + "; -fx-background-radius: 8;");

            Label nameLabel = new Label(g.name());
            nameLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: 700; -fx-text-fill: " + titleColor + ";");
            nameLabel.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(nameLabel, Priority.ALWAYS);

            HBox header = new HBox(8, iconBg, nameLabel);
            header.setAlignment(Pos.CENTER_LEFT);

            Label descLabel = new Label(g.description());
            descLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + descColor + ";");

            Label savedLabel = new Label(nf.format(g.saved() / 100.0));
            savedLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: " + savedColor + ";");
            Label savedTitle = new Label("Guardado");
            savedTitle.setStyle("-fx-font-size: 9px; -fx-text-fill: " + labelColor + ";");
            VBox savedBox = new VBox(1, savedLabel, savedTitle);

            Label targetLabel = new Label(nf.format(g.target() / 100.0));
            targetLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: " + valueColor + ";");
            Label targetTitle = new Label("Objetivo");
            targetTitle.setStyle("-fx-font-size: 9px; -fx-text-fill: " + labelColor + ";");
            VBox targetBox = new VBox(1, targetLabel, targetTitle);

            Label missingLabel = new Label(nf.format(missing / 100.0));
            missingLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: " + missingColor + ";");
            Label missingTitle = new Label("Faltan");
            missingTitle.setStyle("-fx-font-size: 9px; -fx-text-fill: " + labelColor + ";");
            VBox missingBox = new VBox(1, missingLabel, missingTitle);

            Region mspacer1 = new Region(); HBox.setHgrow(mspacer1, Priority.ALWAYS);
            Region mspacer2 = new Region(); HBox.setHgrow(mspacer2, Priority.ALWAYS);
            HBox metricsRow = new HBox(12, savedBox, mspacer1, targetBox, mspacer2, missingBox);
            metricsRow.setAlignment(Pos.CENTER_LEFT);

            Region track = new Region();
            track.setPrefHeight(6); track.setMaxHeight(6);
            track.setStyle("-fx-background-color: " + barTrack + "; -fx-background-radius: 3;");
            
            Region fill = new Region();
            fill.setPrefHeight(6); fill.setMaxHeight(6);
            fill.setStyle("-fx-background-color: " + g.iconColor() + "; -fx-background-radius: 3;");
            
            // Crear contenedor de la barra con ancho vinculado al porcentaje
            StackPane progressBar = new StackPane(track, fill);
            progressBar.setMaxWidth(Double.MAX_VALUE);
            StackPane.setAlignment(fill, Pos.CENTER_LEFT);
            
            // Barra dinámica: función para actualizar ancho del fill
            java.util.function.Consumer<Double> updateFillWidth = (containerWidth) -> {
                double fillWidth = containerWidth.doubleValue() * percent / 100.0;
                double finalWidth = Math.max(2, fillWidth);
                fill.setPrefWidth(finalWidth);
                fill.setMaxWidth(finalWidth); // Evitar que crezca más allá del porcentaje
            };
            
            // Listener para cuando cambia el tamaño del contenedor
            progressBar.widthProperty().addListener((obs, oldWidth, newWidth) -> {
                if (newWidth.doubleValue() > 0) {
                    updateFillWidth.accept(newWidth.doubleValue());
                }
            });
            
            // Forzar actualización después de que se renderice
            javafx.application.Platform.runLater(() -> {
                if (progressBar.getWidth() > 0) {
                    updateFillWidth.accept(progressBar.getWidth());
                } else {
                    // Si aún no tiene ancho, usar el ancho preferido
                    updateFillWidth.accept(progressBar.getPrefWidth());
                }
            });
            
            // Establecer ancho inicial conservador
            fill.setPrefWidth(Math.max(2, percent * 2));

            Label percentLabel = new Label(percent + "%");
            percentLabel.setStyle("-fx-font-size: 10px; -fx-font-weight: 600; -fx-text-fill: " + g.iconColor() + ";");
            HBox progressRow = new HBox(6, progressBar, percentLabel);
            progressRow.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(progressBar, Priority.ALWAYS);

            // Calcular alerta para esta meta (con manejo de excepciones)
            GoalService.AlertaMeta alerta = null;
            try {
                java.util.List<com.myfinaces.db.TransferRepository.TransferRow> movs = 
                    goalService.obtenerMovimientos(userUid, g.goal().accountId());
                int dias = goalService.diasSinMovimiento(movs, g.goal().accountId());
                long promedio = goalService.calcularPromedioMensual(movs, g.goal().accountId());
                alerta = goalService.generarAlerta(percent, dias, promedio);
            } catch (Exception e) {
                // Si hay error al obtener movimientos, no mostrar alerta
                alerta = null;
            }
            
            Label insightLabel = new Label(insightText);
            insightLabel.setStyle(
                "-fx-font-size: 10px; -fx-text-fill: " + insightColorFinal + "; "
                + "-fx-background-color: " + insightBgFinal + "; "
                + "-fx-background-radius: 6; -fx-padding: 4 8;"
            );
            insightLabel.setMaxWidth(Double.MAX_VALUE);
            
            // Label para alertas (solo si hay alerta y no es estancada - esa ya está en insight)
            Label alertaLabel = null;
            if (alerta != null && alerta.tipo() != GoalService.TipoAlerta.ESTANCADA && alerta.tipo() != GoalService.TipoAlerta.SIN_ALERTA) {
                alertaLabel = new Label(alerta.tipo().icono + " " + alerta.mensaje());
                String alertaBg = dk 
                    ? "rgba(" + Integer.parseInt(alerta.color().substring(1,3),16) + "," + Integer.parseInt(alerta.color().substring(3,5),16) + "," + Integer.parseInt(alerta.color().substring(5,7),16) + ",0.15)"
                    : "rgba(" + Integer.parseInt(alerta.color().substring(1,3),16) + "," + Integer.parseInt(alerta.color().substring(3,5),16) + "," + Integer.parseInt(alerta.color().substring(5,7),16) + ",0.12)";
                alertaLabel.setStyle(
                    "-fx-font-size: 10px; -fx-font-weight: 600; -fx-text-fill: " + alerta.color() + "; "
                    + "-fx-background-color: " + alertaBg + "; "
                    + "-fx-background-radius: 6; -fx-padding: 4 8;"
                );
                alertaLabel.setMaxWidth(Double.MAX_VALUE);
            }

            Label dateLabel = new Label("Fecha objetivo: " + g.date());
            dateLabel.setStyle("-fx-font-size: 9px; -fx-text-fill: " + dateColor + ";");

            Label stateBadge = new Label(stateLabel + " " + stateEmoji);
            stateBadge.setStyle(
                "-fx-font-size: 9px; -fx-font-weight: 600; "
                + "-fx-text-fill: " + stateColor + "; "
                + "-fx-background-color: " + stateBgColor + "; "
                + "-fx-background-radius: 6; -fx-padding: 2 6;"
            );

            Region fspacer = new Region(); HBox.setHgrow(fspacer, Priority.ALWAYS);
            HBox footerRow = new HBox(8, dateLabel, fspacer, stateBadge);
            footerRow.setAlignment(Pos.CENTER_LEFT);

            if (alertaLabel != null) {
                cardContent.getChildren().addAll(header, descLabel, metricsRow, progressRow, insightLabel, alertaLabel, footerRow);
            } else {
                cardContent.getChildren().addAll(header, descLabel, metricsRow, progressRow, insightLabel, footerRow);
            }

            StackPane cardPane = new StackPane(cardContent);
            cardPane.setMaxWidth(Double.MAX_VALUE);
            cardPane.setStyle(
                "-fx-background-color: " + cardBg + "; "
                + "-fx-background-radius: 10; -fx-border-radius: 10; "
                + "-fx-border-color: " + cardBorder + "; -fx-border-width: 1; "
                + shadow
            );

            // Efectos hover más pronunciados
            String shadowHover = dk 
                ? "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.60), 24, 0.25, 0, 8);"
                : "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.18), 24, 0.25, 0, 8);";
            String bgHover = dk ? "#1E293B" : "#F8FAFC";
            String borderHover = dk ? "rgba(255,255,255,0.25)" : "rgba(59,130,246,0.40)";
            
            cardPane.setOnMouseEntered(e -> {
                cardPane.setStyle(
                    "-fx-background-color: " + bgHover + "; "
                    + "-fx-background-radius: 10; -fx-border-radius: 10; "
                    + "-fx-border-color: " + borderHover + "; -fx-border-width: 2; "
                    + shadowHover
                );
                cardPane.setScaleX(1.02);
                cardPane.setScaleY(1.02);
            });
            cardPane.setOnMouseExited(e -> {
                cardPane.setStyle(
                    "-fx-background-color: " + cardBg + "; "
                    + "-fx-background-radius: 10; -fx-border-radius: 10; "
                    + "-fx-border-color: " + cardBorder + "; -fx-border-width: 1; "
                    + shadow
                );
                cardPane.setScaleX(1.0);
                cardPane.setScaleY(1.0);
            });

            // ── Botón ⋮ editar meta ───────────────────────────────
            FontIcon editBtnIcon = new FontIcon("fas-pen");
            editBtnIcon.setIconSize(10);
            editBtnIcon.setIconColor(javafx.scene.paint.Color.web(dk ? "#94A3B8" : "#64748B"));
            Button btnEditMeta = new Button();
            btnEditMeta.setGraphic(editBtnIcon);
            btnEditMeta.setStyle(
                "-fx-background-color: transparent; -fx-padding: 4 6; "
                + "-fx-background-radius: 6; -fx-cursor: hand;"
            );
            btnEditMeta.setOnMouseEntered(ev -> btnEditMeta.setStyle(
                "-fx-background-color: " + (dk ? "rgba(255,255,255,0.08)" : "rgba(0,0,0,0.06)") + "; "
                + "-fx-padding: 4 6; -fx-background-radius: 6; -fx-cursor: hand;"
            ));
            btnEditMeta.setOnMouseExited(ev -> btnEditMeta.setStyle(
                "-fx-background-color: transparent; -fx-padding: 4 6; "
                + "-fx-background-radius: 6; -fx-cursor: hand;"
            ));
            btnEditMeta.setOnAction(ev -> {
                ev.consume();
                if (metaEditRef != null && newGoalDrawerRef != null && newGoalEditRef != null) {
                    metaEditRef[0] = g.goal();
                    loadNewGoalDrawerData(newGoalDrawerRef[0], g.goal());
                    if (newGoalEditRef[0] != null) newGoalEditRef[0].run();
                }
            });
            btnEditMeta.setTooltip(new javafx.scene.control.Tooltip("Editar meta"));

            // Reemplazar header de la card para incluir botón ⋮
            header.getChildren().add(btnEditMeta);

            cardPane.setOnMouseClicked(e -> {
                selectedMetaRef[0] = g;
                if (goalOpenRef[0] != null) goalOpenRef[0].run();
            });

            return cardPane;
        };

        java.util.function.BiFunction<String, java.util.List<GoalService.MetaInfo>, javafx.scene.Node> createSection = (String title, java.util.List<GoalService.MetaInfo> list) -> {
            if (list.isEmpty()) return new VBox();

            Label titleLabel = new Label(title);
            titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: 700; -fx-text-fill: " + titleColor + ";");

            // Máx 3 cards por fila; cada card ocupa espacio equitativo con HGrow
            final int MAX_COLS = 3;
            VBox grid = new VBox(16);
            grid.setFillWidth(true);
            grid.setMaxWidth(Double.MAX_VALUE);

            int i = 0;
            while (i < list.size()) {
                int cols = Math.min(MAX_COLS, list.size() - i);
                HBox row = new HBox(16);
                row.setFillHeight(true);
                row.setMaxWidth(Double.MAX_VALUE);
                for (int j = 0; j < cols; j++) {
                    javafx.scene.Node card = createCard.apply(list.get(i + j));
                    HBox.setHgrow(card, Priority.ALWAYS);
                    row.getChildren().add(card);
                }
                // Si la fila tiene menos de MAX_COLS, rellenar con spacers para que
                // las cards mantengan el mismo ancho proporcional
                for (int j = cols; j < MAX_COLS; j++) {
                    Region spacer = new Region();
                    HBox.setHgrow(spacer, Priority.ALWAYS);
                    row.getChildren().add(spacer);
                }
                grid.getChildren().add(row);
                i += cols;
            }

            VBox section = new VBox(12, titleLabel, grid);
            section.setFillWidth(true);
            return section;
        };

        VBox mainContainer = new VBox(24);
        mainContainer.setFillWidth(true);
        mainContainer.setMaxWidth(Double.MAX_VALUE);

        javafx.scene.Node sectionActivas = createSection.apply("Activas", activas);
        javafx.scene.Node sectionCercanas = createSection.apply("Cercanas", cercanas);
        javafx.scene.Node sectionCompletadas = createSection.apply("Completadas", completadas);

        if (!activas.isEmpty()) mainContainer.getChildren().add(sectionActivas);
        if (!cercanas.isEmpty()) mainContainer.getChildren().add(sectionCercanas);
        if (!completadas.isEmpty()) mainContainer.getChildren().add(sectionCompletadas);

        return mainContainer;
    }

    // ── Drawer de detalle de meta ────────────────────────────────
    private static VBox buildGoalDetailDrawer(double width, StackPane stackRoot,
                                               Runnable[] closeRef,
                                               java.util.function.Supplier<Boolean> darkTheme,
                                               GoalService[] goalServiceRef,
                                               AccountRepository accountRepo,
                                               String userUid,
                                               GoalService.MetaInfo[] selectedMetaRef,
                                               Runnable[] metasRefreshHolder) {
        final boolean dk = darkTheme != null && Boolean.TRUE.equals(darkTheme.get());

        // Colores
        String drawerBg      = dk ? "#0F172A"  : "white";
        String titleColor    = dk ? "#E5E7EB"  : "#0F172A";
        String subtitleColor = dk ? "#94A3B8"  : "#64748B";
        String dividerColor  = dk ? "rgba(255,255,255,0.10)" : "#E2E8F0";
        String drawerShadow  = dk ? "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.60), 24, 0.20, -6, 0);"
                                  : "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.22), 24, 0.15, -6, 0);";
        String scrollBg      = dk ? "-fx-background-color: #0F172A; -fx-background: #0F172A;"
                                  : "-fx-background-color: transparent; -fx-background: transparent;";
        String savedColor    = "#10B981";
        String missingColor  = "#EF4444";
        String barTrack      = dk ? "#1E293B" : "#E2E8F0";

        // ── Botón cerrar ─────────────────────────────────────────
        FontIcon closeIcon = new FontIcon("fas-times");
        closeIcon.setIconSize(15);
        closeIcon.setIconColor(javafx.scene.paint.Color.web(dk ? "#94A3B8" : "#64748B"));
        Button btnClose = new Button();
        btnClose.setGraphic(closeIcon);
        btnClose.setStyle(
            "-fx-background-color: " + (dk ? "rgba(255,255,255,0.08)" : "rgba(100,116,139,0.10)") + "; "
            + "-fx-background-radius: 8; -fx-border-radius: 8; "
            + "-fx-cursor: hand; "
            + "-fx-min-width: 30; -fx-min-height: 30; "
            + "-fx-max-width: 30; -fx-max-height: 30;");
        btnClose.setOnAction(e -> { if (closeRef[0] != null) closeRef[0].run(); });

        // ── Header ──────────────────────────────────────────────
        // Referencias mutables para actualización dinámica
        final Label[] titleRef = { new Label("Viaje a Cancún") };
        final Label[] subtitleRef = { new Label("Vacaciones soñadas") };
        final Label[] savedLabelRef = { new Label() };
        final Label[] targetLabelRef = { new Label() };
        final Label[] missingLabelRef = { new Label() };
        final Label[] percentLabelRef = { new Label() };
        final Label[] dateLabelRef = { new Label() };
        final Label[] fechaEstLabelRef = { new Label() };
        final Label[] aportesEstLabelRef = { new Label() };
        final Label[] ritmoLabelRef = { new Label() };
        final FontIcon[] headerIconRef = { new FontIcon("fas-piggy-bank") };
        final Region[] progressFillRef = { new Region() };
        final VBox[] historyContentRef = { new VBox() };
        
        Label title = titleRef[0];
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: 800; -fx-text-fill: " + titleColor + ";");

        Label subtitle = subtitleRef[0];
        subtitle.setStyle("-fx-font-size: 13px; -fx-text-fill: " + subtitleColor + ";");

        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        HBox titleRow = new HBox(12, title, titleSpacer, btnClose);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        VBox headerBox = new VBox(4, titleRow, subtitle);

        // ── Separador ────────────────────────────────────────────
        Region divider = new Region();
        divider.setPrefHeight(1);
        divider.setMaxHeight(1);
        divider.setStyle("-fx-background-color: " + dividerColor + ";");
        divider.setMaxWidth(Double.MAX_VALUE);

        // ── Resumen ───────────────────────────────────────────────
        NumberFormat nf = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-CO"));
        nf.setMaximumFractionDigits(0);

        savedLabelRef[0] = new Label(nf.format(5700000L / 100.0));
        savedLabelRef[0].setStyle("-fx-font-size: 20px; -fx-font-weight: 700; -fx-text-fill: " + savedColor + ";");
        Label savedTitle = new Label("Guardado");
        savedTitle.setStyle("-fx-font-size: 11px; -fx-text-fill: " + subtitleColor + ";");
        VBox savedBox = new VBox(2, savedLabelRef[0], savedTitle);

        targetLabelRef[0] = new Label(nf.format(15000000L / 100.0));
        targetLabelRef[0].setStyle("-fx-font-size: 20px; -fx-font-weight: 700; -fx-text-fill: " + titleColor + ";");
        Label targetTitle = new Label("Objetivo");
        targetTitle.setStyle("-fx-font-size: 11px; -fx-text-fill: " + subtitleColor + ";");
        VBox targetBox = new VBox(2, targetLabelRef[0], targetTitle);

        missingLabelRef[0] = new Label(nf.format(9300000L / 100.0));
        missingLabelRef[0].setStyle("-fx-font-size: 20px; -fx-font-weight: 700; -fx-text-fill: " + missingColor + ";");
        Label missingTitle = new Label("Faltan");
        missingTitle.setStyle("-fx-font-size: 11px; -fx-text-fill: " + subtitleColor + ";");
        VBox missingBox = new VBox(2, missingLabelRef[0], missingTitle);

        Region mspacer1 = new Region();
        Region mspacer2 = new Region();
        HBox.setHgrow(mspacer1, Priority.ALWAYS);
        HBox.setHgrow(mspacer2, Priority.ALWAYS);
        HBox metricsRow = new HBox(0, savedBox, mspacer1, targetBox, mspacer2, missingBox);
        metricsRow.setAlignment(Pos.CENTER_LEFT);

        // Barra de progreso — percent mutable para que refreshGoalDrawer lo actualice
        final int[] percentRef = { 0 };
        Region track = new Region();
        track.setPrefHeight(8);
        track.setMaxHeight(8);
        track.setStyle("-fx-background-color: " + barTrack + "; -fx-background-radius: 4;");

        progressFillRef[0] = new Region();
        progressFillRef[0].setPrefHeight(8);
        progressFillRef[0].setMaxHeight(8);
        progressFillRef[0].setStyle("-fx-background-color: #3B82F6; -fx-background-radius: 4;");
        // Iniciar oculto (0 guardado)
        progressFillRef[0].setPrefWidth(0);
        progressFillRef[0].setMaxWidth(0);
        
        StackPane progressBar = new StackPane(track, progressFillRef[0]);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        StackPane.setAlignment(progressFillRef[0], Pos.CENTER_LEFT);
        
        // Función reutilizable para actualizar ancho del fill según percentRef actual
        @SuppressWarnings("unchecked")
        java.util.function.Consumer<Double>[] updateFillWidthRef = new java.util.function.Consumer[1];
        updateFillWidthRef[0] = (containerWidth) -> {
            double fillWidth = containerWidth * percentRef[0] / 100.0;
            double finalWidth = Math.max(0, fillWidth);
            progressFillRef[0].setPrefWidth(finalWidth);
            progressFillRef[0].setMaxWidth(finalWidth);
        };
        
        progressBar.widthProperty().addListener((obs, oldWidth, newWidth) -> {
            if (newWidth.doubleValue() > 0) {
                updateFillWidthRef[0].accept(newWidth.doubleValue());
            }
        });

        percentLabelRef[0] = new Label("0%");
        percentLabelRef[0].setStyle("-fx-font-size: 14px; -fx-font-weight: 700; -fx-text-fill: #3B82F6;");
        HBox progressRow = new HBox(10, progressBar, percentLabelRef[0]);
        progressRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(progressBar, Priority.ALWAYS);

        // Fecha objetivo
        dateLabelRef[0] = new Label("Fecha objetivo: 15 Jul 2026");
        dateLabelRef[0].setStyle("-fx-font-size: 12px; -fx-text-fill: " + subtitleColor + ";");

        VBox summaryBox = new VBox(12, metricsRow, progressRow, dateLabelRef[0]);
        summaryBox.setPadding(new Insets(0, 0, 8, 0));

        // ── Proyección ───────────────────────────────────────────
        Label projectionTitle = new Label("Proyección");
        projectionTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: 700; -fx-text-fill: " + titleColor + ";");

        // Info box con proyección (fondo sutil)
        String projectionBg = dk ? "rgba(59,130,246,0.10)" : "rgba(59,130,246,0.06)";
        String projectionBorder = dk ? "rgba(59,130,246,0.30)" : "rgba(59,130,246,0.20)";

        fechaEstLabelRef[0] = new Label("📅 Fecha estimada: Agosto 2026");
        fechaEstLabelRef[0].setStyle("-fx-font-size: 13px; -fx-text-fill: " + titleColor + ";");

        aportesEstLabelRef[0] = new Label("🎯 Aportes restantes: ~5 aportes");
        aportesEstLabelRef[0].setStyle("-fx-font-size: 12px; -fx-text-fill: " + subtitleColor + ";");

        ritmoLabelRef[0] = new Label("⚡ Ritmo actual: $100.000/mes");
        ritmoLabelRef[0].setStyle("-fx-font-size: 11px; -fx-text-fill: " + subtitleColor + ";");

        VBox projectionContent = new VBox(6, fechaEstLabelRef[0], aportesEstLabelRef[0], ritmoLabelRef[0]);
        projectionContent.setPadding(new Insets(12, 14, 12, 14));
        projectionContent.setStyle(
            "-fx-background-color: " + projectionBg + "; "
            + "-fx-border-color: " + projectionBorder + "; -fx-border-width: 1; "
            + "-fx-border-radius: 10; -fx-background-radius: 10;"
        );

        VBox projectionBox = new VBox(10, projectionTitle, projectionContent);

        // ── Historial ───────────────────────────────────────────
        Label historyTitle = new Label("Movimientos");
        historyTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: 700; -fx-text-fill: " + titleColor + ";");

        // Movimientos - usar historyContentRef[0] para que pueda ser actualizado
        historyContentRef[0] = new VBox(8);
        VBox historyList = historyContentRef[0];

        // Movimientos iniciales (mock hasta que se carguen los reales)
        String[][] movements = {
            { "Cargando...", "Espere", "", "#94A3B8" }
        };

        for (String[] mov : movements) {
            Label amountLabel = new Label(mov[0]);
            amountLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: 600; -fx-text-fill: " + mov[3] + ";");

            Label typeLabel = new Label(mov[1]);
            typeLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: " + titleColor + ";");

            VBox movText = new VBox(1, typeLabel);
            if (!mov[2].isEmpty()) {
                Label dateMovLabel = new Label(mov[2]);
                dateMovLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: " + subtitleColor + ";");
                movText.getChildren().add(dateMovLabel);
            }

            Region movSpacer = new Region();
            HBox.setHgrow(movSpacer, Priority.ALWAYS);

            HBox movRow = new HBox(10, movText, movSpacer, amountLabel);
            movRow.setAlignment(Pos.CENTER_LEFT);
            movRow.setPadding(new Insets(8, 12, 8, 12));
            movRow.setStyle("-fx-background-color: " + (dk ? "rgba(255,255,255,0.03)" : "rgba(0,0,0,0.02)") + "; "
                + "-fx-background-radius: 8;");

            historyList.getChildren().add(movRow);
        }

        VBox historyBox = new VBox(10, historyTitle, historyList);

        // ── Panel inline depositar/retirar ────────────────────────
        String panelBg     = dk ? "#1E293B" : "#F8FAFC";
        String panelBorder = dk ? "rgba(255,255,255,0.10)" : "#E2E8F0";
        String inputBg     = dk ? "rgba(255,255,255,0.05)" : "white";
        String inputBorder = dk ? "rgba(255,255,255,0.12)" : "#CBD5E1";
        String errorRed    = "#EF4444";

        NumberFormat nfInline = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-CO"));
        nfInline.setMaximumFractionDigits(0);

        // ── Título del panel ─────────────────────────────────────
        FontIcon inlineTitleIcon = new FontIcon("fas-arrow-right");
        inlineTitleIcon.setIconSize(12);
        inlineTitleIcon.setIconColor(javafx.scene.paint.Color.web("#2563EB"));
        Label inlineTitle = new Label("Depositar  →  Meta");
        inlineTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: 800; -fx-text-fill: #2563EB;");

        Region inlineDivider = new Region();
        inlineDivider.setPrefHeight(1); inlineDivider.setMaxHeight(1);
        inlineDivider.setMaxWidth(Double.MAX_VALUE);
        inlineDivider.setStyle("-fx-background-color: " + panelBorder + ";");

        // ── ComboBox de cuentas con celdas ricas ─────────────────
        javafx.scene.control.ComboBox<AccountRepository.Account> accountCombo = new javafx.scene.control.ComboBox<>();
        accountCombo.setMaxWidth(Double.MAX_VALUE);
        accountCombo.setStyle(
            "-fx-background-color: " + inputBg + "; -fx-border-color: " + inputBorder + "; "
            + "-fx-border-radius: 8; -fx-background-radius: 8; -fx-pref-height: 40;"
        );

        // Factoría de celda rica: avatar + nombre + tipo + saldo
        javafx.util.Callback<javafx.scene.control.ListView<AccountRepository.Account>,
            javafx.scene.control.ListCell<AccountRepository.Account>> richCellFactory = lv ->
            new javafx.scene.control.ListCell<>() {
                @Override protected void updateItem(AccountRepository.Account a, boolean empty) {
                    super.updateItem(a, empty);
                    if (empty || a == null) { setGraphic(null); setText(null); return; }
                    try {
                        long bal = accountRepo.computeBalanceCents(userUid, a.id());
                        String typeKey = AccountRepository.normalizeType(a.type());
                        StackPane avatar = buildAccountAvatar(typeKey, a.color());

                        Label nameLabel = new Label(a.name());
                        nameLabel.setStyle("-fx-font-weight: 700; -fx-font-size: 13px; -fx-text-fill: " + titleColor + ";");
                        Label typeLabel = new Label(accountTypeLabelShort(a.type()));
                        typeLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + subtitleColor + ";");
                        VBox nameTypeBox = new VBox(1, nameLabel, typeLabel);
                        nameTypeBox.setAlignment(Pos.CENTER_LEFT);

                        Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);

                        Label balLabel = new Label(nfInline.format(bal / 100.0));
                        balLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #10B981; -fx-font-weight: 600;");

                        HBox row = new HBox(10, avatar, nameTypeBox, sp, balLabel);
                        row.setAlignment(Pos.CENTER_LEFT);
                        row.setPadding(new Insets(2, 12, 2, 0));
                        setGraphic(row);
                        setText(null);
                    } catch (Exception ex) {
                        setText(a.name()); setGraphic(null);
                    }
                }
            };
        accountCombo.setCellFactory(richCellFactory);

        // Celda del botón (muestra cuenta seleccionada)
        accountCombo.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(AccountRepository.Account a, boolean empty) {
                super.updateItem(a, empty);
                if (empty || a == null) {
                    setText("Seleccionar cuenta"); setGraphic(null); return;
                }
                try {
                    String typeKey = AccountRepository.normalizeType(a.type());
                    StackPane avatar = buildAccountAvatar(typeKey, a.color());

                    Label nameLabel = new Label(a.name());
                    nameLabel.setStyle("-fx-font-weight: 700; -fx-font-size: 13px; -fx-text-fill: " + titleColor + ";");
                    Label typeLabel = new Label(accountTypeLabelShort(a.type()));
                    typeLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + subtitleColor + ";");
                    VBox nameTypeBox = new VBox(1, nameLabel, typeLabel);
                    nameTypeBox.setAlignment(Pos.CENTER_LEFT);

                    Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);
                    HBox row = new HBox(10, avatar, nameTypeBox, sp);
                    row.setAlignment(Pos.CENTER_LEFT);
                    setGraphic(row); setText(null);
                } catch (Exception ex) {
                    setText(a.name()); setGraphic(null);
                }
            }
        });

        // ── Saldo disponible dinámico ─────────────────────────────
        Label inlineAccountBalance = new Label("");
        inlineAccountBalance.setStyle(
            "-fx-font-size: 11px; -fx-text-fill: " + subtitleColor + "; -fx-font-style: italic;"
        );
        inlineAccountBalance.setVisible(false);
        inlineAccountBalance.setManaged(false);

        accountCombo.getSelectionModel().selectedItemProperty().addListener((obs, oldAcc, newAcc) -> {
            if (newAcc == null) { inlineAccountBalance.setVisible(false); inlineAccountBalance.setManaged(false); return; }
            try {
                long bal = accountRepo.computeBalanceCents(userUid, newAcc.id());
                inlineAccountBalance.setText("Disponible en " + newAcc.name() + ": " + nfInline.format(bal / 100.0));
                inlineAccountBalance.setVisible(true); inlineAccountBalance.setManaged(true);
            } catch (Exception ex) {
                inlineAccountBalance.setVisible(false); inlineAccountBalance.setManaged(false);
            }
        });

        // ── Campo monto ───────────────────────────────────────────
        Label montoLabel = new Label("Monto");
        montoLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: 600; -fx-text-fill: " + subtitleColor + "; -fx-padding: 4 0 2 0;");

        TextField inlineAmountField = new TextField();
        inlineAmountField.setPromptText("0");
        inlineAmountField.setStyle(
            "-fx-background-color: " + inputBg + "; -fx-border-color: " + inputBorder + "; "
            + "-fx-border-radius: 8; -fx-background-radius: 8; "
            + "-fx-padding: 10 12; -fx-font-size: 15px; -fx-font-weight: 700; -fx-text-fill: " + titleColor + ";"
        );

        UiDialogs.restrictToDecimalAmount(inlineAmountField);

        // ── Error ─────────────────────────────────────────────────
        Label inlineError = new Label("");
        inlineError.setStyle("-fx-font-size: 11px; -fx-text-fill: " + errorRed + ";");
        inlineError.setVisible(false);
        inlineError.setManaged(false);

        // ── Botones ───────────────────────────────────────────────
        Button btnConfirm = new Button("Confirmar");
        btnConfirm.setStyle(
            "-fx-background-color: #2563EB; -fx-text-fill: white; -fx-font-weight: 700; "
            + "-fx-background-radius: 8; -fx-cursor: hand; -fx-pref-height: 40; -fx-font-size: 13px;"
        );
        btnConfirm.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(btnConfirm, Priority.ALWAYS);

        Button btnCancelInline = new Button("Cancelar");
        btnCancelInline.setStyle(
            "-fx-background-color: transparent; -fx-text-fill: " + subtitleColor + "; "
            + "-fx-font-weight: 600; -fx-border-color: " + inputBorder + "; -fx-border-width: 1; "
            + "-fx-background-radius: 8; -fx-border-radius: 8; -fx-cursor: hand; -fx-pref-height: 40; -fx-font-size: 13px;"
        );
        btnCancelInline.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(btnCancelInline, Priority.ALWAYS);

        HBox inlineBtns = new HBox(8, btnConfirm, btnCancelInline);
        inlineBtns.setAlignment(Pos.CENTER_LEFT);

        VBox inlinePanelContent = new VBox(10, accountCombo, inlineAccountBalance,
            montoLabel, inlineAmountField, inlineError, inlineBtns);
        inlinePanelContent.setPadding(new Insets(12, 0, 0, 0));

        VBox inlinePanel = new VBox(0);
        inlinePanel.getChildren().addAll(inlineTitle, inlineDivider, inlinePanelContent);
        inlinePanel.setPadding(new Insets(14, 16, 14, 16));
        inlinePanel.setStyle(
            "-fx-background-color: " + panelBg + "; "
            + "-fx-border-color: " + panelBorder + "; -fx-border-width: 1; "
            + "-fx-border-radius: 12; -fx-background-radius: 12; "
            + (dk ? "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.30), 8, 0, 0, 2);"
                  : "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.08), 8, 0, 0, 2);")
        );
        inlinePanel.setVisible(false);
        inlinePanel.setManaged(false);

        // Modo activo: true = depositar, false = retirar
        final boolean[] isDepositMode = { true };
        // Referencia al drawer para refresh desde la lambda
        final VBox[] drawerRef = { null };

        // Helper: cargar cuentas no-SAVINGS en el ComboBox
        Runnable loadAccountsIntoCombo = () -> {
            try {
                java.util.List<AccountRepository.Account> todas = accountRepo.list(userUid);
                java.util.List<AccountRepository.Account> cuentas = todas.stream()
                    .filter(a -> !"SAVINGS".equals(a.type()))
                    .toList();
                accountCombo.getItems().setAll(cuentas);
                if (!cuentas.isEmpty()) accountCombo.getSelectionModel().selectFirst();
            } catch (Exception ex) {
                System.out.println("[GoalDrawer] Error cargando cuentas: " + ex.getMessage());
            }
        };

        // Helper: construir MetaInfo fresca
        java.util.function.Supplier<GoalService.MetaInfo> buildFreshMeta = () -> {
            GoalService svc = goalServiceRef[0];
            GoalService.MetaInfo cur = selectedMetaRef[0];
            if (svc == null || cur == null) return cur;
            try {
                GoalRepository.Goal g = cur.goal();
                long saldo = svc.calcularSaldo(userUid, g.accountId());
                int pct = svc.calcularPorcentaje(saldo, g.targetCents());
                long faltan = Math.max(0, g.targetCents() - saldo);
                GoalService.EstadoMeta estado = svc.determinarEstado(pct);
                java.util.List<com.myfinaces.db.TransferRepository.TransferRow> movs =
                    svc.obtenerMovimientos(userUid, g.accountId());
                long promedio = svc.calcularPromedioMensual(movs, g.accountId());
                int dias = svc.diasSinMovimiento(movs, g.accountId());
                GoalService.AlertaMeta alerta = svc.generarAlerta(pct, dias, promedio);
                GoalService.ProyeccionMeta proy = (pct < 100 && promedio > 0)
                    ? svc.estimarCumplimiento(faltan, promedio) : null;
                String aportes = (pct < 100 && promedio > 0)
                    ? svc.estimarAportesRestantes(faltan, promedio) : null;
                String insight = svc.generarInsight(pct, faltan);
                if (alerta != null && alerta.tipo() == GoalService.TipoAlerta.ESTANCADA)
                    insight = alerta.tipo().icono + " " + alerta.mensaje();
                if (proy != null) {
                    insight = "💡 " + proy.mensajeMotivacional();
                    if (aportes != null) insight += " — " + aportes;
                }
                String fechaStr = "Sin fecha límite";
                if (g.targetDateEpochSec() > 0) {
                    java.time.LocalDate d = java.time.LocalDate.ofInstant(
                        java.time.Instant.ofEpochSecond(g.targetDateEpochSec()), java.time.ZoneId.systemDefault());
                    fechaStr = d.format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy",
                        java.util.Locale.forLanguageTag("es-CO")));
                }
                return new GoalService.MetaInfo(g, saldo, faltan, pct, estado, insight, fechaStr, proy, promedio, aportes);
            } catch (Exception ex) {
                System.out.println("[GoalDrawer] Error buildFreshMeta: " + ex.getMessage());
                return cur;
            }
        };

        // Acción confirmar
        btnConfirm.setOnAction(ev -> {
            AccountRepository.Account selectedAccount = accountCombo.getSelectionModel().getSelectedItem();
            if (selectedAccount == null) {
                inlineError.setText("Selecciona una cuenta");
                inlineError.setVisible(true); inlineError.setManaged(true); return;
            }
            String raw = inlineAmountField.getText().replaceAll("[^0-9]", "");
            if (raw.isBlank()) {
                inlineError.setText("Ingresa un monto");
                inlineError.setVisible(true); inlineError.setManaged(true); return;
            }
            long amountCents;
            try { amountCents = Long.parseLong(raw) * 100L; }
            catch (NumberFormatException ex) {
                inlineError.setText("Monto inválido");
                inlineError.setVisible(true); inlineError.setManaged(true); return;
            }
            if (amountCents <= 0) {
                inlineError.setText("El monto debe ser mayor a 0");
                inlineError.setVisible(true); inlineError.setManaged(true); return;
            }
            GoalService svc = goalServiceRef[0];
            GoalService.MetaInfo meta = selectedMetaRef[0];
            if (svc == null || meta == null) return;

            // Validación depósito: saldo suficiente en cuenta origen
            if (isDepositMode[0]) {
                try {
                    long balCuenta = accountRepo.computeBalanceCents(userUid, selectedAccount.id());
                    if (amountCents > balCuenta) {
                        inlineError.setText("Saldo insuficiente en " + selectedAccount.name()
                            + " (" + nfInline.format(balCuenta / 100.0) + ")");
                        inlineError.setVisible(true); inlineError.setManaged(true); return;
                    }
                } catch (Exception ex) {
                    inlineError.setText("No se pudo verificar el saldo");
                    inlineError.setVisible(true); inlineError.setManaged(true); return;
                }
            }
            // Validación retiro: saldo suficiente en meta
            if (!isDepositMode[0] && amountCents > meta.saved()) {
                inlineError.setText("Saldo insuficiente en la meta ("
                    + nfInline.format(meta.saved() / 100.0) + ")");
                inlineError.setVisible(true); inlineError.setManaged(true); return;
            }
            inlineError.setVisible(false); inlineError.setManaged(false);
            btnConfirm.setDisable(true);

            final long finalAmount = amountCents;
            final boolean esDeposito = isDepositMode[0];
            final String accountId = selectedAccount.id();

            new Thread(() -> {
                try {
                    if (esDeposito) {
                        svc.depositar(userUid, meta.goal().id(), accountId, finalAmount, null);
                    } else {
                        svc.retirar(userUid, meta.goal().id(), accountId, finalAmount, null);
                    }
                    javafx.application.Platform.runLater(() -> {
                        inlinePanel.setVisible(false); inlinePanel.setManaged(false);
                        inlineAmountField.clear();
                        btnConfirm.setDisable(false);
                        GoalService.MetaInfo fresh = buildFreshMeta.get();
                        selectedMetaRef[0] = fresh;
                        if (fresh != null && drawerRef[0] != null) {
                            refreshGoalDrawer(drawerRef[0], fresh, svc, userUid);
                        }
                        if (metasRefreshHolder[0] != null) metasRefreshHolder[0].run();
                        if (closeRef[0] != null) closeRef[0].run();
                    });
                } catch (Exception ex) {
                    javafx.application.Platform.runLater(() -> {
                        inlineError.setText(ex.getMessage() != null ? ex.getMessage() : "Error al procesar");
                        inlineError.setVisible(true); inlineError.setManaged(true);
                        btnConfirm.setDisable(false);
                    });
                }
            }).start();
        });

        btnCancelInline.setOnAction(ev -> {
            inlinePanel.setVisible(false); inlinePanel.setManaged(false);
            inlineAmountField.clear();
            inlineError.setVisible(false); inlineError.setManaged(false);
        });

        // ── Botones de acción ────────────────────────────────────
        Button btnDeposit = new Button("Depositar");
        btnDeposit.setStyle(
            "-fx-background-color: #2563EB; -fx-text-fill: white; -fx-font-weight: 700; "
            + "-fx-background-radius: 10; -fx-border-radius: 10; -fx-cursor: hand; "
            + "-fx-pref-height: 44; -fx-font-size: 13px; -fx-padding: 0 24;"
        );
        btnDeposit.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(btnDeposit, Priority.ALWAYS);

        Button btnWithdraw = new Button("Retirar");
        btnWithdraw.setStyle(
            "-fx-background-color: transparent; -fx-text-fill: " + (dk ? "#E5E7EB" : "#374151") + "; "
            + "-fx-font-weight: 700; -fx-background-radius: 10; -fx-border-radius: 10; "
            + "-fx-border-color: " + (dk ? "#334155" : "#CBD5E1") + "; -fx-border-width: 1; "
            + "-fx-cursor: hand; -fx-pref-height: 44; -fx-font-size: 13px; -fx-padding: 0 24;"
        );
        btnWithdraw.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(btnWithdraw, Priority.ALWAYS);

        btnDeposit.setOnAction(ev -> {
            isDepositMode[0] = true;
            inlineTitle.setText("Depositar  →  Meta");
            inlineTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: 700; -fx-text-fill: #2563EB;");
            btnConfirm.setStyle(
                "-fx-background-color: #2563EB; -fx-text-fill: white; -fx-font-weight: 700; "
                + "-fx-background-radius: 8; -fx-cursor: hand; -fx-pref-height: 36; -fx-font-size: 13px;"
            );
            inlineAmountField.clear();
            inlineError.setVisible(false); inlineError.setManaged(false);
            loadAccountsIntoCombo.run();
            inlinePanel.setVisible(true); inlinePanel.setManaged(true);
        });

        btnWithdraw.setOnAction(ev -> {
            isDepositMode[0] = false;
            inlineTitle.setText("Retirar  ←  Meta");
            inlineTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: 700; -fx-text-fill: #EF4444;");
            btnConfirm.setStyle(
                "-fx-background-color: #EF4444; -fx-text-fill: white; -fx-font-weight: 700; "
                + "-fx-background-radius: 8; -fx-cursor: hand; -fx-pref-height: 36; -fx-font-size: 13px;"
            );
            inlineAmountField.clear();
            inlineError.setVisible(false); inlineError.setManaged(false);
            loadAccountsIntoCombo.run();
            inlinePanel.setVisible(true); inlinePanel.setManaged(true);
        });

        HBox actionsRow = new HBox(12, btnDeposit, btnWithdraw);
        actionsRow.setAlignment(Pos.CENTER);

        // ── Scroll content ───────────────────────────────────────
        VBox scrollContent = new VBox(20, summaryBox, projectionBox, historyBox, actionsRow, inlinePanel);
        scrollContent.setPadding(new Insets(0, 24, 16, 24));

        ScrollPane scroll = new ScrollPane(scrollContent);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle(scrollBg);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        // ── Drawer VBox ──────────────────────────────────────────
        VBox drawer = new VBox(0);
        drawerRef[0] = drawer;
        drawer.setPrefWidth(width);
        drawer.setMaxWidth(width);
        drawer.setStyle(
            "-fx-background-color: " + drawerBg + "; "
            + "-fx-background-radius: 16 0 0 16; -fx-border-radius: 16 0 0 16; "
            + drawerShadow
        );

        // Header section
        VBox headerSection = new VBox(16, headerBox, divider);
        headerSection.setPadding(new Insets(24, 24, 16, 24));

        drawer.getChildren().addAll(headerSection, scroll);

        // Theme stylesheet
        java.net.URL themeUrl = BudgetView.class.getResource(dk ? "/styles/dark.css" : "/styles/light.css");
        if (themeUrl != null) drawer.getStylesheets().add(themeUrl.toExternalForm());

        // Guardar referencias mutables en UserData para actualización
        java.util.Map<String, Object> refs = new java.util.HashMap<>();
        refs.put("titleRef", titleRef);
        refs.put("subtitleRef", subtitleRef);
        refs.put("savedLabelRef", savedLabelRef);
        refs.put("targetLabelRef", targetLabelRef);
        refs.put("missingLabelRef", missingLabelRef);
        refs.put("percentLabelRef", percentLabelRef);
        refs.put("dateLabelRef", dateLabelRef);
        refs.put("fechaEstLabelRef", fechaEstLabelRef);
        refs.put("aportesEstLabelRef", aportesEstLabelRef);
        refs.put("ritmoLabelRef", ritmoLabelRef);
        refs.put("headerIconRef", headerIconRef);
        refs.put("progressFillRef", progressFillRef);
        refs.put("progressBarRef", new StackPane[]{ progressBar });
        refs.put("percentRef", percentRef);
        refs.put("updateFillWidthRef", updateFillWidthRef);
        refs.put("historyContentRef", historyContentRef);
        drawer.setUserData(refs);

        return drawer;
    }

    // ── Método auxiliar para refrescar drawer de meta ─────────────
    @SuppressWarnings("unchecked")
    private static void refreshGoalDrawer(VBox drawer, GoalService.MetaInfo meta,
                                             GoalService goalService, String userUid) {
        if (meta == null || drawer == null) return;
        
        try {
            NumberFormat nf = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-CO"));
            nf.setMaximumFractionDigits(0);
            
            // Obtener referencias mutables del UserData
            java.util.Map<String, Object> refs = (java.util.Map<String, Object>) drawer.getUserData();
            if (refs == null) {
                System.out.println("[GoalDrawer] Error: No hay referencias en UserData");
                return;
            }
            
            Label[] titleRef = (Label[]) refs.get("titleRef");
            Label[] subtitleRef = (Label[]) refs.get("subtitleRef");
            Label[] savedLabelRef = (Label[]) refs.get("savedLabelRef");
            Label[] targetLabelRef = (Label[]) refs.get("targetLabelRef");
            Label[] missingLabelRef = (Label[]) refs.get("missingLabelRef");
            Label[] percentLabelRef = (Label[]) refs.get("percentLabelRef");
            Label[] dateLabelRef = (Label[]) refs.get("dateLabelRef");
            Label[] fechaEstLabelRef = (Label[]) refs.get("fechaEstLabelRef");
            Label[] aportesEstLabelRef = (Label[]) refs.get("aportesEstLabelRef");
            Label[] ritmoLabelRef = (Label[]) refs.get("ritmoLabelRef");
            FontIcon[] headerIconRef = (FontIcon[]) refs.get("headerIconRef");
            VBox[] historyContentRef = (VBox[]) refs.get("historyContentRef");
            
            // Actualizar icono del header
            if (headerIconRef != null && headerIconRef[0] != null) {
                headerIconRef[0].setIconLiteral(meta.icon());
            }

            // Actualizar título y subtítulo
            if (titleRef != null && titleRef[0] != null) {
                titleRef[0].setText(meta.name());
            }
            if (subtitleRef != null && subtitleRef[0] != null) {
                subtitleRef[0].setText("Meta de ahorro");
            }
            
            // Actualizar métricas
            if (savedLabelRef != null && savedLabelRef[0] != null) {
                savedLabelRef[0].setText(nf.format(meta.saved() / 100.0));
            }
            if (targetLabelRef != null && targetLabelRef[0] != null) {
                targetLabelRef[0].setText(nf.format(meta.target() / 100.0));
            }
            if (missingLabelRef != null && missingLabelRef[0] != null) {
                missingLabelRef[0].setText(nf.format(meta.missing() / 100.0));
            }
            
            // Actualizar barra de progreso y porcentaje
            int percent = meta.percent();
            int[] percentRef = (int[]) refs.get("percentRef");
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<Double>[] updateFillWidthRef =
                (java.util.function.Consumer<Double>[]) refs.get("updateFillWidthRef");
            if (percentRef != null) percentRef[0] = percent;
            StackPane[] progressBarRef = (StackPane[]) refs.get("progressBarRef");
            if (updateFillWidthRef != null && updateFillWidthRef[0] != null
                    && progressBarRef != null && progressBarRef[0] != null) {
                StackPane bar = progressBarRef[0];
                if (bar.getWidth() > 0) {
                    updateFillWidthRef[0].accept(bar.getWidth());
                } else {
                    javafx.application.Platform.runLater(() -> {
                        if (bar.getWidth() > 0) updateFillWidthRef[0].accept(bar.getWidth());
                    });
                }
            }
            if (percentLabelRef != null && percentLabelRef[0] != null) {
                percentLabelRef[0].setText(percent + "%");
            }
            
            // Actualizar fecha objetivo
            if (dateLabelRef != null && dateLabelRef[0] != null) {
                dateLabelRef[0].setText("Fecha objetivo: " + meta.date());
            }
            
            // Actualizar proyección - estilo card naranja
            long faltan = meta.missing();
            long promedioMensual = meta.promedioAporte();
            
            // Calcular días estimados para completar al ritmo actual
            int diasEstimados = 0;
            double ahorroDiarioActual = 0;
            if (promedioMensual > 0) {
                ahorroDiarioActual = promedioMensual / 30.0;
                diasEstimados = (int) Math.ceil(faltan / ahorroDiarioActual);
            }
            
            // Calcular días hasta fecha objetivo
            int diasHastaObjetivo = 180; // Default 6 meses
            try {
                java.time.LocalDate hoy = java.time.LocalDate.now();
                java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter
                    .ofPattern("dd MMM yyyy", java.util.Locale.forLanguageTag("es-CO"));
                java.time.LocalDate fechaObj = java.time.LocalDate.parse(meta.date(), formatter);
                diasHastaObjetivo = (int) java.time.temporal.ChronoUnit.DAYS.between(hoy, fechaObj);
                if (diasHastaObjetivo <= 0) diasHastaObjetivo = 30; // Mínimo 1 mes
            } catch (Exception e) {
                diasHastaObjetivo = 180; // Fallback 6 meses
            }
            
            // Calcular sugerencias
            double ahorroDiarioSugerido = diasHastaObjetivo > 0 ? faltan / (double) diasHastaObjetivo : 0;
            long ahorroMensualSugerido = (long) (ahorroDiarioSugerido * 30);
            int mesesSugeridos = (int) Math.ceil(diasHastaObjetivo / 30.0);
            
            // Mensaje según progreso
            int porcentaje = meta.percent();
            String mensajeProgreso;
            if (porcentaje >= 50) {
                mensajeProgreso = "🎯 Vas a mitad de camino";
            } else if (porcentaje >= 25) {
                mensajeProgreso = "📈 Buen progreso, sigue así";
            } else {
                mensajeProgreso = "🚀 Comienza tu ahorro";
            }
            
            // Actualizar labels
            if (fechaEstLabelRef != null && fechaEstLabelRef[0] != null) {
                fechaEstLabelRef[0].setText(mensajeProgreso);
            }
            if (aportesEstLabelRef != null && aportesEstLabelRef[0] != null) {
                if (ahorroDiarioSugerido > 0) {
                    String textoDias = String.format("💰 Si ahorras $%,.0f/día lo logras en %d día(s)", 
                        ahorroDiarioSugerido / 100.0, diasHastaObjetivo);
                    aportesEstLabelRef[0].setText(textoDias);
                } else {
                    aportesEstLabelRef[0].setText("💰 Establece tu ritmo de ahorro");
                }
            }
            if (ritmoLabelRef != null && ritmoLabelRef[0] != null) {
                if (ahorroMensualSugerido > 0) {
                    String textoSugerido = String.format("📅 Sugerido mensual: $%,.0f (en %d mes(es))", 
                        ahorroMensualSugerido / 100.0, mesesSugeridos);
                    ritmoLabelRef[0].setText(textoSugerido);
                } else {
                    ritmoLabelRef[0].setText("📅 Define tu fecha objetivo");
                }
            }
            
            // Actualizar historial de movimientos
            System.out.println("[GoalDrawer] Actualizando movimientos para meta: " + meta.name() + 
                ", goalService: " + (goalService != null ? "OK" : "NULL") +
                ", historyContentRef: " + (historyContentRef != null && historyContentRef[0] != null ? "OK" : "NULL"));
            
            if (historyContentRef != null && historyContentRef[0] != null && goalService != null) {
                VBox historyList = historyContentRef[0];
                historyList.getChildren().clear();
                System.out.println("[GoalDrawer] History list limpiado, cargando movimientos...");
                
                try {
                    java.util.List<com.myfinaces.db.TransferRepository.TransferRow> movimientos = 
                        goalService.obtenerMovimientos(userUid, meta.goal().accountId());
                    
                    System.out.println("[GoalDrawer] Movimientos obtenidos: " + movimientos.size());
                    
                    // Ordenar por fecha descendente (más recientes primero)
                    movimientos.sort((a, b) -> Long.compare(b.occurredAtEpochSec(), a.occurredAtEpochSec()));
                    
                    // Mostrar solo los últimos 3
                    java.util.List<com.myfinaces.db.TransferRepository.TransferRow> ultimosMovimientos = 
                        movimientos.stream().limit(3).toList();
                    
                    java.time.format.DateTimeFormatter dateFormatter = 
                        java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy", java.util.Locale.forLanguageTag("es-CO"));
                    
                    for (com.myfinaces.db.TransferRepository.TransferRow mov : ultimosMovimientos) {
                        boolean esDeposito = meta.goal().accountId().equals(mov.toAccountId());
                        String montoStr = (esDeposito ? "+" : "-") + nf.format(mov.amountCents() / 100.0);
                        String tipoStr = esDeposito ? "Depósito" : "Retiro";
                        String colorStr = esDeposito ? "#10B981" : "#EF4444";
                        
                        java.time.LocalDate fecha = java.time.LocalDate.ofInstant(
                            java.time.Instant.ofEpochSecond(mov.occurredAtEpochSec()),
                            java.time.ZoneId.systemDefault());
                        String fechaStr = fecha.format(dateFormatter);
                        
                        Label amountLabel = new Label(montoStr);
                        amountLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: 600; -fx-text-fill: " + colorStr + ";");
                        
                        Label typeLabel = new Label(tipoStr);
                        typeLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: " + (drawer.getStyle().contains("#0F172A") ? "#E5E7EB" : "#0F172A") + ";");
                        
                        Label dateMovLabel = new Label(fechaStr);
                        dateMovLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: " + (drawer.getStyle().contains("#0F172A") ? "#94A3B8" : "#64748B") + ";");
                        
                        VBox movTexts = new VBox(2, typeLabel, dateMovLabel);
                        Region movSpacer = new Region();
                        HBox.setHgrow(movSpacer, Priority.ALWAYS);
                        HBox movRow = new HBox(10, amountLabel, movSpacer, movTexts);
                        movRow.setAlignment(Pos.CENTER_LEFT);
                        
                        historyList.getChildren().add(movRow);
                    }
                    
                    if (movimientos.isEmpty()) {
                        Label emptyLabel = new Label("No hay movimientos registrados");
                        emptyLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: " + (drawer.getStyle().contains("#0F172A") ? "#94A3B8" : "#64748B") + ";");
                        historyList.getChildren().add(emptyLabel);
                    } else if (movimientos.size() > 3) {
                        // Mostrar indicador de que hay más movimientos
                        Label moreLabel = new Label("... y " + (movimientos.size() - 3) + " movimientos más");
                        moreLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + (drawer.getStyle().contains("#0F172A") ? "#64748B" : "#94A3B8") + "; -fx-font-style: italic;");
                        historyList.getChildren().add(moreLabel);
                    }
                } catch (Exception e) {
                    System.out.println("[GoalDrawer] Error cargando movimientos: " + e.getMessage());
                    e.printStackTrace();
                }
            }
            
            System.out.println("[GoalDrawer] Drawer actualizado para: " + meta.name());
        } catch (Exception e) {
            System.out.println("[GoalDrawer] Error refrescando drawer: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
