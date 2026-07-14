package com.myfinaces.ui;

import com.myfinaces.auth.AuthSession;
import com.myfinaces.config.AccountStyles;
import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.LoanMovementRepository;
import com.myfinaces.db.LoanPaymentRepository;
import com.myfinaces.db.LoanRepository;
import com.myfinaces.db.TransactionRepository;
import com.myfinaces.service.LoanService;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Dialog;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.shape.Circle;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.geometry.Side;
import org.kordamp.ikonli.javafx.FontIcon;

import javafx.util.Duration;

import javafx.application.Platform;
import javafx.collections.FXCollections;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class LoansView {

    private static String resolveAccountColor(String typeKey, String storedColor) {
        return AccountStyles.resolveColor(typeKey, storedColor);
    }

    private static String resolveAccountTypeIcon(String typeKey) {
        return AccountStyles.resolveIcon(typeKey);
    }

    private static StackPane buildAccountAvatar(String typeKey, String storedColor) {
        String hex = resolveAccountColor(typeKey, storedColor);
        Circle bg = new Circle(15);
        try {
            Color base = Color.web(hex);
            bg.setFill(base.deriveColor(0, 1.0, 1.0, 0.18));
        } catch (Exception ignored) {
            bg.setFill(Color.web(AccountStyles.BANK.color(), 0.18));
        }
        FontIcon icon = new FontIcon(resolveAccountTypeIcon(typeKey));
        icon.setIconSize(12);
        try {
            icon.setIconColor(Color.web(hex));
        } catch (Exception ignored) {
            icon.setIconColor(Color.web(AccountStyles.BANK.color()));
        }
        StackPane avatar = new StackPane(bg, icon);
        avatar.setMinSize(30, 30);
        avatar.setPrefSize(30, 30);
        avatar.setMaxSize(30, 30);
        avatar.setAlignment(Pos.CENTER);
        return avatar;
    }

    private static javafx.util.Callback<javafx.scene.control.ListView<AccountRepository.Account>, ListCell<AccountRepository.Account>> accountCellFactory() {
        return cb -> new ListCell<>() {
            @Override
            protected void updateItem(AccountRepository.Account item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setGraphic(null); return; }
                String typeKey = AccountRepository.normalizeType(item.type());
                StackPane avatar = buildAccountAvatar(typeKey, item.color());
                Label nameLabel = new Label(item.name() == null ? "" : item.name());
                nameLabel.setStyle("-fx-font-weight: 700; -fx-font-size: 13px;");
                nameLabel.setMaxWidth(Double.MAX_VALUE);
                Label typeLabel = new Label(accountTypeLabel(item.type()));
                typeLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #94A3B8;");
                VBox text = new VBox(1, nameLabel, typeLabel);
                text.setAlignment(Pos.CENTER_LEFT);
                HBox row = new HBox(8, avatar, text);
                row.setAlignment(Pos.CENTER_LEFT);
                setText(null);
                setGraphic(row);
            }
        };
    }

    private static String accountTypeLabel(String type) {
        return AccountStyles.resolveLabel(type);
    }

    private LoansView() {
    }

    public static Node buildLoansView(
        AuthSession session,
        LoanRepository loanRepo,
        LoanPaymentRepository loanPaymentRepo,
        LoanMovementRepository loanMovementRepo,
        AccountRepository accountRepo,
        CategoryRepository categoryRepo,
        TransactionRepository txRepo,
        Supplier<Boolean> darkTheme,
        Runnable refreshBalances
    ) {
        // ── Header (título + subtítulo) ──────────────────────────
        Label title = new Label("Préstamos");
        title.getStyleClass().add("app-title");

        Label subtitle = new Label("Gestiona préstamos otorgados y recibidos");
        subtitle.getStyleClass().add("text-secondary");

        VBox titleBox = new VBox(2, title, subtitle);
        titleBox.setAlignment(Pos.CENTER_LEFT);

        // ── Pills de navegación ─────────────────────────────────
        Button pillLent     = createPill("Me deben",  "fas-hand-holding-usd");
        Button pillBorrowed = createPill("Yo debo",   "fas-file-invoice-dollar");
        Button pillActivity = createPill("Actividad", "fas-history");
        Button[] allPills = { pillLent, pillBorrowed, pillActivity };

        // Estado inicial: primera pill activa (se aplica después de resolver dk)

        boolean dk = Boolean.TRUE.equals(darkTheme.get());

        for (Button pill : allPills) {
            pill.setStyle(pillInactive(dk));
            pill.setOnMouseEntered(e -> {
                if (!pill.getStyleClass().contains("pill-active")) {
                    pill.setStyle(pillHover(dk));
                }
            });
            pill.setOnMouseExited(e -> {
                if (!pill.getStyleClass().contains("pill-active")) {
                    pill.setStyle(pillInactive(dk));
                }
            });
        }
        applyPillActive(pillLent, dk);

        HBox pillsBar = new HBox(6, pillLent, pillBorrowed, pillActivity);
        pillsBar.setAlignment(Pos.CENTER_LEFT);
        pillsBar.setPadding(new Insets(4));
        pillsBar.setStyle(
            "-fx-background-color: " + (dk ? "rgba(255,255,255,0.06)" : "#F1F5F9") + "; "
            + "-fx-background-radius: 14; "
            + "-fx-border-radius: 14;" + (dk ? " -fx-border-color: rgba(255,255,255,0.10); -fx-border-width: 1;" : "")
        );

        // ── Botón "+ Nuevo préstamo" ─────────────────────────────
        FontIcon iconNew = new FontIcon("fas-plus");
        iconNew.setIconSize(13);
        iconNew.getStyleClass().add("icon");
        iconNew.setIconColor(javafx.scene.paint.Color.WHITE);
        Button btnNew = new Button("Nuevo préstamo");
        btnNew.setGraphic(iconNew);
        btnNew.setGraphicTextGap(6);
        btnNew.getStyleClass().add("btn-primary");
        btnNew.setStyle("-fx-pref-height: 38; -fx-min-height: 38; -fx-max-height: 38;");

        // ── Top bar (título + botón) ─────────────────────────────
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox topBar = new HBox(16, titleBox, spacer, btnNew);
        topBar.setAlignment(Pos.CENTER_LEFT);

        // ── Contenedor principal dinámico ──────────────────────────
        VBox contentContainer = new VBox(14);
        contentContainer.setFillWidth(true);
        VBox.setVgrow(contentContainer, Priority.ALWAYS);

        // Referencia para abrir modal de pago con loan ID
        @SuppressWarnings("unchecked")
        Consumer<String>[] openPaymentRef = (Consumer<String>[]) new Consumer<?>[]{ null };

        // Referencia para abrir modal de agregar dinero (topup)
        @SuppressWarnings("unchecked")
        Consumer<String>[] openTopupRef = (Consumer<String>[]) new Consumer<?>[]{ null };

        // Referencia para confirmar archivado de préstamo
        @SuppressWarnings("unchecked")
        Consumer<String>[] openArchiveRef = (Consumer<String>[]) new Consumer<?>[]{ null };

        // Referencia para el StackPane root (para el drawer lateral)
        StackPane[] stackRootRef = new StackPane[]{ null };

        // ── LoanService (integración financiera real) ─────────────
        LoanService loanService = new LoanService(
            loanRepo, loanPaymentRepo, loanMovementRepo, accountRepo, txRepo, categoryRepo
        );
        String userUid = session.uid();

        // ── Sistema de overlays reutilizable ──────────────────────
        ModalOverlay modalOverlay = new ModalOverlay();

        // ── Hero summary card (métricas reales) ─────────────────────
        Node[] heroCard = { buildHeroSummaryCard(loanService, userUid, dk) };

        // Panel de actividad (se regenera dinámicamente)
        Node[] activityPanel = { buildActivityTimeline(loanService, userUid, dk) };

        // Empty states premium
        Runnable[] openNewLoanRef = { null };
        Node emptyLoansState    = buildEmptyLoans(() -> { if (openNewLoanRef[0] != null) openNewLoanRef[0].run(); }, dk);
        Node emptyPaymentsState = buildEmptyPayments(dk);

        // Colores para avatares
        String[] avatarColors = { "#3B82F6", "#8B5CF6", "#059669", "#EA580C", "#D946EF", "#0891B2", "#DC2626" };

        // ── Función para cargar cards desde DB ────────────────────
        Runnable[] refreshContent = { null };
        String[] activeTab = { "lent" };

        // ── Callback compuesto: refrescar hero + cards + balances globales ─────────
        // Definición inicial, se actualizará después cuando root esté disponible
        Runnable[] refreshAllRef = { () -> {
            refreshContent[0].run();
            try { refreshBalances.run(); } catch (Exception ignored) {}
        }};

        refreshContent[0] = () -> {
            contentContainer.getChildren().clear();
            try {
                List<LoanRepository.Loan> loans;
                if ("lent".equals(activeTab[0])) {
                    loans = loanService.listLent(userUid);
                } else {
                    loans = loanService.listBorrowed(userUid);
                }

                if (loans.isEmpty()) {
                    contentContainer.getChildren().setAll(emptyLoansState);
                    return;
                }

                int colorIdx = 0;
                for (LoanRepository.Loan loan : loans) {
                    long paidCents = loanService.getPaidCents(userUid, loan.id());
                    long pendingCents = Math.max(0L, loan.principalCents() - paidCents);
                    int pct = loan.principalCents() > 0
                        ? (int) (paidCents * 100 / loan.principalCents()) : 0;

                    String initials = buildInitials(loan.counterpartyName());
                    String color = avatarColors[colorIdx % avatarColors.length];
                    colorIdx++;

                    String dateStr = formatLoanDate(loan.occurredAtEpochSec());
                    List<LoanPaymentRepository.LoanPayment> payments = loanService.listPayments(userUid, loan.id());
                    int paymentCount = payments.size();
                    String lastMov = payments.isEmpty() ? "Sin pagos" : formatRelativeTime(payments.getLast().occurredAtEpochSec());

                    String loanId = loan.id();
                    Node card = buildLoanCard(
                        loanId,
                        loan.counterpartyName(), initials, dateStr,
                        paymentCount, lastMov,
                        loan.principalCents(), paidCents, pendingCents,
                        pct, color, id -> { if (openPaymentRef[0] != null) openPaymentRef[0].accept(id); },
                        activeLoanId -> {
                            if (stackRootRef[0] != null) {
                                showLoanDetailDrawer(stackRootRef[0], loanService, userUid, activeLoanId, dk, session, modalOverlay, refreshAllRef[0]);
                            }
                        },
                        activeLoanId -> { if (openTopupRef[0] != null) openTopupRef[0].accept(activeLoanId); },
                        activeLoanId -> { if (openArchiveRef[0] != null) openArchiveRef[0].accept(activeLoanId); },
                        dk, modalOverlay, loanService, userUid, session, refreshAllRef[0]
                    );
                    contentContainer.getChildren().add(card);
                }
            } catch (Exception ex) {
                Label errLbl = new Label("Error al cargar préstamos");
                errLbl.setStyle("-fx-text-fill: #DC2626; -fx-font-size: 12px;");
                contentContainer.getChildren().setAll(errLbl);
            }
        };

        // Cargar datos iniciales
        refreshContent[0].run();

        // ── Wiring pills → contenido ─────────────────────────────
        pillLent.setOnAction(e -> {
            for (Button p : allPills) applyPillInactive(p, dk);
            applyPillActive(pillLent, dk);
            activeTab[0] = "lent";
            refreshContent[0].run();
        });
        pillBorrowed.setOnAction(e -> {
            for (Button p : allPills) applyPillInactive(p, dk);
            applyPillActive(pillBorrowed, dk);
            activeTab[0] = "borrowed";
            refreshContent[0].run();
        });
        pillActivity.setOnAction(e -> {
            for (Button p : allPills) applyPillInactive(p, dk);
            applyPillActive(pillActivity, dk);
            activeTab[0] = "activity";
            activityPanel[0] = buildActivityTimeline(loanService, userUid, dk);
            Node ap = activityPanel[0];
            VBox apContainer = new VBox(0, ap);
            boolean hasActivity = !((VBox) ap).getChildren().isEmpty();
            if (hasActivity) {
                contentContainer.getChildren().setAll(apContainer);
            } else {
                contentContainer.getChildren().setAll(emptyPaymentsState);
            }
        });

        // ── Scroll sobre el contenido ─────────────────────────────
        ScrollPane scroll = new ScrollPane(contentContainer);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        scroll.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        // ── Root (VBox principal del módulo) ──────────────────────
        VBox root = new VBox(16, topBar, heroCard[0], pillsBar, scroll);
        root.getStyleClass().add("content");
        root.setPadding(new Insets(24));
        root.setFillWidth(true);
        VBox.setVgrow(root, Priority.ALWAYS);

        // Actualizar refreshAll para que refresque el hero card correctamente
        refreshAllRef[0] = () -> {
            // Refrescar hero card con métricas reales
            Node newHero = buildHeroSummaryCard(loanService, userUid, dk);
            int heroIdx = root.getChildren().indexOf(heroCard[0]);
            if (heroIdx >= 0) {
                root.getChildren().set(heroIdx, newHero);
                heroCard[0] = newHero;
            }
            refreshContent[0].run();
            try { refreshBalances.run(); } catch (Exception ignored) {}
        };

        // Registrar modals
        buildNewLoanModal(modalOverlay, loanService, session, refreshAllRef[0]);
        Consumer<String> showPaymentModal = buildPaymentModal(modalOverlay, loanService, session, refreshAllRef[0]);
        Consumer<String> showTopupModal = buildTopupModal(modalOverlay, loanService, session, refreshAllRef[0]);
        Consumer<String> showArchiveModal = buildArchiveModal(modalOverlay, loanService, session, refreshAllRef[0]);

        // Wiring
        btnNew.setOnAction(e -> modalOverlay.show("new-loan"));
        openPaymentRef[0] = showPaymentModal;
        openTopupRef[0] = showTopupModal;
        openArchiveRef[0] = showArchiveModal;
        openNewLoanRef[0] = () -> modalOverlay.show("new-loan");

        // ── StackPane raíz con overlay (blur + backdrop + ESC) ──
        StackPane stackRoot = modalOverlay.wrapContent(root);
        stackRootRef[0] = stackRoot;

        return stackRoot;
    }

    // ── Pill styles ─────────────────────────────────────────────────────
    private static String pillActive(boolean dk) {
        return "-fx-background-color: #2563EB; -fx-text-fill: white; "
            + "-fx-background-radius: 10; -fx-border-radius: 10; "
            + "-fx-font-size: 13px; -fx-font-weight: 700; -fx-cursor: hand; "
            + "-fx-padding: 7 20 7 20;";
    }
    private static String pillInactive(boolean dk) {
        return "-fx-background-color: transparent; -fx-text-fill: " + (dk ? "rgba(229,231,235,0.60)" : "#64748B") + "; "
            + "-fx-background-radius: 10; -fx-border-radius: 10; "
            + "-fx-font-size: 13px; -fx-font-weight: 600; -fx-cursor: hand; "
            + "-fx-padding: 7 20 7 20;";
    }
    private static String pillHover(boolean dk) {
        return "-fx-background-color: " + (dk ? "rgba(255,255,255,0.10)" : "#E2E8F0") + "; "
            + "-fx-text-fill: " + (dk ? "rgba(229,231,235,0.92)" : "#334155") + "; "
            + "-fx-background-radius: 10; -fx-border-radius: 10; "
            + "-fx-font-size: 13px; -fx-font-weight: 600; -fx-cursor: hand; "
            + "-fx-padding: 7 20 7 20;";
    }

    private static Button createPill(String text, String iconLiteral) {
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(13);
        icon.setIconColor(Color.web("#64748B"));
        Button pill = new Button(text);
        pill.setGraphic(icon);
        pill.setGraphicTextGap(7);
        return pill;
    }

    private static void applyPillActive(Button pill, boolean dk) {
        pill.setStyle(pillActive(dk));
        pill.getStyleClass().add("pill-active");
        if (pill.getGraphic() instanceof FontIcon fi) {
            fi.setIconColor(Color.WHITE);
        }
        ScaleTransition pulse = new ScaleTransition(Duration.millis(150), pill);
        pulse.setFromX(0.95); pulse.setFromY(0.95);
        pulse.setToX(1.0);   pulse.setToY(1.0);
        pulse.setInterpolator(Interpolator.EASE_OUT);
        pulse.play();
    }

    private static void applyPillInactive(Button pill, boolean dk) {
        pill.setStyle(pillInactive(dk));
        pill.getStyleClass().remove("pill-active");
        if (pill.getGraphic() instanceof FontIcon fi) {
            fi.setIconColor(Color.web(dk ? "rgba(229,231,235,0.60)" : "#64748B"));
        }
        pill.setScaleX(1.0);
        pill.setScaleY(1.0);
    }

    // ── Card de préstamo individual ─────────────────────────────────────
    private static Node buildLoanCard(
        String loanId,
        String name, String initials, String date,
        int paymentCount, String lastMovement,
        long totalCents, long paidCents, long pendingCents,
        int pct, String avatarColor,
        Consumer<String> onRegisterPayment,
        Consumer<String> onViewDetails,
        Consumer<String> onAddMoney,
        Consumer<String> onArchive,
        boolean dk,
        ModalOverlay modalOverlay,
        LoanService loanService,
        String userUid,
        AuthSession session,
        Runnable refreshAll
    ) {
        // ── IZQUIERDA: avatar + info persona ────────────────────
        Label avatarLabel = new Label(initials);
        avatarLabel.setStyle(
            "-fx-background-color: " + avatarColor + "; "
            + "-fx-background-radius: 50; "
            + "-fx-text-fill: white; -fx-font-weight: 800; -fx-font-size: 15px; "
            + "-fx-min-width: 46; -fx-min-height: 46; "
            + "-fx-max-width: 46; -fx-max-height: 46; "
            + "-fx-alignment: center;"
        );

        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: 800;" + (dk ? " -fx-text-fill: #E5E7EB;" : ""));

        FontIcon calIcon = new FontIcon("fas-calendar-alt");
        calIcon.setIconSize(11);
        calIcon.setIconColor(Color.web("#94A3B8"));
        Label dateLabel = new Label(date);
        dateLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + (dk ? "rgba(229,231,235,0.55)" : "#94A3B8") + "; -fx-font-weight: 600;");
        HBox dateRow = new HBox(5, calIcon, dateLabel);
        dateRow.setAlignment(Pos.CENTER_LEFT);

        FontIcon payIcon = new FontIcon("fas-receipt");
        payIcon.setIconSize(11);
        payIcon.setIconColor(Color.web("#94A3B8"));
        Label payCountLabel = new Label(paymentCount + (paymentCount == 1 ? " pago" : " pagos"));
        payCountLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + (dk ? "rgba(229,231,235,0.55)" : "#94A3B8") + "; -fx-font-weight: 600;");
        HBox payRow = new HBox(5, payIcon, payCountLabel);
        payRow.setAlignment(Pos.CENTER_LEFT);

        FontIcon clockIcon = new FontIcon("fas-clock");
        clockIcon.setIconSize(11);
        clockIcon.setIconColor(Color.web("#94A3B8"));
        Label lastLabel = new Label(lastMovement);
        lastLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + (dk ? "rgba(229,231,235,0.55)" : "#94A3B8") + "; -fx-font-weight: 600;");
        HBox lastRow = new HBox(5, clockIcon, lastLabel);
        lastRow.setAlignment(Pos.CENTER_LEFT);

        VBox personInfo = new VBox(3, nameLabel, dateRow, payRow, lastRow);
        personInfo.setAlignment(Pos.CENTER_LEFT);

        HBox leftSection = new HBox(14, avatarLabel, personInfo);
        leftSection.setAlignment(Pos.CENTER_LEFT);
        leftSection.setMinWidth(210);

        // ── CENTRO: métricas + barra de progreso ────────────────
        Node metricTotal   = buildCardMetric("Total",     DashboardFormatters.formatMoney(totalCents),   null, dk);
        Node metricPaid    = buildCardMetric("Pagado",    DashboardFormatters.formatMoney(paidCents),    "#16A34A", dk);
        Node metricPending = buildCardMetric("Pendiente", DashboardFormatters.formatMoney(pendingCents), "#EA580C", dk);

        HBox metricsRow = new HBox(24, metricTotal, metricPaid, metricPending);
        metricsRow.setAlignment(Pos.CENTER_LEFT);

        // Barra de progreso
        String barColor = pct < 40 ? "#EA580C" : pct < 70 ? "#F59E0B" : "#16A34A";
        Region trackBg = new Region();
        trackBg.setStyle("-fx-background-color: " + (dk ? "rgba(255,255,255,0.10)" : "#F1F5F9") + "; -fx-background-radius: 6;");
        trackBg.setPrefHeight(6);
        trackBg.setMaxHeight(6);

        Region trackFill = new Region();
        trackFill.setStyle("-fx-background-color: " + barColor + "; -fx-background-radius: 6;");
        trackFill.setPrefHeight(6);
        trackFill.setMaxHeight(6);
        trackFill.setMaxWidth(Double.MAX_VALUE);

        StackPane progressBar = new StackPane(trackBg, trackFill);
        progressBar.setAlignment(Pos.CENTER_LEFT);
        progressBar.setPrefHeight(6);
        progressBar.setMaxHeight(6);
        trackBg.prefWidthProperty().bind(progressBar.widthProperty());
        trackFill.prefWidthProperty().bind(progressBar.widthProperty().multiply(pct / 100.0));

        Label pctLabel = new Label(pct + "% recuperado");
        pctLabel.setStyle("-fx-font-size: 10px; -fx-font-weight: 700; -fx-text-fill: " + barColor + ";");

        HBox progressInfo = new HBox(8, progressBar, pctLabel);
        progressInfo.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(progressBar, Priority.ALWAYS);

        VBox centerSection = new VBox(10, metricsRow, progressInfo);
        centerSection.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(centerSection, Priority.ALWAYS);

        // ── DERECHA: acciones ───────────────────────────────────
        FontIcon payBtnIcon = new FontIcon("fas-plus-circle");
        payBtnIcon.setIconSize(13);
        payBtnIcon.setIconColor(Color.WHITE);
        Button btnPay = new Button("Registrar pago");
        btnPay.setGraphic(payBtnIcon);
        btnPay.setGraphicTextGap(6);
        String payBase =
            "-fx-background-color: #2563EB; -fx-text-fill: white; "
            + "-fx-background-radius: 10; -fx-border-radius: 10; "
            + "-fx-font-size: 12px; -fx-font-weight: 700; -fx-cursor: hand; "
            + "-fx-padding: 8 18 8 18;";
        String payHover =
            "-fx-background-color: #1D4ED8; -fx-text-fill: white; "
            + "-fx-background-radius: 10; -fx-border-radius: 10; "
            + "-fx-font-size: 12px; -fx-font-weight: 700; -fx-cursor: hand; "
            + "-fx-padding: 8 18 8 18;";
        btnPay.setStyle(payBase);
        btnPay.setOnMouseEntered(ev -> btnPay.setStyle(payHover));
        btnPay.setOnMouseExited(ev -> btnPay.setStyle(payBase));
        btnPay.setOnAction(ev -> {
            ev.consume();
            onRegisterPayment.accept(loanId);
        });

        Button btnDetail = buildCompactAction("fas-edit", dk ? "#94A3B8" : "#64748B", dk);
        btnDetail.setOnAction(ev -> {
            ev.consume();
            // Abrir modal de edición directamente
            try {
                LoanRepository.Loan loan = loanService.getLoan(userUid, loanId);
                if (loan != null) {
                    buildEditLoanModal(modalOverlay, loanService, session, userUid, loanId, loan.accountId(), loan.principalCents(), refreshAll);
                    modalOverlay.show("edit-loan");
                }
            } catch (Exception ex) {
                // Silencioso en caso de error
            }
        });

        // Botón Agregar dinero (reemplaza edit)
        Button btnAddMoney = new Button("Agregar");
        btnAddMoney.setGraphic(new FontIcon("fas-plus"));
        btnAddMoney.setGraphicTextGap(6);
        String addBase =
            "-fx-background-color: " + (dk ? "rgba(245,158,11,0.15)" : "#FEF3C7") + "; "
            + "-fx-text-fill: #D97706; "
            + "-fx-background-radius: 8; -fx-border-radius: 8; "
            + "-fx-font-size: 11px; -fx-font-weight: 700; -fx-cursor: hand; "
            + "-fx-padding: 6 12 6 12; "
            + "-fx-border-color: " + (dk ? "rgba(245,158,11,0.35)" : "#FCD34D") + "; "
            + "-fx-border-width: 1;";
        String addHover =
            "-fx-background-color: " + (dk ? "rgba(245,158,11,0.25)" : "#FDE68A") + "; "
            + "-fx-text-fill: #B45309; "
            + "-fx-background-radius: 8; -fx-border-radius: 8; "
            + "-fx-font-size: 11px; -fx-font-weight: 700; -fx-cursor: hand; "
            + "-fx-padding: 6 12 6 12; "
            + "-fx-border-color: " + (dk ? "rgba(245,158,11,0.50)" : "#F59E0B") + "; "
            + "-fx-border-width: 1;";
        btnAddMoney.setStyle(addBase);
        btnAddMoney.setOnMouseEntered(ev -> btnAddMoney.setStyle(addHover));
        btnAddMoney.setOnMouseExited(ev -> btnAddMoney.setStyle(addBase));
        btnAddMoney.setOnAction(ev -> {
            ev.consume();
            onAddMoney.accept(loanId);
        });

        Button btnDelete = buildCompactAction("fas-trash-alt", "#EF4444", dk);
        btnDelete.setOnAction(ev -> {
            ev.consume();
            onArchive.accept(loanId);
        });

        HBox secondaryActions = new HBox(6, btnDetail, btnAddMoney, btnDelete);
        secondaryActions.setAlignment(Pos.CENTER);

        VBox rightSection = new VBox(8, btnPay, secondaryActions);
        rightSection.setAlignment(Pos.CENTER);
        rightSection.setMinWidth(150);

        // ── Separadores verticales ──────────────────────────────
        Region sepL = cardSeparator(dk);
        Region sepR = cardSeparator(dk);

        // ── Card row ────────────────────────────────────────────
        HBox cardRow = new HBox(20, leftSection, sepL, centerSection, sepR, rightSection);
        cardRow.setAlignment(Pos.CENTER_LEFT);
        cardRow.setPadding(new Insets(20, 24, 20, 24));

        // ── Card wrapper con hover ──────────────────────────────
        String cardBase = dk
            ? "-fx-background-color: rgba(15,23,42,0.92); "
              + "-fx-background-radius: 14; -fx-border-radius: 14; "
              + "-fx-border-color: rgba(229,231,235,0.10); -fx-border-width: 1; "
              + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 14, 0.18, 0, 4);"
            : "-fx-background-color: white; "
              + "-fx-background-radius: 14; -fx-border-radius: 14; "
              + "-fx-border-color: #E2E8F0; -fx-border-width: 1; "
              + "-fx-effect: dropshadow(gaussian, rgba(15,23,42,0.05), 10, 0, 0, 2);";
        String cardHover = dk
            ? "-fx-background-color: rgba(59,130,246,0.06); "
              + "-fx-background-radius: 14; -fx-border-radius: 14; "
              + "-fx-border-color: rgba(59,130,246,0.35); -fx-border-width: 1; "
              + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.45), 20, 0.22, 0, 6);"
            : "-fx-background-color: #FAFBFF; "
              + "-fx-background-radius: 14; -fx-border-radius: 14; "
              + "-fx-border-color: #CBD5E1; -fx-border-width: 1; "
              + "-fx-effect: dropshadow(gaussian, rgba(15,23,42,0.10), 16, 0, 0, 4)";

        // ── Status badge ──────────────────────────────────────────
        String statusText;
        String statusBg;
        String statusFg;
        String statusIcon;
        if (pct >= 100) {
            statusText = "Liquidado";
            statusBg = dk ? "rgba(22,163,106,0.16)" : "#F0FDF4";
            statusFg = dk ? "#34D399" : "#16A34A";
            statusIcon = "fas-check-circle";
        } else if (pct >= 40) {
            statusText = "Al día";
            statusBg = dk ? "rgba(59,130,246,0.16)" : "#EFF6FF";
            statusFg = dk ? "#60A5FA" : "#2563EB";
            statusIcon = "fas-thumbs-up";
        } else {
            statusText = "Pendiente";
            statusBg = dk ? "rgba(234,88,12,0.16)" : "#FFF7ED";
            statusFg = dk ? "#FB923C" : "#EA580C";
            statusIcon = "fas-exclamation-circle";
        }

        FontIcon badgeIcon = new FontIcon(statusIcon);
        badgeIcon.setIconSize(10);
        badgeIcon.setIconColor(Color.web(statusFg));
        Label badge = new Label(statusText);
        badge.setGraphic(badgeIcon);
        badge.setGraphicTextGap(4);
        badge.setStyle(
            "-fx-background-color: " + statusBg + "; "
            + "-fx-text-fill: " + statusFg + "; "
            + "-fx-background-radius: 8; -fx-border-radius: 8; "
            + "-fx-border-color: " + statusFg + "22; -fx-border-width: 1; "
            + "-fx-font-size: 10px; -fx-font-weight: 700; "
            + "-fx-padding: 3 10 3 10;"
        );
        nameLabel.setGraphic(null);

        HBox nameRow = new HBox(8, nameLabel, badge);
        nameRow.setAlignment(Pos.CENTER_LEFT);

        // Update personInfo to use nameRow instead of nameLabel
        personInfo.getChildren().set(0, nameRow);

        VBox card = new VBox(cardRow);
        card.setStyle(cardBase);

        // ── Micro hover: elevation + smooth translateY ───────────
        TranslateTransition hoverUp = new TranslateTransition(Duration.millis(180), card);
        hoverUp.setToY(-3);
        hoverUp.setInterpolator(Interpolator.EASE_BOTH);

        TranslateTransition hoverDown = new TranslateTransition(Duration.millis(180), card);
        hoverDown.setToY(0);
        hoverDown.setInterpolator(Interpolator.EASE_BOTH);

        card.setOnMouseEntered(ev -> {
            card.setStyle(cardHover);
            hoverDown.stop();
            hoverUp.playFromStart();
        });
        card.setOnMouseExited(ev -> {
            card.setStyle(cardBase);
            hoverUp.stop();
            hoverDown.playFromStart();
        });

        // ── Click en el card abre el drawer de detalle ───────────────
        card.setOnMouseClicked(ev -> {
            if (ev.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                onViewDetails.accept(loanId);
            }
        });
        card.setCursor(javafx.scene.Cursor.HAND);

        // ── Animated progress bar fill on appearance ─────────────
        // Use an animated fraction multiplier instead of touching the bound prefWidth.
        // We wrap trackFill in a clip-like approach: animate scaleX from left pivot.
        trackFill.setScaleX(0);
        boolean[] barAnimPlayed = { false };
        // Pivot scaleX from left edge once layout is done
        trackFill.layoutBoundsProperty().addListener((obs2, ob, nb) -> {
            trackFill.setTranslateX(-nb.getWidth() / 2.0 * (1.0 - trackFill.getScaleX()));
        });
        trackFill.scaleXProperty().addListener((obs2, ov, nv) -> {
            double w = trackFill.getLayoutBounds().getWidth();
            trackFill.setTranslateX(-w / 2.0 * (1.0 - nv.doubleValue()));
        });

        card.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null && !barAnimPlayed[0]) {
                barAnimPlayed[0] = true;
                Timeline barAnim = new Timeline(new KeyFrame(
                    Duration.millis(700),
                    new KeyValue(trackFill.scaleXProperty(), 1.0, Interpolator.EASE_OUT)
                ));
                barAnim.setDelay(Duration.millis(300));
                barAnim.play();
            }
        });

        return card;
    }

    // ── Mini métrica dentro de la card ─────────────────────────────────
    private static Node buildCardMetric(String label, String value, String valueColor, boolean dk) {
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-size: 10px; -fx-font-weight: 700; -fx-text-fill: " + (dk ? "rgba(229,231,235,0.55)" : "#94A3B8") + ";");

        Label val = new Label(value);
        String valColor = valueColor != null ? valueColor : (dk ? "#E5E7EB" : null);
        val.setStyle(
            "-fx-font-size: 14px; -fx-font-weight: 900;"
            + (valColor != null ? " -fx-text-fill: " + valColor + ";" : "")
        );

        VBox block = new VBox(2, lbl, val);
        block.setAlignment(Pos.CENTER_LEFT);
        return block;
    }

    // ── Botón compacto de acción secundaria ───────────────────────────
    private static Button buildCompactAction(String iconLiteral, String iconColor, boolean dk) {
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(13);
        icon.setIconColor(Color.web(iconColor));

        Button btn = new Button();
        btn.setGraphic(icon);
        String base = dk
            ? "-fx-background-color: rgba(255,255,255,0.06); "
              + "-fx-background-radius: 8; -fx-border-radius: 8; "
              + "-fx-border-color: rgba(255,255,255,0.10); -fx-border-width: 1; "
              + "-fx-min-width: 34; -fx-min-height: 34; "
              + "-fx-max-width: 34; -fx-max-height: 34; "
              + "-fx-cursor: hand; -fx-padding: 0;"
            : "-fx-background-color: #F8FAFC; "
              + "-fx-background-radius: 8; -fx-border-radius: 8; "
              + "-fx-border-color: #E2E8F0; -fx-border-width: 1; "
              + "-fx-min-width: 34; -fx-min-height: 34; "
              + "-fx-max-width: 34; -fx-max-height: 34; "
              + "-fx-cursor: hand; -fx-padding: 0;";
        String hover = dk
            ? "-fx-background-color: rgba(59,130,246,0.18); "
              + "-fx-background-radius: 8; -fx-border-radius: 8; "
              + "-fx-border-color: rgba(59,130,246,0.35); -fx-border-width: 1; "
              + "-fx-min-width: 34; -fx-min-height: 34; "
              + "-fx-max-width: 34; -fx-max-height: 34; "
              + "-fx-cursor: hand; -fx-padding: 0;"
            : "-fx-background-color: #EEF2FF; "
              + "-fx-background-radius: 8; -fx-border-radius: 8; "
              + "-fx-border-color: #CBD5E1; -fx-border-width: 1; "
              + "-fx-min-width: 34; -fx-min-height: 34; "
              + "-fx-max-width: 34; -fx-max-height: 34; "
              + "-fx-cursor: hand; -fx-padding: 0;";
        btn.setStyle(base);
        btn.setOnMouseEntered(ev -> btn.setStyle(hover));
        btn.setOnMouseExited(ev -> btn.setStyle(base));
        return btn;
    }

    // ── Separador vertical para cards ─────────────────────────────────
    private static Region cardSeparator(boolean dk) {
        Region sep = new Region();
        sep.setStyle(
            "-fx-background-color: " + (dk ? "rgba(255,255,255,0.10)" : "#F1F5F9") + "; "
            + "-fx-pref-width: 1; -fx-min-width: 1; -fx-max-width: 1;"
        );
        sep.setMinHeight(60);
        return sep;
    }

    // ── Modal: Nuevo préstamo ───────────────────────────────────────────
    private static void buildNewLoanModal(
        ModalOverlay overlay, LoanService loanService,
        AuthSession session, Runnable refreshBalances
    ) {
        String userUid = session.uid();

        HBox header = overlay.buildHeader("Nuevo préstamo",
            "Registra un préstamo otorgado o recibido");

        // ── Segmented control: Prestar / Pedir prestado ──────────
        Button segLend = new Button("Prestar");
        Button segBorrow = new Button("Pedir prestado");
        String[] selectedType = { LoanRepository.TYPE_LENT };

        FontIcon segLendIcon = new FontIcon("fas-arrow-up");
        segLendIcon.setIconSize(12);
        segLendIcon.getStyleClass().add("loan-seg-icon");
        segLend.setGraphic(segLendIcon);
        segLend.setGraphicTextGap(6);

        FontIcon segBorrowIcon = new FontIcon("fas-arrow-down");
        segBorrowIcon.setIconSize(12);
        segBorrowIcon.getStyleClass().add("loan-seg-icon");
        segBorrow.setGraphic(segBorrowIcon);
        segBorrow.setGraphicTextGap(6);

        segLend.getStyleClass().addAll("loan-seg-btn", "loan-seg-active");
        segBorrow.getStyleClass().add("loan-seg-btn");

        segLend.setOnAction(e -> {
            selectedType[0] = LoanRepository.TYPE_LENT;
            if (!segLend.getStyleClass().contains("loan-seg-active"))
                segLend.getStyleClass().add("loan-seg-active");
            segBorrow.getStyleClass().remove("loan-seg-active");
        });
        segBorrow.setOnAction(e -> {
            selectedType[0] = LoanRepository.TYPE_BORROWED;
            if (!segBorrow.getStyleClass().contains("loan-seg-active"))
                segBorrow.getStyleClass().add("loan-seg-active");
            segLend.getStyleClass().remove("loan-seg-active");
        });

        segLend.setMaxWidth(Double.MAX_VALUE);
        segBorrow.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(segLend, Priority.ALWAYS);
        HBox.setHgrow(segBorrow, Priority.ALWAYS);

        HBox segmentRow = new HBox(0, segLend, segBorrow);
        segmentRow.setAlignment(Pos.CENTER);
        segmentRow.getStyleClass().add("loan-segment-row");
        segLend.getStyleClass().add("loan-seg-btn");
        segBorrow.getStyleClass().add("loan-seg-btn");

        // ── Selector de cuenta (real) ────────────────────────────
        Label accountLabel = ModalOverlay.fieldLabel("Cuenta", "fas-wallet");
        ComboBox<AccountRepository.Account> accountCombo = new ComboBox<>();
        accountCombo.setPromptText("Seleccionar cuenta");
        accountCombo.setMaxWidth(Double.MAX_VALUE);
        accountCombo.getStyleClass().add("account-combo");
        accountCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AccountRepository.Account a) {
                return a == null ? "" : a.name() + " · " + a.currency();
            }
            @Override
            public AccountRepository.Account fromString(String s) { return null; }
        });
        accountCombo.setCellFactory(accountCellFactory());
        accountCombo.setButtonCell(accountCellFactory().call(null));

        // Cargar cuentas reales
        try {
            List<AccountRepository.Account> accounts = loanService.listAccounts(userUid);
            accountCombo.setItems(FXCollections.observableArrayList(accounts));
            if (!accounts.isEmpty()) accountCombo.getSelectionModel().selectFirst();
        } catch (Exception ignored) {}

        Label balanceLabel = new Label();
        balanceLabel.setStyle(
            "-fx-font-size: 11px; -fx-font-weight: 700; -fx-text-fill: #16A34A;"
        );

        // Actualizar saldo al cambiar cuenta
        Runnable refreshAccountBalance = () -> {
            AccountRepository.Account acc = accountCombo.getValue();
            if (acc == null) { balanceLabel.setText(""); return; }
            try {
                long cents = loanService.getAccountBalance(userUid, acc.id());
                balanceLabel.setText("Saldo disponible: " + DashboardFormatters.formatMoney(cents, acc.currency()));
            } catch (Exception ex) { balanceLabel.setText(""); }
        };
        accountCombo.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> refreshAccountBalance.run());
        refreshAccountBalance.run();

        VBox accountBlock = new VBox(6, accountLabel, accountCombo, balanceLabel);

        // ── Persona ──────────────────────────────────────────────
        Label personLabel = ModalOverlay.fieldLabel("Persona", "fas-user");
        TextField personField = new TextField();
        personField.setPromptText("Nombre de la persona");
        personField.getStyleClass().add("modal-text-input");

        VBox personBlock = new VBox(6, personLabel, personField);

        // ── Monto ────────────────────────────────────────────────
        Label amountLabel = ModalOverlay.fieldLabel("Monto", "fas-dollar-sign");
        TextField amountField = new TextField();
        amountField.setPromptText("$0");
        amountField.getStyleClass().add("modal-amount-input");

        UiDialogs.restrictToDecimalAmount(amountField);

        VBox amountBlock = new VBox(6, amountLabel, amountField);

        // ── Nota ─────────────────────────────────────────────────
        Label noteLabel = ModalOverlay.fieldLabel("Nota (opcional)", "fas-sticky-note");
        TextField noteField = new TextField();
        noteField.setPromptText("Agrega una descripción...");
        noteField.getStyleClass().add("modal-text-input");

        VBox noteBlock = new VBox(6, noteLabel, noteField);

        // ── Error label ──────────────────────────────────────────
        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: 700; -fx-text-fill: #DC2626;");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        // ── Footer ───────────────────────────────────────────────
        VBox footer = overlay.buildFooter("Registrar préstamo", "fas-check",
            "#2563EB", "#1D4ED8");

        // Wire submit button
        Button submitBtn = (Button) footer.lookup(".btn-primary-modal");
        if (submitBtn == null && !footer.getChildren().isEmpty()) {
            // fallback: first button in footer
            for (Node n : footer.getChildren()) {
                if (n instanceof Button b) { submitBtn = b; break; }
                if (n instanceof HBox hb) {
                    for (Node c : hb.getChildren()) {
                        if (c instanceof Button b) { submitBtn = b; break; }
                    }
                }
            }
        }
        Button finalSubmitBtn = submitBtn;
        if (finalSubmitBtn != null) {
            finalSubmitBtn.setOnAction(ev -> {
                errorLabel.setVisible(false);
                errorLabel.setManaged(false);

                AccountRepository.Account acc = accountCombo.getValue();
                String person = personField.getText() == null ? "" : personField.getText().trim();
                String amtRaw = amountField.getText();

                // Validations
                if (acc == null) {
                    errorLabel.setText("Selecciona una cuenta");
                    errorLabel.setVisible(true); errorLabel.setManaged(true); return;
                }
                if (person.isBlank()) {
                    errorLabel.setText("Ingresa el nombre de la persona");
                    errorLabel.setVisible(true); errorLabel.setManaged(true); return;
                }

                long cents;
                try {
                    BigDecimal v = DashboardFormatters.parseAmount(amtRaw);
                    cents = v.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
                } catch (Exception ex) {
                    errorLabel.setText("Ingresa un monto válido");
                    errorLabel.setVisible(true); errorLabel.setManaged(true); return;
                }
                if (cents <= 0) {
                    errorLabel.setText("El monto debe ser mayor a $0");
                    errorLabel.setVisible(true); errorLabel.setManaged(true); return;
                }

                String notes = noteField.getText() == null ? null : noteField.getText().trim();
                if (notes != null && notes.isBlank()) notes = null;

                // Execute
                try {
                    loanService.createLoan(
                        userUid, session, selectedType[0],
                        person, acc.id(), cents, acc.currency(), notes
                    );

                    // Success — close modal and refresh
                    overlay.hide();
                    personField.clear();
                    amountField.clear();
                    noteField.clear();
                    Platform.runLater(() -> {
                        try { refreshBalances.run(); } catch (Exception ignored) {}
                    });
                } catch (Exception ex) {
                    errorLabel.setText(ex.getMessage() != null ? ex.getMessage() : "Error al registrar");
                    errorLabel.setVisible(true);
                    errorLabel.setManaged(true);
                }
            });
        }

        overlay.register("new-loan", 480,
            header, segmentRow, accountBlock, personBlock,
            amountBlock, noteBlock, errorLabel, footer);
    }

    // ── Modal: Editar préstamo ───────────────────────────────────────────
    private static void buildEditLoanModal(
        ModalOverlay overlay, LoanService loanService,
        AuthSession session, String userUid, String loanId,
        String currentAccountId, long currentPrincipalCents, Runnable refreshBalances
    ) {
        HBox header = overlay.buildHeader("Editar préstamo",
            "Ajusta el monto del préstamo existente");

        // ── Selector de cuenta ─────────────────────────────────────
        Label accountLabel = ModalOverlay.fieldLabel("Cuenta", "fas-wallet");
        ComboBox<AccountRepository.Account> accountCombo = new ComboBox<>();
        accountCombo.setPromptText("Seleccionar cuenta");
        accountCombo.setMaxWidth(Double.MAX_VALUE);
        accountCombo.getStyleClass().add("account-combo");
        accountCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AccountRepository.Account a) {
                return a == null ? "" : a.name() + " · " + a.currency();
            }
            @Override
            public AccountRepository.Account fromString(String s) { return null; }
        });
        accountCombo.setCellFactory(accountCellFactory());
        accountCombo.setButtonCell(accountCellFactory().call(null));

        // Cargar cuentas reales
        try {
            List<AccountRepository.Account> accounts = loanService.listAccounts(userUid);
            accountCombo.setItems(FXCollections.observableArrayList(accounts));
            // Seleccionar la cuenta actual del préstamo
            if (!accounts.isEmpty()) {
                accountCombo.getItems().stream()
                    .filter(a -> a.id().equals(currentAccountId))
                    .findFirst()
                    .ifPresentOrElse(
                        accountCombo.getSelectionModel()::select,
                        () -> accountCombo.getSelectionModel().selectFirst()
                    );
            }
        } catch (Exception ignored) {}

        Label balanceLabel = new Label();
        balanceLabel.setStyle(
            "-fx-font-size: 11px; -fx-font-weight: 700; -fx-text-fill: #16A34A;"
        );

        // Actualizar saldo al cambiar cuenta
        Runnable refreshAccountBalance = () -> {
            AccountRepository.Account acc = accountCombo.getValue();
            if (acc == null) { balanceLabel.setText(""); return; }
            try {
                long cents = loanService.getAccountBalance(userUid, acc.id());
                balanceLabel.setText("Saldo disponible: " + DashboardFormatters.formatMoney(cents, acc.currency()));
            } catch (Exception ex) { balanceLabel.setText(""); }
        };
        accountCombo.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> refreshAccountBalance.run());
        refreshAccountBalance.run();

        VBox accountBlock = new VBox(6, accountLabel, accountCombo, balanceLabel);

        // ── Monto actual ───────────────────────────────────────────
        Label currentLabel = ModalOverlay.fieldLabel("Monto actual", "fas-info-circle");
        Label currentAmount = new Label(DashboardFormatters.formatMoney(currentPrincipalCents));
        currentAmount.setStyle(
            "-fx-font-size: 14px; -fx-font-weight: 700; -fx-text-fill: #64748B;"
        );
        VBox currentBlock = new VBox(6, currentLabel, currentAmount);

        // ── Nuevo monto ─────────────────────────────────────────────
        Label amountLabel = ModalOverlay.fieldLabel("Nuevo monto", "fas-dollar-sign");
        TextField amountField = new TextField();
        amountField.setPromptText("Ej: 100000.00");
        amountField.getStyleClass().add("modal-text-input");
        // Pre-llenar con valor numérico sin símbolo de moneda
        amountField.setText(String.valueOf(currentPrincipalCents / 100.0));

        Label amountError = new Label();
        amountError.setStyle("-fx-text-fill: #DC2626; -fx-font-size: 11px;");
        amountError.setVisible(false);
        amountError.setManaged(false);

        VBox amountBlock = new VBox(6, amountLabel, amountField, amountError);

        // ── Nota ──────────────────────────────────────────────────
        Label noteLabel = ModalOverlay.fieldLabel("Nota (opcional)", "fas-sticky-note");
        TextArea noteField = new TextArea();
        noteField.setPromptText("Razón del ajuste...");
        noteField.getStyleClass().add("modal-text-input");
        noteField.setPrefRowCount(2);
        noteField.setWrapText(true);

        VBox noteBlock = new VBox(6, noteLabel, noteField);

        // ── Error general ──────────────────────────────────────────
        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: #DC2626; -fx-font-size: 12px;");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        // ── Footer ─────────────────────────────────────────────────
        VBox footer = overlay.buildFooter("Guardar cambios", "fas-save", "#2563EB", "#1D4ED8");
        Button primaryBtn = (Button) footer.getChildren().get(1);
        Button cancelBtn = (Button) footer.getChildren().get(2);

        primaryBtn.setOnAction(e -> {
            errorLabel.setText("");
            errorLabel.setVisible(false);
            errorLabel.setManaged(false);
            amountError.setText("");
            amountError.setVisible(false);
            amountError.setManaged(false);

            AccountRepository.Account selectedAccount = accountCombo.getValue();
            if (selectedAccount == null) {
                errorLabel.setText("Selecciona una cuenta");
                errorLabel.setVisible(true);
                errorLabel.setManaged(true);
                return;
            }

            String amountText = amountField.getText().trim();
            if (amountText.isBlank()) {
                amountError.setText("Ingresa un monto");
                amountError.setVisible(true);
                amountError.setManaged(true);
                return;
            }

            long newCents;
            try {
                // Intentar parsear como número decimal simple
                java.math.BigDecimal v = new java.math.BigDecimal(amountText);
                newCents = v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
            } catch (Exception ex) {
                amountError.setText("Monto inválido");
                amountError.setVisible(true);
                amountError.setManaged(true);
                return;
            }

            if (newCents <= 0) {
                amountError.setText("El monto debe ser mayor a 0");
                amountError.setVisible(true);
                amountError.setManaged(true);
                return;
            }

            String note = noteField.getText().trim();
            if (note.isBlank()) note = null;

            try {
                loanService.updateLoan(userUid, session, loanId, selectedAccount.id(), newCents, note);
                overlay.hide();
                refreshBalances.run();
            } catch (Exception ex) {
                errorLabel.setText(ex.getMessage());
                errorLabel.setVisible(true);
                errorLabel.setManaged(true);
            }
        });

        cancelBtn.setOnAction(e -> overlay.hide());

        overlay.register("edit-loan", 440,
            header, accountBlock, currentBlock, amountBlock, noteBlock, errorLabel, footer);
    }

    // ── Modal: Registrar pago ──────────────────────────────────────────
    private static Consumer<String> buildPaymentModal(
        ModalOverlay overlay, LoanService loanService,
        AuthSession session, Runnable refreshBalances
    ) {
        String userUid = session.uid();

        // Estado compartido: préstamo activo (se setea al abrir el modal)
        String[] activeLoanId = { null };
        long[] activePending = { 0L };

        HBox header = overlay.buildHeader("Registrar pago",
            "Registra un abono al préstamo");

        // ── Info persona (dinámica) ─────────────────────────────
        Label avatar = new Label("--");
        avatar.setStyle(
            "-fx-background-color: #3B82F6; -fx-background-radius: 50; "
            + "-fx-text-fill: white; -fx-font-weight: 800; -fx-font-size: 16px; "
            + "-fx-min-width: 50; -fx-min-height: 50; "
            + "-fx-max-width: 50; -fx-max-height: 50; "
            + "-fx-alignment: center;"
        );

        Label nameLabel = new Label("");
        nameLabel.getStyleClass().add("loan-person-name");

        Label loanInfo = new Label("");
        loanInfo.getStyleClass().add("loan-person-info");

        VBox personText = new VBox(3, nameLabel, loanInfo);
        personText.setAlignment(Pos.CENTER_LEFT);

        HBox personRow = new HBox(14, avatar, personText);
        personRow.setAlignment(Pos.CENTER_LEFT);
        personRow.setPadding(new Insets(12, 16, 12, 16));
        personRow.getStyleClass().add("loan-person-row");

        // ── Barra de progreso ────────────────────────────────────
        Region trackBg = new Region();
        trackBg.getStyleClass().add("loan-track-bg");
        trackBg.setPrefHeight(10);
        trackBg.setMaxHeight(10);

        Region trackFill = new Region();
        trackFill.setPrefHeight(10);
        trackFill.setMaxHeight(10);
        trackFill.setMaxWidth(Double.MAX_VALUE);

        StackPane progressBar = new StackPane(trackBg, trackFill);
        progressBar.setAlignment(Pos.CENTER_LEFT);
        progressBar.setPrefHeight(10);
        progressBar.setMaxHeight(10);
        trackBg.prefWidthProperty().bind(progressBar.widthProperty());

        Label pctLbl = new Label("");
        pctLbl.setMaxWidth(Double.MAX_VALUE);
        pctLbl.setAlignment(Pos.CENTER_RIGHT);

        VBox progressBlock = new VBox(6, progressBar, pctLbl);

        // ── Pendiente actual ─────────────────────────────────────
        Label pendingLabel = new Label("Pendiente actual");
        pendingLabel.getStyleClass().add("loan-muted-label");

        Label pendingValue = new Label("$0");
        pendingValue.setStyle(
            "-fx-font-size: 26px; -fx-font-weight: 900; -fx-text-fill: #EA580C;"
        );

        VBox pendingBlock = new VBox(2, pendingLabel, pendingValue);
        pendingBlock.setAlignment(Pos.CENTER);
        pendingBlock.setPadding(new Insets(10, 0, 6, 0));

        // ── Selector de cuenta (real) ────────────────────────────
        Label accountLabel = ModalOverlay.fieldLabel("Cuenta", "fas-wallet");
        ComboBox<AccountRepository.Account> payAccountCombo = new ComboBox<>();
        payAccountCombo.setPromptText("Seleccionar cuenta");
        payAccountCombo.setMaxWidth(Double.MAX_VALUE);
        payAccountCombo.getStyleClass().add("account-combo");
        payAccountCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AccountRepository.Account a) {
                if (a == null) return "";
                long bal = 0L;
                try {
                    bal = loanService.getAccountBalance(userUid, a.id());
                } catch (Exception ignored) {}
                return a.name() + " · " + a.currency() + " · " + DashboardFormatters.formatMoney(bal);
            }
            @Override
            public AccountRepository.Account fromString(String s) { return null; }
        });
        payAccountCombo.setCellFactory(accountCellFactory());
        payAccountCombo.setButtonCell(accountCellFactory().call(null));

        try {
            List<AccountRepository.Account> accounts = loanService.listAccounts(userUid);
            payAccountCombo.setItems(FXCollections.observableArrayList(accounts));
            if (!accounts.isEmpty()) payAccountCombo.getSelectionModel().selectFirst();
        } catch (Exception ignored) {}

        VBox accountBlock = new VBox(6, accountLabel, payAccountCombo);

        // ── Monto a pagar ────────────────────────────────────────
        Label amtLabel = ModalOverlay.fieldLabel("Monto a pagar", "fas-dollar-sign");
        TextField amtField = new TextField();
        amtField.setPromptText("$0");
        amtField.getStyleClass().add("modal-amount-input");

        UiDialogs.restrictToDecimalAmount(amtField);

        // ── Cálculo dinámico de restante ─────────────────────────
        Label remainLabel = new Label("Restante después del pago");
        remainLabel.getStyleClass().add("loan-muted-label");

        Label remainValue = new Label("$0");
        remainValue.getStyleClass().add("loan-remain-value");

        amtField.textProperty().addListener((obs, oldVal, newVal) -> {
            long payAmount = 0L;
            try {
                BigDecimal v = DashboardFormatters.parseAmount(newVal);
                payAmount = v.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
            } catch (Exception ignored) { }
            long remaining = Math.max(0, activePending[0] - payAmount);
            remainValue.setText(DashboardFormatters.formatMoney(remaining));
            if (remaining == 0 && payAmount > 0) {
                if (!remainValue.getStyleClass().contains("loan-remain-complete"))
                    remainValue.getStyleClass().add("loan-remain-complete");
            } else {
                remainValue.getStyleClass().remove("loan-remain-complete");
            }
        });

        HBox remainRow = new HBox(8, remainLabel, remainValue);
        remainRow.setAlignment(Pos.CENTER_LEFT);
        remainRow.setPadding(new Insets(8, 16, 8, 16));
        remainRow.getStyleClass().add("loan-remain-row");

        VBox amountBlock = new VBox(8, amtLabel, amtField, remainRow);

        // ── Error label ──────────────────────────────────────────
        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: 700; -fx-text-fill: #DC2626;");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        // ── Footer ───────────────────────────────────────────────
        VBox footer = overlay.buildFooter("Confirmar pago", "fas-check-circle",
            "#16A34A", "#15803D");

        // Wire submit
        Button submitBtn = null;
        for (Node n : footer.getChildren()) {
            if (n instanceof Button b) { submitBtn = b; break; }
            if (n instanceof HBox hb) {
                for (Node c : hb.getChildren()) {
                    if (c instanceof Button b) { submitBtn = b; break; }
                }
            }
        }
        if (submitBtn != null) {
            submitBtn.setOnAction(ev -> {
                errorLabel.setVisible(false);
                errorLabel.setManaged(false);

                if (activeLoanId[0] == null) {
                    errorLabel.setText("No se ha seleccionado un préstamo");
                    errorLabel.setVisible(true); errorLabel.setManaged(true); return;
                }

                AccountRepository.Account acc = payAccountCombo.getValue();
                if (acc == null) {
                    errorLabel.setText("Selecciona una cuenta");
                    errorLabel.setVisible(true); errorLabel.setManaged(true); return;
                }

                long cents;
                try {
                    BigDecimal v = DashboardFormatters.parseAmount(amtField.getText());
                    cents = v.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
                } catch (Exception ex) {
                    errorLabel.setText("Ingresa un monto válido");
                    errorLabel.setVisible(true); errorLabel.setManaged(true); return;
                }
                if (cents <= 0) {
                    errorLabel.setText("El monto debe ser mayor a $0");
                    errorLabel.setVisible(true); errorLabel.setManaged(true); return;
                }
                if (cents > activePending[0]) {
                    errorLabel.setText("El monto excede la deuda pendiente");
                    errorLabel.setVisible(true); errorLabel.setManaged(true); return;
                }

                try {
                    loanService.registerPayment(
                        userUid, session, activeLoanId[0], acc.id(), cents, 0L, null
                    );

                    overlay.hide();
                    amtField.clear();
                    Platform.runLater(() -> {
                        try { refreshBalances.run(); } catch (Exception ignored) {}
                    });
                } catch (Exception ex) {
                    errorLabel.setText(ex.getMessage() != null ? ex.getMessage() : "Error al registrar pago");
                    errorLabel.setVisible(true);
                    errorLabel.setManaged(true);
                }
            });
        }

        overlay.register("pay-loan", 440,
            header, personRow, progressBlock, pendingBlock,
            accountBlock, amountBlock, errorLabel, footer);

        // Retorna consumer que popula datos y muestra el modal
        return loanId -> {
            activeLoanId[0] = loanId;
            errorLabel.setVisible(false);
            errorLabel.setManaged(false);
            amtField.clear();

            // Refrescar cuentas para mostrar saldos actualizados
            try {
                List<AccountRepository.Account> freshAccounts = loanService.listAccounts(userUid);
                payAccountCombo.getItems().setAll(freshAccounts);
                if (!freshAccounts.isEmpty()) {
                    payAccountCombo.getSelectionModel().selectFirst();
                }
            } catch (Exception ex) {
                // Si falla el refresh, continuamos con las cuentas existentes
            }

            try {
                LoanRepository.Loan loan = loanService.getLoan(userUid, loanId);
                if (loan == null) return;

                long paidCents = loanService.getPaidCents(userUid, loanId);
                long pending = Math.max(0L, loan.principalCents() - paidCents);
                activePending[0] = pending;

                // Poblar persona
                String initials = buildInitials(loan.counterpartyName());
                avatar.setText(initials);
                nameLabel.setText(loan.counterpartyName());
                loanInfo.setText(
                    "Total: " + DashboardFormatters.formatMoney(loan.principalCents())
                    + "  ·  Pagado: " + DashboardFormatters.formatMoney(paidCents)
                );

                // Barra de progreso
                int pct = loan.principalCents() > 0
                    ? (int) (paidCents * 100 / loan.principalCents()) : 0;
                String barColor = pct < 40 ? "#EA580C" : pct < 70 ? "#F59E0B" : "#16A34A";
                trackFill.setStyle("-fx-background-color: " + barColor + "; -fx-background-radius: 6;");
                trackFill.prefWidthProperty().unbind();
                trackFill.prefWidthProperty().bind(progressBar.widthProperty().multiply(pct / 100.0));
                pctLbl.setText(pct + "% recuperado");
                pctLbl.setStyle("-fx-font-size: 11px; -fx-font-weight: 700; -fx-text-fill: " + barColor + ";");

                // Pendiente
                pendingValue.setText(DashboardFormatters.formatMoney(pending));
                remainValue.setText(DashboardFormatters.formatMoney(pending));
            } catch (Exception ex) {
                activePending[0] = 0L;
            }

            overlay.show("pay-loan");
        };
    }

    // ── Modal: Agregar dinero (TOPUP) ────────────────────────────────────
    private static Consumer<String> buildTopupModal(
        ModalOverlay overlay, LoanService loanService,
        AuthSession session, Runnable refreshBalances
    ) {
        String userUid = session.uid();

        // Estado compartido: préstamo activo (se setea al abrir el modal)
        String[] activeLoanId = { null };
        long[] currentPrincipal = { 0L };
        long[] currentPaid = { 0L };
        String[] loanType = { null };
        String[] counterparty = { null };
        String[] currency = { null };

        HBox header = overlay.buildHeader("Agregar dinero",
            "Aumenta el monto del préstamo existente");

        // ── Info persona compacta ─────────────────────────────
        Label avatar = new Label("--");
        avatar.setStyle(
            "-fx-background-color: #F59E0B; -fx-background-radius: 50; "
            + "-fx-text-fill: white; -fx-font-weight: 700; -fx-font-size: 14px; "
            + "-fx-min-width: 40; -fx-min-height: 40; "
            + "-fx-max-width: 40; -fx-max-height: 40; "
            + "-fx-alignment: center;"
        );

        Label nameLabel = new Label("");
        nameLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: 700;");

        Label loanInfo = new Label("");
        loanInfo.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748B;");

        VBox personText = new VBox(2, nameLabel, loanInfo);
        personText.setAlignment(Pos.CENTER_LEFT);

        HBox personRow = new HBox(10, avatar, personText);
        personRow.setAlignment(Pos.CENTER_LEFT);
        personRow.setPadding(new Insets(8, 12, 8, 12));
        personRow.setStyle(
            "-fx-background-color: rgba(245,158,11,0.08); "
            + "-fx-background-radius: 10; -fx-border-radius: 10;"
        );

        // ── Selector de cuenta origen ───────────────────────────
        Label accountLabel = ModalOverlay.fieldLabel("Cuenta origen", "fas-wallet");
        ComboBox<AccountRepository.Account> accountCombo = new ComboBox<>();
        accountCombo.setPromptText("Seleccionar cuenta");
        accountCombo.setMaxWidth(Double.MAX_VALUE);
        accountCombo.getStyleClass().add("account-combo");
        accountCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AccountRepository.Account a) {
                if (a == null) return "";
                long balance = 0L;
                try {
                    balance = loanService.getAccountBalance(userUid, a.id());
                } catch (Exception ignored) {}
                return a.name() + " · " + a.currency() + " · " + DashboardFormatters.formatMoney(balance);
            }
            @Override
            public AccountRepository.Account fromString(String s) { return null; }
        });
        accountCombo.setCellFactory(accountCellFactory());
        accountCombo.setButtonCell(accountCellFactory().call(null));

        try {
            List<AccountRepository.Account> accounts = loanService.listAccounts(userUid);
            accountCombo.setItems(FXCollections.observableArrayList(accounts));
            if (!accounts.isEmpty()) accountCombo.getSelectionModel().selectFirst();
        } catch (Exception ignored) {}

        VBox accountBlock = new VBox(4, accountLabel, accountCombo);
        accountBlock.setPadding(new Insets(4, 0, 0, 0));

        // ── Monto a agregar ────────────────────────────────────
        Label amtLabel = ModalOverlay.fieldLabel("Monto a agregar", "fas-plus");
        TextField amtField = new TextField();
        amtField.setPromptText("$0");
        amtField.getStyleClass().add("modal-amount-input");

        UiDialogs.restrictToDecimalAmount(amtField);

        // ── Resumen dinámico ───────────────────────────────────
        Label summaryLabel = new Label("Nuevo total del préstamo");
        summaryLabel.getStyleClass().add("loan-muted-label");

        Label summaryValue = new Label("$0");
        summaryValue.setStyle(
            "-fx-font-size: 22px; -fx-font-weight: 800; -fx-text-fill: #059669;"
        );

        Label pendingLabel = new Label("Pendiente total");
        pendingLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #94A3B8;");

        Label pendingValue = new Label("$0");
        pendingValue.setStyle(
            "-fx-font-size: 14px; -fx-font-weight: 700; -fx-text-fill: #64748B;"
        );

        VBox summaryBlock = new VBox(1, summaryLabel, summaryValue, pendingLabel, pendingValue);
        summaryBlock.setAlignment(Pos.CENTER);
        summaryBlock.setPadding(new Insets(8, 0, 6, 0));
        summaryBlock.setStyle(
            "-fx-background-color: rgba(5,150,105,0.08); "
            + "-fx-background-radius: 10; -fx-border-radius: 10;"
        );

        // Actualizar resumen dinámico al cambiar monto
        amtField.textProperty().addListener((obs, oldVal, newVal) -> {
            long addAmount = 0L;
            try {
                BigDecimal v = DashboardFormatters.parseAmount(newVal);
                addAmount = v.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
            } catch (Exception ignored) { }

            long newTotal = currentPrincipal[0] + addAmount;
            long newPending = newTotal - currentPaid[0];

            summaryValue.setText(DashboardFormatters.formatMoney(newTotal));
            pendingValue.setText("Pendiente: " + DashboardFormatters.formatMoney(newPending));
        });

        // ── Nota opcional ────────────────────────────
        Label noteLabel = ModalOverlay.fieldLabel("Nota (opcional)", "fas-sticky-note");
        TextArea noteArea = new TextArea();
        noteArea.setPromptText("Ej: Segunda entrega...");
        noteArea.setPrefRowCount(2);
        noteArea.setWrapText(true);
        noteArea.getStyleClass().add("modal-text-input");
        noteArea.setStyle("-fx-min-height: 50; -fx-max-height: 70; -fx-pref-height: 55;");

        VBox noteBlock = new VBox(3, noteLabel, noteArea);
        noteBlock.setPadding(new Insets(2, 0, 0, 0));

        // ── Error label ──────────────────────────────────────────
        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: 700; -fx-text-fill: #DC2626;");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        // ── Footer ───────────────────────────────────────────────
        VBox footer = overlay.buildFooter("Confirmar agregado", "fas-check-circle",
            "#059669", "#047857");

        // Wire submit
        Button submitBtn = null;
        for (Node n : footer.getChildren()) {
            if (n instanceof Button b) { submitBtn = b; break; }
            if (n instanceof HBox hb) {
                for (Node c : hb.getChildren()) {
                    if (c instanceof Button b) { submitBtn = b; break; }
                }
            }
        }
        if (submitBtn != null) {
            submitBtn.setOnAction(ev -> {
                errorLabel.setVisible(false);
                errorLabel.setManaged(false);

                if (activeLoanId[0] == null) {
                    errorLabel.setText("No se ha seleccionado un préstamo");
                    errorLabel.setVisible(true); errorLabel.setManaged(true); return;
                }

                AccountRepository.Account acc = accountCombo.getValue();
                if (acc == null) {
                    errorLabel.setText("Selecciona una cuenta origen");
                    errorLabel.setVisible(true); errorLabel.setManaged(true); return;
                }

                long addCents;
                try {
                    BigDecimal v = DashboardFormatters.parseAmount(amtField.getText());
                    addCents = v.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
                } catch (Exception ex) {
                    errorLabel.setText("Ingresa un monto válido");
                    errorLabel.setVisible(true); errorLabel.setManaged(true); return;
                }
                if (addCents <= 0) {
                    errorLabel.setText("El monto debe ser mayor a $0");
                    errorLabel.setVisible(true); errorLabel.setManaged(true); return;
                }

                try {
                    // Usar createLoan que detecta préstamo existente y hace TOPUP
                    loanService.createLoan(
                        userUid, session,
                        loanType[0],          // LENT o BORROWED
                        counterparty[0],      // nombre persona
                        acc.id(),             // cuenta origen
                        addCents,             // monto a agregar
                        currency[0],          // moneda
                        noteArea.getText()    // nota opcional
                    );

                    overlay.hide();
                    amtField.clear();
                    noteArea.clear();
                    Platform.runLater(() -> {
                        try { refreshBalances.run(); } catch (Exception ignored) {}
                    });
                } catch (Exception ex) {
                    errorLabel.setText(ex.getMessage() != null ? ex.getMessage() : "Error al agregar dinero");
                    errorLabel.setVisible(true);
                    errorLabel.setManaged(true);
                }
            });
        }

        overlay.register("topup-loan", 400,
            header, personRow,
            accountBlock, amtLabel, amtField, summaryBlock,
            noteBlock, errorLabel, footer);

        // Retorna consumer que popula datos y muestra el modal
        return loanId -> {
            activeLoanId[0] = loanId;
            amtField.clear();
            errorLabel.setVisible(false);
            errorLabel.setManaged(false);

            // Refrescar cuentas para mostrar saldos actualizados
            try {
                List<AccountRepository.Account> freshAccounts = loanService.listAccounts(userUid);
                accountCombo.getItems().setAll(freshAccounts);
                if (!freshAccounts.isEmpty()) {
                    accountCombo.getSelectionModel().selectFirst();
                }
            } catch (Exception ex) {
                // Si falla el refresh, continuamos con las cuentas existentes
            }

            try {
                LoanRepository.Loan loan = loanService.getLoan(userUid, loanId);
                if (loan == null) return;

                long paidCents = loanService.getPaidCents(userUid, loanId);
                long pending = Math.max(0L, loan.principalCents() - paidCents);

                // Guardar estado para uso en submit
                currentPrincipal[0] = loan.principalCents();
                currentPaid[0] = paidCents;
                loanType[0] = loan.type();
                counterparty[0] = loan.counterpartyName();
                currency[0] = loan.currency();

                // Poblar persona
                String initials = buildInitials(loan.counterpartyName());
                avatar.setText(initials);
                nameLabel.setText(loan.counterpartyName());
                loanInfo.setText(
                    "Pendiente actual: " + DashboardFormatters.formatMoney(pending)
                );

                // Valores actuales
                summaryValue.setText(DashboardFormatters.formatMoney(loan.principalCents()));
                pendingValue.setText("Pendiente: " + DashboardFormatters.formatMoney(pending));

            } catch (Exception ex) {
                currentPrincipal[0] = 0L;
                currentPaid[0] = 0L;
            }

            overlay.show("topup-loan");
        };
    }

    // ── Modal: Confirmar archivado ──────────────────────────────────────
    private static Consumer<String> buildArchiveModal(
        ModalOverlay overlay, LoanService loanService,
        AuthSession session, Runnable refreshBalances
    ) {
        String userUid = session.uid();

        HBox header = overlay.buildHeader("Archivar préstamo",
            "El préstamo se ocultará de la lista activa");

        // ── Icono de advertencia ─────────────────────────────
        Label warningIcon = new Label("!");
        warningIcon.setStyle(
            "-fx-background-color: #FEF3C7; -fx-background-radius: 50; "
            + "-fx-text-fill: #D97706; -fx-font-weight: 800; -fx-font-size: 24px; "
            + "-fx-min-width: 60; -fx-min-height: 60; "
            + "-fx-max-width: 60; -fx-max-height: 60; "
            + "-fx-alignment: center;"
        );

        // ── Mensaje de confirmación ──────────────────────────
        Label confirmLabel = new Label("¿Estás seguro de archivar este préstamo?");
        confirmLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: 700; -fx-text-fill: #374151;");
        confirmLabel.setWrapText(true);

        Label detailLabel = new Label(
            "El préstamo se marcará como cerrado y se ocultará de la lista principal. " +
            "El historial financiero se conservará. Puedes ver préstamos archivados en una vista futura."
        );
        detailLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #6B7280;");
        detailLabel.setWrapText(true);

        VBox messageBox = new VBox(10, confirmLabel, detailLabel);
        messageBox.setAlignment(Pos.CENTER);
        messageBox.setPadding(new Insets(10, 0, 10, 0));

        HBox warningRow = new HBox(16, warningIcon, messageBox);
        warningRow.setAlignment(Pos.CENTER_LEFT);
        warningRow.setPadding(new Insets(16, 12, 16, 12));
        warningRow.setStyle(
            "-fx-background-color: #FFFBEB; "
            + "-fx-background-radius: 12; -fx-border-radius: 12;"
        );

        // ── Error label ──────────────────────────────────────
        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: 700; -fx-text-fill: #DC2626;");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        // ── Footer ───────────────────────────────────────────
        HBox footer = new HBox(10);
        footer.setAlignment(Pos.CENTER);
        footer.setPadding(new Insets(10, 0, 0, 0));

        Button cancelBtn = new Button("Cancelar");
        cancelBtn.getStyleClass().addAll("btn-secondary");
        cancelBtn.setStyle("-fx-min-width: 120;");
        cancelBtn.setOnAction(e -> overlay.hide());

        Button confirmBtn = new Button("Archivar");
        confirmBtn.getStyleClass().addAll("btn-primary");
        confirmBtn.setStyle(
            "-fx-min-width: 120; "
            + "-fx-background-color: #EF4444; "
            + "-fx-text-fill: white; "
            + "-fx-font-weight: 700;"
        );
        confirmBtn.setOnAction(ev -> {
            errorLabel.setVisible(false);
            errorLabel.setManaged(false);

            String activeLoanId = (String) confirmBtn.getProperties().get("loanId");
            if (activeLoanId == null || activeLoanId.isBlank()) {
                errorLabel.setText("Error: No se ha seleccionado un préstamo");
                errorLabel.setVisible(true);
                errorLabel.setManaged(true);
                return;
            }

            try {
                loanService.archiveLoan(userUid, session, activeLoanId);
                overlay.hide();
                Platform.runLater(() -> {
                    try { refreshBalances.run(); } catch (Exception ignored) {}
                });
            } catch (Exception ex) {
                errorLabel.setText(ex.getMessage() != null ? ex.getMessage() : "Error al archivar préstamo");
                errorLabel.setVisible(true);
                errorLabel.setManaged(true);
            }
        });

        footer.getChildren().addAll(cancelBtn, confirmBtn);

        overlay.register("archive-loan", 360,
            header, warningRow, errorLabel, footer);

        // Retorna consumer que guarda el loanId y muestra el modal
        return loanId -> {
            confirmBtn.getProperties().put("loanId", loanId);
            errorLabel.setVisible(false);
            errorLabel.setManaged(false);
            overlay.show("archive-loan");
        };
    }

    // ── Panel de actividad (timeline) ───────────────────────────────────
    private static Node buildActivityTimeline(LoanService loanService, String userUid, boolean dk) {
        VBox timeline = new VBox(0);
        timeline.setFillWidth(true);

        try {
            // Cargar todos los préstamos y pagos
            List<LoanRepository.Loan> allLoans = loanService.listAll(userUid);
            List<LoanPaymentRepository.LoanPayment> allPayments = loanService.listAllPayments(userUid);

            // Mapa rápido de loan ID → Loan
            java.util.Map<String, LoanRepository.Loan> loanMap = new java.util.HashMap<>();
            for (LoanRepository.Loan l : allLoans) loanMap.put(l.id(), l);

            // Mapa rápido de account ID → nombre
            java.util.Map<String, String> accountNameCache = new java.util.HashMap<>();

            // Crear entries unificados: { epochSec, tipo, datos }
            record TimelineItem(long epochSec, String icon, String iconColor, String iconBg,
                                String action, String amount, String detail, String time) {}

            List<TimelineItem> items = new java.util.ArrayList<>();

            // Agregar creaciones de préstamos
            for (LoanRepository.Loan loan : allLoans) {
                String accName = resolveAccountName(loanService, userUid, loan.accountId(), accountNameCache);
                boolean isLent = LoanRepository.TYPE_LENT.equals(loan.type());

                String action = isLent ? "Prestaste" : "Pediste prestado";
                String icon = isLent ? "fas-arrow-up" : "fas-arrow-down";
                String color = isLent ? "#2563EB" : "#8B5CF6";

                items.add(new TimelineItem(
                    loan.occurredAtEpochSec(),
                    icon, color, color + "1A",
                    action,
                    DashboardFormatters.formatMoney(loan.principalCents()),
                    (isLent ? "Préstamo a " : "Préstamo de ") + loan.counterpartyName() + " · " + accName,
                    formatTimeOfDay(loan.occurredAtEpochSec())
                ));
            }

            // Agregar pagos
            for (LoanPaymentRepository.LoanPayment payment : allPayments) {
                LoanRepository.Loan loan = loanMap.get(payment.loanId());
                if (loan == null) continue;

                String accName = resolveAccountName(loanService, userUid, payment.accountId(), accountNameCache);
                boolean isLent = LoanRepository.TYPE_LENT.equals(loan.type());

                String action = isLent
                    ? loan.counterpartyName() + " pagó"
                    : "Pagaste a " + loan.counterpartyName();
                String icon = isLent ? "fas-arrow-down" : "fas-arrow-up";
                String color = isLent ? "#16A34A" : "#EA580C";

                items.add(new TimelineItem(
                    payment.occurredAtEpochSec(),
                    icon, color, color + "1A",
                    action,
                    DashboardFormatters.formatMoney(payment.principalCents()),
                    (isLent ? "Préstamo a " : "Préstamo de ") + loan.counterpartyName() + " · " + accName,
                    formatTimeOfDay(payment.occurredAtEpochSec())
                ));
            }

            if (items.isEmpty()) {
                return timeline;
            }

            // Ordenar por fecha descendente
            items.sort((a, b) -> Long.compare(b.epochSec(), a.epochSec()));

            // Agrupar por día
            java.time.LocalDate today = java.time.LocalDate.now();
            java.time.LocalDate yesterday = today.minusDays(1);
            java.time.format.DateTimeFormatter dayFmt = java.time.format.DateTimeFormatter
                .ofPattern("EEEE, d 'de' MMMM", new java.util.Locale("es"));

            String currentDayKey = null;
            for (TimelineItem item : items) {
                java.time.LocalDate d = java.time.Instant.ofEpochSecond(item.epochSec())
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
                String dayKey;
                if (d.equals(today)) {
                    dayKey = "Hoy";
                } else if (d.equals(yesterday)) {
                    dayKey = "Ayer";
                } else {
                    String raw = d.format(dayFmt);
                    dayKey = raw.substring(0, 1).toUpperCase() + raw.substring(1);
                }

                if (!dayKey.equals(currentDayKey)) {
                    currentDayKey = dayKey;
                    timeline.getChildren().add(buildDateHeader(dayKey, dk));
                }

                timeline.getChildren().add(buildTimelineEntry(
                    item.icon(), item.iconColor(), item.iconBg(),
                    item.action(), item.amount(),
                    item.detail(), item.time(), dk
                ));
            }
        } catch (Exception ex) {
            Label errLbl = new Label("Error al cargar actividad");
            errLbl.setStyle("-fx-text-fill: #DC2626; -fx-font-size: 12px;");
            timeline.getChildren().add(errLbl);
        }

        return timeline;
    }

    private static String resolveAccountName(
        LoanService loanService, String userUid, String accountId,
        java.util.Map<String, String> cache
    ) {
        if (accountId == null) return "Cuenta";
        return cache.computeIfAbsent(accountId, id -> {
            try {
                AccountRepository.Account acc = loanService.getAccount(userUid, id);
                return acc != null ? "Cuenta " + acc.name() : "Cuenta";
            } catch (Exception e) { return "Cuenta"; }
        });
    }

    private static String formatTimeOfDay(long epochSec) {
        if (epochSec <= 0) return "";
        java.time.LocalTime t = java.time.Instant.ofEpochSecond(epochSec)
            .atZone(java.time.ZoneId.systemDefault()).toLocalTime();
        int h = t.getHour();
        int m = t.getMinute();
        String ampm = h < 12 ? "a.\u00A0m." : "p.\u00A0m.";
        int h12 = h == 0 ? 12 : h > 12 ? h - 12 : h;
        return h12 + ":" + String.format("%02d", m) + " " + ampm;
    }

    private static Node buildDateHeader(String text, boolean dk) {
        Label label = new Label(text);
        label.setStyle(
            "-fx-font-size: 12px; -fx-font-weight: 800; "
            + "-fx-text-fill: " + (dk ? "#E5E7EB" : "#334155") + ";"
        );
        HBox header = new HBox(label);
        header.setPadding(new Insets(20, 0, 8, 20));
        return header;
    }

    private static Node buildTimelineEntry(
        String iconLiteral, String iconColor, String iconBgColor,
        String action, String amount,
        String detail, String time, boolean dk
    ) {
        String lineColor = dk ? "rgba(255,255,255,0.10)" : "#E2E8F0";

        // ── Línea vertical de timeline ───────────────────────────
        Region lineTop = new Region();
        lineTop.setStyle("-fx-background-color: " + lineColor + ";");
        lineTop.setPrefWidth(2);
        lineTop.setMinWidth(2);
        lineTop.setMaxWidth(2);
        lineTop.setPrefHeight(12);
        lineTop.setMinHeight(12);

        // ── Icono circular ───────────────────────────────────────
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(14);
        icon.setIconColor(Color.web(iconColor));
        StackPane iconCircle = new StackPane(icon);
        iconCircle.setStyle(
            "-fx-background-color: " + iconBgColor + "; "
            + "-fx-background-radius: 50; "
            + "-fx-min-width: 32; -fx-min-height: 32; "
            + "-fx-max-width: 32; -fx-max-height: 32;"
        );

        Region lineBottom = new Region();
        lineBottom.setStyle("-fx-background-color: " + lineColor + ";");
        lineBottom.setPrefWidth(2);
        lineBottom.setMinWidth(2);
        lineBottom.setMaxWidth(2);
        VBox.setVgrow(lineBottom, Priority.ALWAYS);

        VBox lineAndIcon = new VBox(0, lineTop, iconCircle, lineBottom);
        lineAndIcon.setAlignment(Pos.TOP_CENTER);
        lineAndIcon.setMinWidth(32);

        // ── Contenido de la entrada ──────────────────────────────
        Label actionLabel = new Label(action);
        actionLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: 700;" + (dk ? " -fx-text-fill: #E5E7EB;" : ""));

        Label amountLabel = new Label("  " + amount);
        amountLabel.setStyle(
            "-fx-font-size: 13px; -fx-font-weight: 800; "
            + "-fx-text-fill: " + iconColor + ";"
        );

        HBox actionRow = new HBox(0, actionLabel, amountLabel);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        Label detailLabel = new Label(detail);
        detailLabel.setStyle(
            "-fx-font-size: 11px; -fx-text-fill: " + (dk ? "rgba(229,231,235,0.55)" : "#94A3B8") + "; -fx-font-weight: 600;"
        );
        detailLabel.setWrapText(true);

        Label timeLabel = new Label(time);
        timeLabel.setStyle(
            "-fx-font-size: 10px; -fx-text-fill: " + (dk ? "rgba(203,213,225,0.50)" : "#CBD5E1") + "; -fx-font-weight: 600;"
        );

        VBox textContent = new VBox(3, actionRow, detailLabel, timeLabel);
        textContent.setAlignment(Pos.CENTER_LEFT);

        // ── Card de la entrada ───────────────────────────────────
        String entryBase = dk
            ? "-fx-background-color: rgba(15,23,42,0.92); "
              + "-fx-background-radius: 12; -fx-border-radius: 12; "
              + "-fx-border-color: rgba(229,231,235,0.08); -fx-border-width: 1; "
              + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.25), 6, 0, 0, 1);"
            : "-fx-background-color: white; "
              + "-fx-background-radius: 12; -fx-border-radius: 12; "
              + "-fx-border-color: #F1F5F9; -fx-border-width: 1; "
              + "-fx-effect: dropshadow(gaussian, rgba(15,23,42,0.03), 6, 0, 0, 1);";
        String entryHover = dk
            ? "-fx-background-color: rgba(59,130,246,0.06); "
              + "-fx-background-radius: 12; -fx-border-radius: 12; "
              + "-fx-border-color: rgba(59,130,246,0.30); -fx-border-width: 1; "
              + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 10, 0, 0, 2);"
            : "-fx-background-color: #FAFBFF; "
              + "-fx-background-radius: 12; -fx-border-radius: 12; "
              + "-fx-border-color: #E2E8F0; -fx-border-width: 1; "
              + "-fx-effect: dropshadow(gaussian, rgba(15,23,42,0.07), 10, 0, 0, 2);";

        HBox entryCard = new HBox(14, textContent);
        entryCard.setPadding(new Insets(12, 16, 12, 16));
        entryCard.setAlignment(Pos.CENTER_LEFT);
        entryCard.setStyle(entryBase);
        entryCard.setOnMouseEntered(ev -> entryCard.setStyle(entryHover));
        entryCard.setOnMouseExited(ev -> entryCard.setStyle(entryBase));
        HBox.setHgrow(entryCard, Priority.ALWAYS);

        // ── Fila completa: línea + card ──────────────────────────
        HBox row = new HBox(12, lineAndIcon, entryCard);
        row.setAlignment(Pos.TOP_LEFT);
        row.setPadding(new Insets(0, 0, 0, 8));
        HBox.setHgrow(entryCard, Priority.ALWAYS);

        return row;
    }

    // ── Hero summary card ────────────────────────────────────────────
    private static Node buildHeroSummaryCard(LoanService loanService, String userUid, boolean dk) {
        // Métricas reales: solo préstamos otorgados (me deben)
        long totalLentCents     = 0L;
        long recoveredCents     = 0L;
        long pendingCents       = 0L;
        int  recoveryPct        = 0;

        try {
            List<LoanRepository.Loan> lentLoans = loanService.listLent(userUid);
            for (LoanRepository.Loan loan : lentLoans) {
                totalLentCents += loan.principalCents();
                long paid = loanService.getPaidCents(userUid, loan.id());
                recoveredCents += paid;
            }
            pendingCents = Math.max(0L, totalLentCents - recoveredCents);
            recoveryPct = totalLentCents > 0
                ? (int) (recoveredCents * 100 / totalLentCents) : 0;
        } catch (Exception ignored) {}

        String totalFormatted     = DashboardFormatters.formatMoney(totalLentCents);
        String recoveredFormatted = DashboardFormatters.formatMoney(recoveredCents);
        String pendingFormatted   = DashboardFormatters.formatMoney(pendingCents);

        // ── Metric: Total prestado ───────────────────────────────
        Node totalBlock = buildHeroMetric(
            "TOTAL PRESTADO", totalFormatted, dk ? "#E5E7EB" : null,
            "fas-coins", "#2563EB", "#2563EB1A", dk
        );

        // ── Metric: Recuperado ───────────────────────────────────
        Node recoveredBlock = buildHeroMetric(
            "RECUPERADO", recoveredFormatted, dk ? "#34D399" : "#16A34A",
            "fas-check-circle", "#16A34A", "#16A34A1A", dk
        );

        // ── Metric: Pendiente ────────────────────────────────────
        Node pendingBlock = buildHeroMetric(
            "PENDIENTE", pendingFormatted, dk ? "#FB923C" : "#EA580C",
            "fas-clock", "#EA580C", "#EA580C1A", dk
        );

        // ── Separadores verticales ───────────────────────────────
        Region sep1 = heroSeparator(dk);
        Region sep2 = heroSeparator(dk);

        // ── Métricas en fila ─────────────────────────────────────
        HBox metricsRow = new HBox();
        metricsRow.setFillHeight(true);
        HBox.setHgrow(totalBlock, Priority.ALWAYS);
        HBox.setHgrow(recoveredBlock, Priority.ALWAYS);
        HBox.setHgrow(pendingBlock, Priority.ALWAYS);
        metricsRow.getChildren().addAll(totalBlock, sep1, recoveredBlock, sep2, pendingBlock);
        metricsRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(metricsRow, Priority.ALWAYS);

        // ── Indicador circular ───────────────────────────────────
        Node circularIndicator = buildCircularIndicator(recoveryPct, dk);

        // ── Separador antes del indicador ────────────────────────
        Region sepCircle = heroSeparator(dk);

        // ── Card completa ────────────────────────────────────────
        HBox cardContent = new HBox(0, metricsRow, sepCircle, circularIndicator);
        cardContent.setAlignment(Pos.CENTER);

        VBox card = new VBox(0, cardContent);
        card.setPadding(new Insets(14, 18, 14, 18));
        card.setStyle(dk
            ? "-fx-background-color: rgba(15,23,42,0.92); "
              + "-fx-background-radius: 16; -fx-border-radius: 16; "
              + "-fx-border-color: rgba(229,231,235,0.10); -fx-border-width: 1; "
              + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 16, 0.18, 0, 4);"
            : "-fx-background-color: white; "
              + "-fx-background-radius: 16; -fx-border-radius: 16; "
              + "-fx-border-color: #E2E8F0; -fx-border-width: 1; "
              + "-fx-effect: dropshadow(gaussian, rgba(15,23,42,0.07), 16, 0, 0, 4);"
        );
        card.getStyleClass().addAll("content-card");

        // ── Hero card hover ──────────────────────────────────────
        String heroBase = card.getStyle();
        String heroHover = dk
            ? "-fx-background-color: rgba(59,130,246,0.06); "
              + "-fx-background-radius: 16; -fx-border-radius: 16; "
              + "-fx-border-color: rgba(59,130,246,0.35); -fx-border-width: 1; "
              + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.45), 20, 0.22, 0, 6);"
            : "-fx-background-color: #FAFBFF; "
              + "-fx-background-radius: 16; -fx-border-radius: 16; "
              + "-fx-border-color: #CBD5E1; -fx-border-width: 1; "
              + "-fx-effect: dropshadow(gaussian, rgba(15,23,42,0.12), 20, 0, 0, 6);";

        TranslateTransition heroUp = new TranslateTransition(Duration.millis(200), card);
        heroUp.setToY(-2);
        heroUp.setInterpolator(Interpolator.EASE_BOTH);
        TranslateTransition heroDown = new TranslateTransition(Duration.millis(200), card);
        heroDown.setToY(0);
        heroDown.setInterpolator(Interpolator.EASE_BOTH);

        card.setOnMouseEntered(ev -> {
            card.setStyle(heroHover);
            heroDown.stop();
            heroUp.playFromStart();
        });
        card.setOnMouseExited(ev -> {
            card.setStyle(heroBase);
            heroUp.stop();
            heroDown.playFromStart();
        });

        // ── Entrance fade-in ─────────────────────────────────────
        card.setOpacity(0);
        card.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                FadeTransition fadeIn = new FadeTransition(Duration.millis(400), card);
                fadeIn.setFromValue(0);
                fadeIn.setToValue(1);
                fadeIn.setInterpolator(Interpolator.EASE_OUT);
                fadeIn.setDelay(Duration.millis(100));
                fadeIn.play();
            }
        });

        return card;
    }

    // ── Bloque individual de métrica ─────────────────────────────────
    private static Node buildHeroMetric(
        String label, String value, String valueColor,
        String iconLiteral, String iconColor, String iconBgColor, boolean dk
    ) {
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(16);
        icon.setIconColor(Color.web(iconColor));

        StackPane iconCircle = new StackPane(icon);
        iconCircle.setStyle(
            "-fx-background-color: " + iconBgColor + "; "
            + "-fx-background-radius: 50; "
            + "-fx-min-width: 34; -fx-min-height: 34; "
            + "-fx-max-width: 34; -fx-max-height: 34;"
        );

        Label descLabel = new Label(label);
        descLabel.setStyle(
            "-fx-font-size: 10px; -fx-font-weight: 700; "
            + "-fx-text-fill: " + (dk ? "rgba(229,231,235,0.55)" : "#94A3B8") + "; -fx-letter-spacing: 0.5;"
        );

        Label valLabel = new Label(value);
        valLabel.setStyle(
            "-fx-font-size: 15px; -fx-font-weight: 900;"
            + (valueColor != null ? " -fx-text-fill: " + valueColor + ";" : "")
        );

        VBox textCol = new VBox(2, descLabel, valLabel);
        textCol.setAlignment(Pos.CENTER_LEFT);

        HBox row = new HBox(10, iconCircle, textCol);
        row.setAlignment(Pos.CENTER_LEFT);

        VBox block = new VBox(row);
        block.setAlignment(Pos.CENTER);
        block.setPadding(new Insets(2, 14, 2, 14));
        return block;
    }

    // ── Separador vertical suave ─────────────────────────────────────
    private static Region heroSeparator(boolean dk) {
        Region sep = new Region();
        sep.setStyle(
            "-fx-background-color: " + (dk ? "rgba(255,255,255,0.10)" : "#E2E8F0") + "; "
            + "-fx-pref-width: 1; -fx-min-width: 1; -fx-max-width: 1;"
        );
        sep.setMinHeight(40);
        return sep;
    }

    // ── Indicador circular de recuperación ───────────────────────────
    private static Node buildCircularIndicator(int percent, boolean dk) {
        double size = 72;
        double stroke = 6;
        double radius = (size - stroke) / 2.0;
        double cx = size / 2.0;
        double cy = size / 2.0;

        Canvas canvas = new Canvas(size, size);
        GraphicsContext gc = canvas.getGraphicsContext2D();

        // Track (fondo)
        gc.setLineWidth(stroke);
        gc.setStroke(Color.web(dk ? "rgba(255,255,255,0.10)" : "#E2E8F0"));
        gc.strokeOval(cx - radius, cy - radius, radius * 2, radius * 2);

        // Arco de progreso
        if (percent > 0) {
            gc.setLineWidth(stroke);
            gc.setLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
            gc.setStroke(Color.web("#2563EB"));
            double angle = percent * 3.6;
            gc.strokeArc(
                cx - radius, cy - radius, radius * 2, radius * 2,
                90, -angle,
                javafx.scene.shape.ArcType.OPEN
            );
        }

        // Texto central
        gc.setFill(Color.web(dk ? "#E5E7EB" : "#0F172A"));
        gc.setFont(Font.font("System", FontWeight.BLACK, 15));
        String pctText = percent + "%";
        javafx.scene.text.Text measure = new javafx.scene.text.Text(pctText);
        measure.setFont(gc.getFont());
        double tw = measure.getLayoutBounds().getWidth();
        double th = measure.getLayoutBounds().getHeight();
        gc.fillText(pctText, cx - tw / 2.0, cy + th / 4.0);

        // Etiqueta inferior
        Label indicator = new Label("Recuperación");
        indicator.setStyle(
            "-fx-font-size: 10px; -fx-font-weight: 700; -fx-text-fill: " + (dk ? "rgba(229,231,235,0.55)" : "#94A3B8") + ";"
        );
        indicator.setAlignment(Pos.CENTER);

        VBox wrapper = new VBox(4, new StackPane(canvas), indicator);
        wrapper.setAlignment(Pos.CENTER);
        wrapper.setPadding(new Insets(2, 16, 2, 16));

        return wrapper;
    }

    // ── Utilidades de formato para préstamos reales ─────────────────────

    private static String buildInitials(String name) {
        if (name == null || name.isBlank()) return "??";
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        }
        return (parts[0].charAt(0) + "" + parts[parts.length - 1].charAt(0)).toUpperCase();
    }

    private static String formatLoanDate(long epochSec) {
        if (epochSec <= 0) return "";
        java.time.LocalDate d = java.time.Instant.ofEpochSecond(epochSec)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter
            .ofPattern("dd MMM yyyy", new java.util.Locale("es"));
        return d.format(fmt);
    }

    private static String formatRelativeTime(long epochSec) {
        if (epochSec <= 0) return "";
        long now = java.time.Instant.now().getEpochSecond();
        long diff = now - epochSec;
        if (diff < 60) return "Justo ahora";
        if (diff < 3600) return "Hace " + (diff / 60) + " min";
        if (diff < 86400) return "Hace " + (diff / 3600) + " h";
        long days = diff / 86400;
        if (days == 1) return "Hace 1 día";
        if (days < 7) return "Hace " + days + " días";
        if (days < 30) return "Hace " + (days / 7) + " sem";
        return "Hace " + (days / 30) + " mes" + ((days / 30) > 1 ? "es" : "");
    }

    // ══════════════════════════════════════════════════════════════════
    //  E M P T Y   S T A T E S   P R E M I U M
    // ══════════════════════════════════════════════════════════════════

    /**
     * Crea un empty state premium reutilizable estilo Nubank/Nequi.
     * <p>
     * Incluye: ilustración compuesta, título emocional, subtítulo útil,
     * CTA elegante, hint text, acento decorativo y mucho aire visual.
     *
     * @param icons        3 iconos FontAwesome [principal, decorador1, decorador2]
     * @param accentColor  Color principal hex (e.g. "#2563EB")
     * @param bgTint       Tinte de fondo para la ilustración (e.g. "#EFF6FF")
     * @param headline     Título emocional corto
     * @param subtitle     Mensaje de apoyo
     * @param ctaText      Texto del botón CTA (null para omitir)
     * @param ctaIcon      Icono del CTA (null usa "fas-plus")
     * @param ctaHint      Texto sutil bajo el CTA (null para omitir)
     * @param ctaAction    Acción al hacer clic (puede ser null)
     */
    static Node buildEmptyState(
        String[] icons, String accentColor, String bgTint,
        String headline, String subtitle,
        String ctaText, String ctaIcon, String ctaHint,
        Runnable ctaAction, boolean dk
    ) {
        // ── Acento decorativo superior ────────────────────────────
        Region accentDot = new Region();
        accentDot.setStyle(
            "-fx-background-color: " + accentColor + "33; "
            + "-fx-background-radius: 50; "
            + "-fx-min-width: 6; -fx-min-height: 6; "
            + "-fx-max-width: 6; -fx-max-height: 6;"
        );
        HBox dotRow = new HBox(accentDot);
        dotRow.setAlignment(Pos.CENTER);
        dotRow.setPadding(new Insets(0, 0, 8, 0));

        // ── Ilustración minimalista compuesta ─────────────────────
        FontIcon mainIcon = new FontIcon(icons[0]);
        mainIcon.setIconSize(46);
        mainIcon.setIconColor(Color.web(accentColor));

        FontIcon decor1 = new FontIcon(icons[1]);
        decor1.setIconSize(15);
        decor1.setIconColor(Color.web(accentColor + "55"));

        FontIcon decor2 = new FontIcon(icons[2]);
        decor2.setIconSize(13);
        decor2.setIconColor(Color.web(accentColor + "35"));

        StackPane iconCircle = new StackPane(mainIcon);
        iconCircle.setStyle(
            "-fx-background-color: " + bgTint + "; "
            + "-fx-background-radius: 50; "
            + "-fx-min-width: 96; -fx-min-height: 96; "
            + "-fx-max-width: 96; -fx-max-height: 96;"
        );
        iconCircle.setAlignment(Pos.CENTER);

        // Decoradores flotantes
        StackPane.setMargin(decor1, new Insets(4, 2, 0, 0));
        StackPane.setMargin(decor2, new Insets(0, 0, 6, 4));
        StackPane illustrationPane = new StackPane(iconCircle, decor1, decor2);
        illustrationPane.setMaxWidth(130);
        illustrationPane.setMaxHeight(110);
        illustrationPane.setAlignment(Pos.CENTER);
        StackPane.setAlignment(decor1, Pos.TOP_RIGHT);
        StackPane.setAlignment(decor2, Pos.BOTTOM_LEFT);

        // ── Tipografía ───────────────────────────────────────────
        Label headlineLbl = new Label(headline);
        headlineLbl.setStyle(
            "-fx-font-size: 18px; -fx-font-weight: 900; -fx-text-fill: " + (dk ? "#E5E7EB" : "#0F172A") + ";"
        );
        headlineLbl.setWrapText(true);
        headlineLbl.setAlignment(Pos.CENTER);
        headlineLbl.setMaxWidth(340);

        Label subtitleLbl = new Label(subtitle);
        subtitleLbl.setStyle(
            "-fx-font-size: 13px; -fx-font-weight: 500; -fx-text-fill: " + (dk ? "rgba(229,231,235,0.55)" : "#94A3B8") + "; "
            + "-fx-line-spacing: 3;"
        );
        subtitleLbl.setWrapText(true);
        subtitleLbl.setAlignment(Pos.CENTER);
        subtitleLbl.setMaxWidth(310);

        VBox textBlock = new VBox(10, headlineLbl, subtitleLbl);
        textBlock.setAlignment(Pos.CENTER);

        // ── Contenedor principal ──────────────────────────────────
        VBox container = new VBox(24, dotRow, illustrationPane, textBlock);
        container.setAlignment(Pos.CENTER);
        container.setPadding(new Insets(56, 40, 56, 40));

        // ── CTA elegante ─────────────────────────────────────────
        if (ctaText != null) {
            String iconLit = ctaIcon != null ? ctaIcon : "fas-plus";
            FontIcon btnIcon = new FontIcon(iconLit);
            btnIcon.setIconSize(13);
            btnIcon.setIconColor(Color.WHITE);

            Button cta = new Button(ctaText);
            cta.setGraphic(btnIcon);
            cta.setGraphicTextGap(8);
            String ctaBase =
                "-fx-background-color: " + accentColor + "; "
                + "-fx-text-fill: white; "
                + "-fx-background-radius: 14; -fx-border-radius: 14; "
                + "-fx-font-size: 13px; -fx-font-weight: 700; "
                + "-fx-cursor: hand; -fx-padding: 12 32 12 32; "
                + "-fx-effect: dropshadow(gaussian, " + accentColor + "33, 12, 0, 0, 3);";
            String ctaHoverStyle =
                "-fx-background-color: derive(" + accentColor + ", -12%); "
                + "-fx-text-fill: white; "
                + "-fx-background-radius: 14; -fx-border-radius: 14; "
                + "-fx-font-size: 13px; -fx-font-weight: 700; "
                + "-fx-cursor: hand; -fx-padding: 12 32 12 32; "
                + "-fx-effect: dropshadow(gaussian, " + accentColor + "55, 16, 0, 0, 4);";
            cta.setStyle(ctaBase);
            cta.setOnMouseEntered(ev -> cta.setStyle(ctaHoverStyle));
            cta.setOnMouseExited(ev -> cta.setStyle(ctaBase));
            if (ctaAction != null) cta.setOnAction(ev -> ctaAction.run());

            VBox ctaBlock = new VBox(8, cta);
            ctaBlock.setAlignment(Pos.CENTER);

            // Hint text debajo del CTA
            if (ctaHint != null) {
                Label hint = new Label(ctaHint);
                hint.setStyle(
                    "-fx-font-size: 10px; -fx-font-weight: 600; -fx-text-fill: #CBD5E1;"
                );
                hint.setAlignment(Pos.CENTER);
                ctaBlock.getChildren().add(hint);
            }

            container.getChildren().add(ctaBlock);
        }

        // ── Wrapper card ──────────────────────────────────────────
        VBox card = new VBox(container);
        card.setAlignment(Pos.CENTER);
        card.setStyle(dk
            ? "-fx-background-color: rgba(15,23,42,0.92); "
              + "-fx-background-radius: 18; -fx-border-radius: 18; "
              + "-fx-border-color: rgba(229,231,235,0.08); -fx-border-width: 1; "
              + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.30), 10, 0, 0, 2);"
            : "-fx-background-color: white; "
              + "-fx-background-radius: 18; -fx-border-radius: 18; "
              + "-fx-border-color: #F1F5F9; -fx-border-width: 1; "
              + "-fx-effect: dropshadow(gaussian, rgba(15,23,42,0.04), 10, 0, 0, 2);"
        );

        // Fade-in suave (una sola vez)
        card.setOpacity(0);
        boolean[] played = { false };
        card.sceneProperty().addListener((obs, oldS, newS) -> {
            if (newS != null && !played[0]) {
                played[0] = true;
                FadeTransition fi = new FadeTransition(Duration.millis(600), card);
                fi.setFromValue(0);
                fi.setToValue(1);
                fi.setInterpolator(Interpolator.EASE_OUT);
                fi.setDelay(Duration.millis(80));
                fi.play();
            }
        });

        return card;
    }

    // ── Empty states específicos del módulo Préstamos ────────────────

    /** Sin préstamos — estado inicial motivacional */
    static Node buildEmptyLoans(Runnable onCreateLoan, boolean dk) {
        return buildEmptyState(
            new String[]{ "fas-hand-holding-usd", "fas-star", "fas-coins" },
            "#2563EB", dk ? "rgba(37,99,235,0.12)" : "#EFF6FF",
            "Todavía no tienes préstamos",
            "Empieza a registrar el dinero que prestas\no te prestan. Todo organizado en un solo lugar.",
            "Registrar primer préstamo", "fas-plus", "Toma menos de 30 segundos",
            onCreateLoan, dk
        );
    }

    /** Sin actividad — timeline vacío */
    static Node buildEmptyActivity(boolean dk) {
        return buildEmptyState(
            new String[]{ "fas-history", "fas-clock", "fas-stream" },
            "#8B5CF6", dk ? "rgba(139,92,246,0.12)" : "#F5F3FF",
            "Sin movimientos recientes",
            "Cuando registres pagos o nuevos préstamos,\naparecerán aquí como una línea de tiempo.",
            null, null, null, null, dk
        );
    }

    /** Búsqueda vacía — sin resultados */
    static Node buildEmptySearch(boolean dk) {
        return buildEmptyState(
            new String[]{ "fas-search", "fas-filter", "fas-times-circle" },
            "#F59E0B", dk ? "rgba(245,158,11,0.12)" : "#FFFBEB",
            "No encontramos resultados",
            "Intenta con otro nombre o revisa\nlos filtros activos.",
            "Limpiar búsqueda", "fas-redo", null, null, dk
        );
    }

    /** Sin pagos registrados — card individual */
    static Node buildEmptyPayments(boolean dk) {
        return buildEmptyState(
            new String[]{ "fas-receipt", "fas-check-circle", "fas-piggy-bank" },
            "#059669", dk ? "rgba(5,150,105,0.12)" : "#F0FDF4",
            "Aún no hay pagos registrados",
            "Los abonos que registres aparecerán aquí\ncon todo el detalle de cada transacción.",
            null, null, null, null, dk
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DRAWER LATERAL: Detalle de préstamo
    // ═══════════════════════════════════════════════════════════════════════════

    /** Muestra side drawer con detalles del préstamo — animado, fintech style */
    private static void showLoanDetailDrawer(
        StackPane root,
        LoanService loanService,
        String userUid,
        String loanId,
        boolean dk,
        AuthSession session,
        ModalOverlay modalOverlay,
        Runnable refreshAll
    ) {
        try {
            // ── Datos del préstamo ─────────────────────────────────────
            LoanRepository.Loan loan = loanService.getLoan(userUid, loanId);
            if (loan == null) return;

            List<LoanMovementRepository.LoanMovement> movements = loanService.listMovements(userUid, loanId);
            movements.sort((a, b) -> Long.compare(b.occurredAtEpochSec(), a.occurredAtEpochSec()));

            long paidCents = loanService.getPaidCents(userUid, loanId);
            long pendingCents = loan.principalCents() - paidCents;
            boolean isClosed = LoanRepository.STATUS_CLOSED.equals(loan.status());

            // ── Backdrop oscuro semitransparente ───────────────────────
            Region backdrop = new Region();
            backdrop.setStyle("-fx-background-color: rgba(15,23,42,0.45);");
            backdrop.setVisible(false);
            backdrop.setOpacity(0);

            // ── Drawer container (desliza desde derecha) ───────────────
            VBox drawer = new VBox();
            drawer.setPrefWidth(420);
            drawer.setMaxWidth(420);
            drawer.setMinWidth(420);
            drawer.setFillWidth(true);
            drawer.setMaxHeight(Double.MAX_VALUE);
            drawer.setStyle(
                (dk
                    ? "-fx-background-color: linear-gradient(to bottom, #0F172A, #1E293B); "
                    : "-fx-background-color: linear-gradient(to bottom, #FFFFFF, #F8FAFC); ")
                + "-fx-background-radius: 24 0 0 24; "
                + "-fx-border-radius: 24 0 0 24; "
                + (dk ? "-fx-border-color: rgba(59,130,246,0.25); " : "-fx-border-color: #E2E8F0; ")
                + "-fx-border-width: 1 0 1 1; "
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 40, 0, -10, 0);"
            );
            drawer.setPadding(new Insets(28, 28, 28, 32));
            VBox.setVgrow(drawer, Priority.ALWAYS);

            // Posición inicial: fuera de pantalla (derecha)
            drawer.setTranslateX(450);

            // ── Header con título y botón cerrar ─────────────────────────
            HBox header = new HBox();
            header.setAlignment(Pos.CENTER_LEFT);
            header.setSpacing(12);

            FontIcon closeIcon = new FontIcon("fas-times");
            closeIcon.setIconSize(14);
            closeIcon.setIconColor(Color.web(dk ? "#94A3B8" : "#64748B"));
            Button btnClose = new Button();
            btnClose.setGraphic(closeIcon);
            btnClose.setStyle(
                "-fx-background-color: transparent; "
                + "-fx-min-width: 36; -fx-min-height: 36; "
                + "-fx-max-width: 36; -fx-max-height: 36; "
                + "-fx-cursor: hand; -fx-padding: 0; "
                + "-fx-background-radius: 10; -fx-border-radius: 10;"
            );
            btnClose.setOnMouseEntered(e -> btnClose.setStyle(
                "-fx-background-color: " + (dk ? "rgba(255,255,255,0.08)" : "#F1F5F9") + "; "
                + "-fx-min-width: 36; -fx-min-height: 36; "
                + "-fx-max-width: 36; -fx-max-height: 36; "
                + "-fx-cursor: hand; -fx-padding: 0; "
                + "-fx-background-radius: 10; -fx-border-radius: 10;"
            ));
            btnClose.setOnMouseExited(e -> btnClose.setStyle(
                "-fx-background-color: transparent; "
                + "-fx-min-width: 36; -fx-min-height: 36; "
                + "-fx-max-width: 36; -fx-max-height: 36; "
                + "-fx-cursor: hand; -fx-padding: 0; "
                + "-fx-background-radius: 10; -fx-border-radius: 10;"
            ));

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Label title = new Label("Detalle del préstamo");
            title.setStyle(
                "-fx-font-size: 18px; -fx-font-weight: 700; "
                + (dk ? "-fx-text-fill: #F1F5F9;" : "-fx-text-fill: #0F172A;")
            );

            header.getChildren().addAll(title, spacer, btnClose);

            // ── Persona destacada ──────────────────────────────────────
            String initials = buildInitials(loan.counterpartyName());
            Label avatar = new Label(initials);
            avatar.setStyle(
                "-fx-background-color: #3B82F6; "
                + "-fx-background-radius: 50; "
                + "-fx-text-fill: white; -fx-font-weight: 800; -fx-font-size: 18px; "
                + "-fx-min-width: 56; -fx-min-height: 56; "
                + "-fx-max-width: 56; -fx-max-height: 56; "
                + "-fx-alignment: center;"
            );

            Label personName = new Label(loan.counterpartyName());
            personName.setStyle(
                "-fx-font-size: 20px; -fx-font-weight: 800; "
                + (dk ? "-fx-text-fill: #F8FAFC;" : "-fx-text-fill: #0F172A;")
            );

            String statusText = isClosed ? "Cerrado" : "Activo";
            String statusColor = isClosed ? "#10B981" : "#3B82F6";
            Label statusBadge = new Label("● " + statusText);
            statusBadge.setStyle(
                "-fx-font-size: 12px; -fx-font-weight: 700; "
                + "-fx-text-fill: " + statusColor + "; "
                + "-fx-background-color: " + (dk ? "rgba(255,255,255,0.08)" : statusColor + "15") + "; "
                + "-fx-background-radius: 20; -fx-border-radius: 20; "
                + "-fx-padding: 6 14 6 14;"
            );

            VBox personInfo = new VBox(4, personName, statusBadge);
            personInfo.setAlignment(Pos.CENTER_LEFT);

            HBox personRow = new HBox(16, avatar, personInfo);
            personRow.setAlignment(Pos.CENTER_LEFT);
            personRow.setPadding(new Insets(20, 0, 20, 0));

            // ── Métricas principales (cards horizontales) ───────────────
            HBox metricsRow = new HBox(12);
            metricsRow.setAlignment(Pos.CENTER);

            VBox totalCard = buildMetricCard("Total", DashboardFormatters.formatMoney(loan.principalCents()), "#3B82F6", dk);
            VBox paidCard = buildMetricCard("Pagado", DashboardFormatters.formatMoney(paidCents), "#10B981", dk);
            VBox pendingCard = buildMetricCard("Pendiente", DashboardFormatters.formatMoney(Math.max(0, pendingCents)), "#F59E0B", dk);

            HBox.setHgrow(totalCard, Priority.ALWAYS);
            HBox.setHgrow(paidCard, Priority.ALWAYS);
            HBox.setHgrow(pendingCard, Priority.ALWAYS);
            metricsRow.getChildren().addAll(totalCard, paidCard, pendingCard);

            // ── Referencia para closeDrawer (se define más adelante) ─────
            final Runnable[] closeDrawerRef = { null };

            // ── Botón Editar (solo si no está cerrado) ────────────────────
            final Button[] btnEditRef = { null };
            if (!isClosed) {
                FontIcon editIcon = new FontIcon("fas-edit");
                editIcon.setIconSize(12);
                editIcon.setIconColor(Color.WHITE);
                Button btnEdit = new Button("Editar préstamo");
                btnEdit.setGraphic(editIcon);
                btnEdit.setGraphicTextGap(6);
                String editBase =
                    "-fx-background-color: #6366F1; -fx-text-fill: white; "
                    + "-fx-background-radius: 10; -fx-border-radius: 10; "
                    + "-fx-font-size: 12px; -fx-font-weight: 700; -fx-cursor: hand; "
                    + "-fx-padding: 8 16 8 16;";
                String editHover =
                    "-fx-background-color: #4F46E5; -fx-text-fill: white; "
                    + "-fx-background-radius: 10; -fx-border-radius: 10; "
                    + "-fx-font-size: 12px; -fx-font-weight: 700; -fx-cursor: hand; "
                    + "-fx-padding: 8 16 8 16;";
                btnEdit.setStyle(editBase);
                btnEdit.setOnMouseEntered(ev -> btnEdit.setStyle(editHover));
                btnEdit.setOnMouseExited(ev -> btnEdit.setStyle(editBase));
                btnEdit.setOnAction(ev -> {
                    // Cerrar drawer antes de mostrar el modal
                    if (closeDrawerRef[0] != null) closeDrawerRef[0].run();
                    
                    // Construir modal de edición dinámicamente con datos del préstamo
                    buildEditLoanModal(modalOverlay, loanService, session, userUid, loanId, loan.accountId(), loan.principalCents(), () -> {
                        refreshAll.run();
                        // Reabrir drawer después de editar
                        Platform.runLater(() -> {
                            try {
                                showLoanDetailDrawer(root, loanService, userUid, loanId, dk, session, modalOverlay, refreshAll);
                            } catch (Exception ignored) {}
                        });
                    });
                    modalOverlay.show("edit-loan");
                });
                btnEditRef[0] = btnEdit;
            }

            // ── Timeline de movimientos ─────────────────────────────────
            Label timelineTitle = new Label("Timeline de movimientos");
            timelineTitle.setStyle(
                "-fx-font-size: 14px; -fx-font-weight: 700; "
                + (dk ? "-fx-text-fill: #E2E8F0;" : "-fx-text-fill: #334155;")
            );
            timelineTitle.setPadding(new Insets(24, 0, 12, 0));

            VBox timelineBox = new VBox(8);
            timelineBox.setFillWidth(true);

            // Guardar referencia a movimientos completos para historial
            final List<LoanMovementRepository.LoanMovement>[] allMovementsRef = new List[]{ movements };

            List<LoanMovementRepository.LoanMovement> visibleMovements = movements.size() > 5
                ? movements.subList(0, 5)
                : movements;

            if (visibleMovements.isEmpty()) {
                Label noMovements = new Label("Sin movimientos registrados");
                noMovements.setStyle(
                    "-fx-font-size: 13px; -fx-text-fill: " + (dk ? "#94A3B8" : "#64748B") + "; "
                    + "-fx-font-style: italic;"
                );
                timelineBox.getChildren().add(noMovements);
            } else {
                for (LoanMovementRepository.LoanMovement m : visibleMovements) {
                    Node item = buildTimelineItem(m, dk);
                    timelineBox.getChildren().add(item);
                }
            }

            timelineBox.setFillWidth(true);

            Button viewHistoryBtn = new Button("Ver historial completo");
            viewHistoryBtn.setStyle(
                "-fx-background-color: transparent; "
                + "-fx-text-fill: #3B82F6; "
                + "-fx-font-size: 12px; -fx-font-weight: 600; "
                + "-fx-cursor: hand; -fx-padding: 6 0 0 0;"
            );
            viewHistoryBtn.setOnAction(e -> {
                try {
                    showFullLoanHistoryDrawer(
                        drawer,
                        new ArrayList<>(drawer.getChildren()),
                        loanService,
                        session,
                        userUid,
                        loanId,
                        dk,
                        modalOverlay,
                        refreshAll,
                        closeDrawerRef
                    );
                } catch (Exception ignored) {
                }
            });

            // ── Info adicional ───────────────────────────────────────────
            String createdDate = formatLoanDate(loan.createdAtEpochSec());
            Label createdLbl = new Label("Creado el " + createdDate);
            createdLbl.setStyle(
                "-fx-font-size: 12px; -fx-text-fill: " + (dk ? "#64748B" : "#94A3B8") + ";"
            );
            createdLbl.setPadding(new Insets(16, 0, 0, 0));

            // ── Ensamblar contenido ────────────────────────────────────
            if (btnEditRef[0] != null) {
                drawer.getChildren().addAll(
                    header,
                    personRow,
                    metricsRow,
                    btnEditRef[0],
                    timelineTitle,
                    timelineBox,
                    viewHistoryBtn,
                    createdLbl
                );
            } else {
                drawer.getChildren().addAll(
                    header,
                    personRow,
                    metricsRow,
                    timelineTitle,
                    timelineBox,
                    viewHistoryBtn,
                    createdLbl
                );
            }

            // ── Contenedor final con backdrop ──────────────────────────
            // Usar AnchorPane para anclar drawer a la derecha
            javafx.scene.layout.AnchorPane drawerContainer = new javafx.scene.layout.AnchorPane();
            drawerContainer.setPickOnBounds(false);

            // Backdrop cubre todo
            javafx.scene.layout.AnchorPane.setTopAnchor(backdrop, 0.0);
            javafx.scene.layout.AnchorPane.setBottomAnchor(backdrop, 0.0);
            javafx.scene.layout.AnchorPane.setLeftAnchor(backdrop, 0.0);
            javafx.scene.layout.AnchorPane.setRightAnchor(backdrop, 0.0);

            // Drawer anclado a la derecha, centrado verticalmente
            javafx.scene.layout.AnchorPane.setTopAnchor(drawer, 0.0);
            javafx.scene.layout.AnchorPane.setBottomAnchor(drawer, 0.0);
            javafx.scene.layout.AnchorPane.setRightAnchor(drawer, 0.0);

            drawerContainer.getChildren().addAll(backdrop, drawer);
            drawerContainer.setVisible(false);

            // Ajustar al tamaño del root
            drawerContainer.prefWidthProperty().bind(root.widthProperty());
            drawerContainer.prefHeightProperty().bind(root.heightProperty());

            // Agregar al root (al frente)
            root.getChildren().add(drawerContainer);

            // ── Handler de ESC (declarado antes para poder removerlo) ───────
            final boolean[] drawerOpen = { true };

            // ── Función de cierre ───────────────────────────────────────
            Runnable closeDrawer = () -> {
                if (!drawerOpen[0]) return; // Evitar cierre múltiple
                drawerOpen[0] = false;

                // Animación drawer → derecha (fuera de pantalla)
                TranslateTransition slideOut = new TranslateTransition(Duration.millis(280), drawer);
                slideOut.setToX(450);
                slideOut.setInterpolator(Interpolator.EASE_IN);

                // Fade out backdrop
                FadeTransition fadeOut = new FadeTransition(Duration.millis(200), backdrop);
                fadeOut.setToValue(0);

                slideOut.setOnFinished(e -> {
                    if (root.getChildren().contains(drawerContainer)) {
                        root.getChildren().remove(drawerContainer);
                    }
                });

                fadeOut.play();
                slideOut.play();
            };

            closeDrawerRef[0] = closeDrawer;

            // Cerrar al hacer click en backdrop
            backdrop.setOnMouseClicked(e -> closeDrawer.run());
            btnClose.setOnAction(e -> closeDrawer.run());

            // ── Cerrar al presionar ESC ─────────────────────────────────
            javafx.event.EventHandler<javafx.scene.input.KeyEvent> escHandler = e -> {
                if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE && drawerOpen[0]) {
                    closeDrawer.run();
                }
            };
            if (root.getScene() != null) {
                root.getScene().addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, escHandler);
            }

            // ── Animación de entrada ───────────────────────────────────
            drawerContainer.setVisible(true);
            backdrop.setVisible(true);

            // Fade in backdrop
            FadeTransition fadeIn = new FadeTransition(Duration.millis(220), backdrop);
            fadeIn.setFromValue(0);
            fadeIn.setToValue(1);

            // Slide in drawer (desde derecha hacia posición final)
            TranslateTransition slideIn = new TranslateTransition(Duration.millis(320), drawer);
            slideIn.setFromX(450);
            slideIn.setToX(0);
            slideIn.setInterpolator(Interpolator.EASE_OUT);

            fadeIn.play();
            slideIn.play();

        } catch (Exception ex) {
            // Silencioso en caso de error — no interrumpir UX
        }
    }

    /** Card métrica para el drawer */
    private static VBox buildMetricCard(String label, String value, String color, boolean dk) {
        VBox card = new VBox(4);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(16, 10, 16, 10));
        card.setStyle(
            (dk
                ? "-fx-background-color: rgba(255,255,255,0.04); "
                : "-fx-background-color: " + color + "08; ")
            + "-fx-background-radius: 14; -fx-border-radius: 14;"
        );

        Label valueLbl = new Label(value);
        valueLbl.setWrapText(false);
        valueLbl.setStyle(
            "-fx-font-size: 14px; -fx-font-weight: 700; "
            + "-fx-text-fill: " + color + ";"
        );

        Label labelLbl = new Label(label);
        labelLbl.setStyle(
            "-fx-font-size: 11px; -fx-font-weight: 600; "
            + "-fx-text-fill: " + (dk ? "#94A3B8" : "#64748B") + ";"
        );

        card.getChildren().addAll(valueLbl, labelLbl);
        return card;
    }

    /** Item de timeline para movimiento */
    private static HBox buildTimelineItem(LoanMovementRepository.LoanMovement m, boolean dk) {
        String iconCode;
        String color;
        String label;

        switch (m.movementType()) {
            case LoanMovementRepository.MOV_TOPUP:
                iconCode = "fas-plus-circle";
                color = "#F59E0B";
                label = "Agregado";
                break;
            case LoanMovementRepository.MOV_PAYMENT_IN:
                iconCode = "fas-arrow-down";
                color = "#10B981";
                label = "Pago recibido";
                break;
            case LoanMovementRepository.MOV_PAYMENT_OUT:
                iconCode = "fas-arrow-up";
                color = "#3B82F6";
                label = "Pago realizado";
                break;
            case LoanMovementRepository.MOV_CLOSE:
                iconCode = "fas-check-circle";
                color = "#8B5CF6";
                label = "Cierre";
                break;
            case LoanMovementRepository.MOV_CREATION:
            default:
                iconCode = "fas-file-contract";
                color = "#64748B";
                label = "Creación";
                break;
        }

        FontIcon icon = new FontIcon(iconCode);
        icon.setIconSize(14);
        icon.setIconColor(Color.web(color));

        VBox iconBox = new VBox(icon);
        iconBox.setAlignment(Pos.CENTER);
        iconBox.setMinWidth(32);

        Label lblType = new Label(label);
        lblType.setStyle(
            "-fx-font-size: 13px; -fx-font-weight: 700; "
            + (dk ? "-fx-text-fill: #E2E8F0;" : "-fx-text-fill: #1E293B;")
        );

        String amountStr = DashboardFormatters.formatMoney(m.amountCents());
        Label lblAmount = new Label(amountStr);
        lblAmount.setWrapText(false);
        lblAmount.setStyle(
            "-fx-font-size: 12px; -fx-font-weight: 600; "
            + "-fx-text-fill: " + color + ";"
        );

        String dateStr = formatLoanDate(m.occurredAtEpochSec());
        Label lblDate = new Label(dateStr);
        lblDate.setStyle(
            "-fx-font-size: 11px; -fx-text-fill: " + (dk ? "#64748B" : "#94A3B8") + ";"
        );

        VBox center = new VBox(2, lblType, lblDate);
        center.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(center, Priority.ALWAYS);

        HBox row = new HBox(12, iconBox, center, lblAmount);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 14, 10, 14));
        row.setStyle(
            (dk
                ? "-fx-background-color: rgba(255,255,255,0.03); "
                : "-fx-background-color: #F8FAFC; ")
            + "-fx-background-radius: 10; -fx-border-radius: 10;"
        );

        return row;
    }

    private static Node buildLoanHistoryEmptyState(boolean dk, String message) {
        return buildEmptyState(
            new String[]{ "fas-history", "fas-clock", "fas-stream" },
            "#3B82F6",
            dk ? "rgba(59,130,246,0.12)" : "#EFF6FF",
            message,
            "",
            null,
            null,
            null,
            null,
            dk
        );
    }

    private static Node buildLoanHistoryItem(
        LoanService loanService,
        AuthSession session,
        String userUid,
        String loanId,
        LoanRepository.Loan loan,
        LoanMovementRepository.LoanMovement movement,
        boolean dk,
        ModalOverlay modalOverlay,
        Runnable refreshAll,
        Runnable refreshHistory,
        Runnable[] closeDrawerRef,
        Runnable reopenDrawer
    ) {
        String iconCode;
        String color;
        String label;

        switch (movement.movementType()) {
            case LoanMovementRepository.MOV_TOPUP:
                iconCode = "fas-plus-circle";
                color = "#F59E0B";
                label = "Agregado";
                break;
            case LoanMovementRepository.MOV_PAYMENT_IN:
                iconCode = "fas-arrow-down";
                color = "#10B981";
                label = "Pago recibido";
                break;
            case LoanMovementRepository.MOV_PAYMENT_OUT:
                iconCode = "fas-arrow-up";
                color = "#3B82F6";
                label = "Pago realizado";
                break;
            case LoanMovementRepository.MOV_CLOSE:
                iconCode = "fas-check-circle";
                color = "#8B5CF6";
                label = "Cierre";
                break;
            case LoanMovementRepository.MOV_CREATION:
            default:
                iconCode = "fas-file-contract";
                color = "#64748B";
                label = "Creación";
                break;
        }

        FontIcon icon = new FontIcon(iconCode);
        icon.setIconSize(14);
        icon.setIconColor(Color.web(color));

        VBox iconBox = new VBox(icon);
        iconBox.setAlignment(Pos.CENTER);
        iconBox.setMinWidth(32);

        Label lblType = new Label(label);
        lblType.setStyle(
            "-fx-font-size: 13px; -fx-font-weight: 700; "
            + (dk ? "-fx-text-fill: #E2E8F0;" : "-fx-text-fill: #1E293B;")
        );

        String amountStr = DashboardFormatters.formatMoney(movement.amountCents());
        Label lblAmount = new Label(amountStr);
        lblAmount.setStyle(
            "-fx-font-size: 12px; -fx-font-weight: 600; "
            + "-fx-text-fill: " + color + ";"
        );

        String dateStr = formatLoanDate(movement.occurredAtEpochSec());
        Label lblDate = new Label(dateStr);
        lblDate.setStyle(
            "-fx-font-size: 11px; -fx-text-fill: " + (dk ? "#64748B" : "#94A3B8") + ";"
        );

        VBox center = new VBox(2, lblType, lblDate);
        center.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(center, Priority.ALWAYS);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox right = new HBox(8);
        right.setAlignment(Pos.CENTER_RIGHT);
        right.getChildren().add(lblAmount);

        if (!LoanMovementRepository.MOV_CLOSE.equals(movement.movementType())) {
            FontIcon moreIcon = new FontIcon("fas-ellipsis-v");
            moreIcon.setIconSize(12);
            moreIcon.setIconColor(Color.web(dk ? "#94A3B8" : "#64748B"));

            Button actionsBtn = new Button();
            actionsBtn.setGraphic(moreIcon);
            actionsBtn.setStyle(
                "-fx-background-color: " + (dk ? "rgba(255,255,255,0.06)" : "#F8FAFC") + "; "
                + "-fx-border-color: " + (dk ? "rgba(148,163,184,0.20)" : "#E2E8F0") + "; "
                + "-fx-border-width: 1; -fx-border-radius: 8; -fx-background-radius: 8; "
                + "-fx-min-width: 30; -fx-min-height: 30; -fx-max-width: 30; -fx-max-height: 30; "
                + "-fx-cursor: hand; -fx-padding: 0;"
            );

            MenuItem editItem = new MenuItem(
                "Editar movimiento",
                buildLoanHistoryActionBadge(
                    "fas-edit",
                    dk ? "rgba(59,130,246,0.16)" : "#DBEAFE",
                    dk ? "#93C5FD" : "#2563EB"
                )
            );
            MenuItem deleteItem = new MenuItem(
                "Eliminar movimiento",
                buildLoanHistoryActionBadge(
                    "fas-trash-alt",
                    dk ? "rgba(239,68,68,0.16)" : "#FEE2E2",
                    dk ? "#FCA5A5" : "#DC2626"
                )
            );
            ContextMenu menu = new ContextMenu(editItem, deleteItem);
            menu.getStyleClass().add("loan-history-menu");
            editItem.getStyleClass().add("loan-history-menu-item");
            deleteItem.getStyleClass().addAll("loan-history-menu-item", "destructive");

            editItem.setOnAction(ev -> showMovementEditDialog(
                loanService, session, userUid, loan, movement, dk, modalOverlay, refreshAll, refreshHistory, closeDrawerRef, reopenDrawer
            ));

            deleteItem.setOnAction(ev -> showMovementDeleteConfirm(
                loanService, session, userUid, loan, movement, dk, modalOverlay, refreshAll, refreshHistory, closeDrawerRef, reopenDrawer
            ));

            actionsBtn.setOnAction(ev -> menu.show(actionsBtn, Side.BOTTOM, 0, 0));
            right.getChildren().add(actionsBtn);
        }

        HBox topRow = new HBox(12, iconBox, center, spacer, right);
        topRow.setAlignment(Pos.CENTER_LEFT);

        VBox item = new VBox(4, topRow);
        String note = movement.note();
        if (note != null && !note.isBlank()) {
            Label noteLabel = new Label(note);
            noteLabel.setWrapText(true);
            noteLabel.setStyle(
                "-fx-font-size: 11px; -fx-text-fill: " + (dk ? "#94A3B8" : "#64748B") + ";"
            );
            item.getChildren().add(noteLabel);
        }

        item.setPadding(new Insets(10, 14, 10, 14));
        item.setStyle(
            (dk
                ? "-fx-background-color: rgba(255,255,255,0.03); "
                : "-fx-background-color: #F8FAFC; ")
            + "-fx-background-radius: 10; -fx-border-radius: 10;"
        );

        return item;
    }

    private static StackPane buildLoanHistoryActionBadge(String iconCode, String bgColor, String iconColor) {
        FontIcon icon = new FontIcon(iconCode);
        icon.setIconSize(12);
        icon.setIconColor(Color.web(iconColor));

        StackPane badge = new StackPane(icon);
        badge.setMinSize(26, 26);
        badge.setPrefSize(26, 26);
        badge.setMaxSize(26, 26);
        badge.setAlignment(Pos.CENTER);
        badge.setStyle(
            "-fx-background-color: " + bgColor + "; "
            + "-fx-background-radius: 999; "
            + "-fx-border-color: rgba(255,255,255,0.20); "
            + "-fx-border-width: 1; "
            + "-fx-border-radius: 999;"
        );
        return badge;
    }

    private static void showMovementEditDialog(
        LoanService loanService,
        AuthSession session,
        String userUid,
        LoanRepository.Loan loan,
        LoanMovementRepository.LoanMovement movement,
        boolean dk,
        ModalOverlay modalOverlay,
        Runnable refreshAll,
        Runnable refreshHistory,
        Runnable[] closeDrawerRef,
        Runnable reopenDrawer
    ) {
        if (LoanMovementRepository.MOV_CLOSE.equals(movement.movementType())) return;

        HBox header = modalOverlay.buildHeader("Editar movimiento",
            "Modifica los detalles del movimiento seleccionado");

        Label accountLabel = ModalOverlay.fieldLabel("Cuenta", "fas-wallet");
        ComboBox<AccountRepository.Account> accountCombo = new ComboBox<>();
        accountCombo.setPromptText("Seleccionar cuenta");
        accountCombo.setMaxWidth(Double.MAX_VALUE);
        accountCombo.getStyleClass().add("account-combo");
        accountCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AccountRepository.Account a) {
                return a == null ? "" : a.name() + " · " + a.currency();
            }

            @Override
            public AccountRepository.Account fromString(String s) { return null; }
        });
        accountCombo.setCellFactory(accountCellFactory());
        accountCombo.setButtonCell(accountCellFactory().call(null));

        try {
            List<AccountRepository.Account> accounts = loanService.listAccounts(userUid);
            accountCombo.setItems(FXCollections.observableArrayList(accounts));
            accountCombo.getItems().stream()
                .filter(a -> a.id().equals(Optional.ofNullable(movement.accountId()).orElse(loan.accountId())))
                .findFirst()
                .ifPresentOrElse(accountCombo.getSelectionModel()::select, accountCombo.getSelectionModel()::selectFirst);
        } catch (Exception ignored) {}

        VBox accountBlock = new VBox(6, accountLabel, accountCombo);

        Label amountLabel = ModalOverlay.fieldLabel("Monto", "fas-dollar-sign");
        TextField amountField = new TextField();
        amountField.setPromptText("Ej: 1000.00");
        amountField.getStyleClass().add("modal-text-input");
        amountField.setText(String.valueOf(movement.amountCents() / 100.0));
        UiDialogs.restrictToDecimalAmount(amountField);

        Label amountError = new Label();
        amountError.setStyle("-fx-text-fill: #DC2626; -fx-font-size: 11px;");
        amountError.setVisible(false);
        amountError.setManaged(false);

        VBox amountBlock = new VBox(6, amountLabel, amountField, amountError);

        Label dateLabel = ModalOverlay.fieldLabel("Fecha", "fas-calendar-alt");
        DatePicker datePicker = new DatePicker(LocalDate.ofInstant(Instant.ofEpochSecond(movement.occurredAtEpochSec()), ZoneId.systemDefault()));
        datePicker.setMaxWidth(Double.MAX_VALUE);
        datePicker.getStyleClass().add("modal-text-input");

        VBox dateBlock = new VBox(6, dateLabel, datePicker);

        Label noteLabel = ModalOverlay.fieldLabel("Nota (opcional)", "fas-sticky-note");
        TextArea noteField = new TextArea();
        noteField.setPromptText("Agrega una descripción...");
        noteField.getStyleClass().add("modal-text-input");
        noteField.setPrefRowCount(3);
        noteField.setWrapText(true);
        noteField.setText(movement.note() == null ? "" : movement.note());

        VBox noteBlock = new VBox(6, noteLabel, noteField);

        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: #DC2626; -fx-font-size: 12px;");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        VBox footer = modalOverlay.buildFooter("Guardar cambios", "fas-save", "#2563EB", "#1D4ED8");
        Button primaryBtn = (Button) footer.getChildren().get(1);
        Button cancelBtn = (Button) footer.getChildren().get(2);

        primaryBtn.setOnAction(e -> {
            errorLabel.setText("");
            errorLabel.setVisible(false);
            errorLabel.setManaged(false);
            amountError.setText("");
            amountError.setVisible(false);
            amountError.setManaged(false);

            AccountRepository.Account account = accountCombo.getValue();
            if (account == null) {
                errorLabel.setText("Selecciona una cuenta");
                errorLabel.setVisible(true);
                errorLabel.setManaged(true);
                return;
            }

            long cents;
            try {
                BigDecimal v = DashboardFormatters.parseAmount(amountField.getText());
                cents = v.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
            } catch (Exception ex) {
                amountError.setText("Ingresa un monto válido");
                amountError.setVisible(true);
                amountError.setManaged(true);
                return;
            }

            if (cents <= 0) {
                amountError.setText("El monto debe ser mayor a 0");
                amountError.setVisible(true);
                amountError.setManaged(true);
                return;
            }

            LocalDate date = datePicker.getValue();
            if (date == null) {
                errorLabel.setText("Selecciona una fecha");
                errorLabel.setVisible(true);
                errorLabel.setManaged(true);
                return;
            }

            String note = noteField.getText() == null ? null : noteField.getText().trim();
            if (note != null && note.isBlank()) {
                note = null;
            }

            try {
                long occurred = date.atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
                loanService.updateMovement(userUid, session, movement.id(), account.id(), cents, occurred, note);
                modalOverlay.hide();
                if (refreshAll != null) refreshAll.run();
                if (refreshHistory != null) refreshHistory.run();
                // Reopen drawer after successful edit
                if (reopenDrawer != null) {
                    reopenDrawer.run();
                }
            } catch (Exception ex) {
                errorLabel.setText(ex.getMessage() != null ? ex.getMessage() : "No se pudo guardar el movimiento");
                errorLabel.setVisible(true);
                errorLabel.setManaged(true);
            }
        });

        cancelBtn.setOnAction(e -> {
            modalOverlay.hide();
            if (closeDrawerRef != null && closeDrawerRef[0] != null) {
                closeDrawerRef[0].run();
            }
        });

        modalOverlay.register("edit-movement", 460,
            header, accountBlock, amountBlock, dateBlock, noteBlock, errorLabel, footer);
        
        // Cerrar drawer antes de mostrar el modal
        if (closeDrawerRef != null && closeDrawerRef[0] != null) {
            closeDrawerRef[0].run();
        }
        modalOverlay.show("edit-movement");
    }

    private static void showMovementDeleteConfirm(
        LoanService loanService,
        AuthSession session,
        String userUid,
        LoanRepository.Loan loan,
        LoanMovementRepository.LoanMovement movement,
        boolean dk,
        ModalOverlay modalOverlay,
        Runnable refreshAll,
        Runnable refreshHistory,
        Runnable[] closeDrawerRef,
        Runnable reopenDrawer
    ) {
        // Build modern header with icon
        HBox header = modalOverlay.buildHeader("Eliminar movimiento",
            "El préstamo se recalculará automáticamente.");

        // Icon warning
        FontIcon warningIcon = new FontIcon("fas-exclamation-triangle");
        warningIcon.setIconSize(48);
        warningIcon.setIconColor(Color.web("#F59E0B"));
        StackPane iconContainer = new StackPane(warningIcon);
        iconContainer.setStyle(
            "-fx-background-color: rgba(245,158,11,0.12); "
            + "-fx-background-radius: 50; "
            + "-fx-min-width: 80; -fx-min-height: 80; "
            + "-fx-max-width: 80; -fx-max-height: 80;"
        );
        iconContainer.setAlignment(Pos.CENTER);

        // Confirmation message
        Label confirmLabel = new Label("¿Eliminar este movimiento de " + loan.counterpartyName() + "?");
        confirmLabel.setStyle(
            "-fx-font-size: 16px; -fx-font-weight: 700; "
            + (dk ? "-fx-text-fill: #E2E8F0;" : "-fx-text-fill: #1E293B;")
        );
        confirmLabel.setWrapText(true);

        Label detailLabel = new Label(
            "Monto: " + DashboardFormatters.formatMoney(movement.amountCents()) + "\n" +
            "Tipo: " + (LoanMovementRepository.MOV_CREATION.equals(movement.movementType()) || LoanMovementRepository.MOV_TOPUP.equals(movement.movementType()) ? "Préstamo" : "Pago")
        );
        detailLabel.setStyle(
            "-fx-font-size: 13px; -fx-font-weight: 500; "
            + (dk ? "-fx-text-fill: #94A3B8;" : "-fx-text-fill: #64748B;")
        );
        detailLabel.setWrapText(true);

        VBox messageBox = new VBox(12, confirmLabel, detailLabel);
        messageBox.setAlignment(Pos.CENTER);
        messageBox.setPadding(new Insets(16, 0, 16, 0));

        VBox contentBox = new VBox(20, iconContainer, messageBox);
        contentBox.setAlignment(Pos.CENTER);
        contentBox.setPadding(new Insets(8, 0, 8, 0));

        // Error label
        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: #DC2626; -fx-font-size: 12px;");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        errorLabel.setWrapText(true);

        // Footer with danger button
        VBox footer = modalOverlay.buildFooter("Eliminar", "fas-trash-alt", "#DC2626", "#B91C1C");
        Button deleteBtn = (Button) footer.getChildren().get(1);
        Button cancelBtn = (Button) footer.getChildren().get(2);

        // Ensure button shows full text without truncation
        deleteBtn.setMinWidth(100);
        deleteBtn.setPrefWidth(100);
        deleteBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(deleteBtn, Priority.ALWAYS);

        deleteBtn.setOnAction(e -> {
            errorLabel.setText("");
            errorLabel.setVisible(false);
            errorLabel.setManaged(false);

            try {
                LoanService.MovementMutationResult result = loanService.deleteMovement(userUid, session, movement.id());
                modalOverlay.hide();
                if (refreshAll != null) refreshAll.run();
                if (result.loanDeleted()) {
                    if (closeDrawerRef != null && closeDrawerRef[0] != null) {
                        closeDrawerRef[0].run();
                    }
                } else if (refreshHistory != null) {
                    refreshHistory.run();
                    // Reopen drawer after successful deletion if loan still exists
                    if (reopenDrawer != null) {
                        reopenDrawer.run();
                    }
                }
            } catch (Exception ex) {
                errorLabel.setText(ex.getMessage() != null ? ex.getMessage() : "No se pudo eliminar el movimiento");
                errorLabel.setVisible(true);
                errorLabel.setManaged(true);
            }
        });

        cancelBtn.setOnAction(e -> {
            modalOverlay.hide();
            if (reopenDrawer != null) {
                reopenDrawer.run();
            }
        });

        modalOverlay.register("delete-movement", 420,
            header, contentBox, errorLabel, footer);

        // Cerrar drawer antes de mostrar el modal
        if (closeDrawerRef != null && closeDrawerRef[0] != null) {
            closeDrawerRef[0].run();
        }
        modalOverlay.show("delete-movement");
    }

    // ── Método para mostrar historial completo de movimientos de préstamo ─────
    private static void showFullLoanHistoryDrawer(
        VBox drawer,
        List<Node> mainSnapshot,
        LoanService loanService,
        AuthSession session,
        String userUid,
        String loanId,
        boolean dk,
        ModalOverlay modalOverlay,
        Runnable refreshAll,
        Runnable[] closeDrawerRef
    ) {
        if (drawer == null || loanService == null || session == null || userUid == null || loanId == null) return;

        LoanRepository.Loan loan;
        List<LoanMovementRepository.LoanMovement> allMovements;
        try {
            loan = loanService.getLoan(userUid, loanId);
            if (loan == null) {
                drawer.getChildren().setAll(buildLoanHistoryEmptyState(dk, "El préstamo ya no existe"));
                return;
            }
            allMovements = loanService.listMovements(userUid, loanId);
            allMovements.sort((a, b) -> {
                int cmp = Long.compare(b.occurredAtEpochSec(), a.occurredAtEpochSec());
                if (cmp != 0) return cmp;
                return Long.compare(b.createdAtEpochSec(), a.createdAtEpochSec());
            });
        } catch (Exception ex) {
            drawer.getChildren().setAll(buildLoanHistoryEmptyState(dk, "No se pudo cargar el historial"));
            return;
        }

        String titleColor = dk ? "#E5E7EB" : "#0F172A";
        String subtitleColor = dk ? "#94A3B8" : "#64748B";
        String dividerColor = dk ? "rgba(255,255,255,0.10)" : "#E2E8F0";

        List<Node> snapshot = mainSnapshot == null ? List.of() : new ArrayList<>(mainSnapshot);

        drawer.getChildren().clear();

        FontIcon backIcon = new FontIcon("fas-arrow-left");
        backIcon.setIconSize(14);
        backIcon.setIconColor(Color.web(dk ? "#94A3B8" : "#64748B"));
        Button btnBack = new Button();
        btnBack.setGraphic(backIcon);
        btnBack.setText("Volver");
        btnBack.setStyle(
            "-fx-background-color: transparent; "
            + "-fx-text-fill: " + (dk ? "#94A3B8" : "#64748B") + "; "
            + "-fx-font-size: 13px; -fx-font-weight: 600; "
            + "-fx-cursor: hand; -fx-padding: 6 12;");
        btnBack.setOnAction(e -> {
            if (closeDrawerRef != null && closeDrawerRef[0] != null) {
                StackPane root = null;
                if (drawer.getScene() != null && drawer.getScene().getRoot() instanceof StackPane sp) {
                    root = sp;
                }
                closeDrawerRef[0].run();
                StackPane rootRef = root;
                Timeline reopenMain = new Timeline(new KeyFrame(Duration.millis(310), ev -> {
                    try {
                        if (rootRef != null) {
                            showLoanDetailDrawer(rootRef, loanService, userUid, loanId, dk, session, modalOverlay, refreshAll);
                        }
                    } catch (Exception ignored) {
                    }
                }));
                reopenMain.play();
            } else {
                drawer.getChildren().setAll(snapshot);
            }
        });

        Label historyTitle = new Label("Historial de movimientos");
        historyTitle.setStyle("-fx-font-size: 18px; -fx-font-weight: 800; -fx-text-fill: " + titleColor + ";");

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox headerRow = new HBox(12, btnBack, headerSpacer, historyTitle);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        Region divider = new Region();
        divider.setPrefHeight(1);
        divider.setMaxHeight(1);
        divider.setStyle("-fx-background-color: " + dividerColor + ";");
        divider.setMaxWidth(Double.MAX_VALUE);

        Label loanNameLabel = new Label(loan.counterpartyName());
        loanNameLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: 700; -fx-text-fill: " + titleColor + ";");
        VBox loanInfoBox = new VBox(4, loanNameLabel);
        loanInfoBox.setPadding(new Insets(12, 0, 0, 0));

        VBox movementsList = new VBox(8);
        movementsList.setPadding(new Insets(8, 0, 0, 0));

        final Runnable[] refreshHistoryRef = { null };
        refreshHistoryRef[0] = () -> showFullLoanHistoryDrawer(
            drawer, snapshot, loanService, session, userUid, loanId, dk, modalOverlay, refreshAll, closeDrawerRef
        );

        // Create reopenDrawer runnable for full history drawer
        Runnable reopenDrawer = () -> {
            if (closeDrawerRef != null && closeDrawerRef[0] != null) {
                StackPane root = null;
                if (drawer.getScene() != null && drawer.getScene().getRoot() instanceof StackPane sp) {
                    root = sp;
                }
                closeDrawerRef[0].run();
                StackPane rootRef = root;
                Timeline reopenMain = new Timeline(new KeyFrame(Duration.millis(310), ev -> {
                    try {
                        if (rootRef != null) {
                            showLoanDetailDrawer(rootRef, loanService, userUid, loanId, dk, session, modalOverlay, refreshAll);
                        }
                    } catch (Exception ignored) {
                    }
                }));
                reopenMain.play();
            } else {
                drawer.getChildren().setAll(snapshot);
            }
        };

        if (allMovements.isEmpty()) {
            movementsList.getChildren().add(buildLoanHistoryEmptyState(dk, "No hay movimientos registrados"));
        } else {
            for (LoanMovementRepository.LoanMovement movement : allMovements) {
                movementsList.getChildren().add(buildLoanHistoryItem(
                    loanService,
                    session,
                    userUid,
                    loanId,
                    loan,
                    movement,
                    dk,
                    modalOverlay,
                    refreshAll,
                    refreshHistoryRef[0],
                    closeDrawerRef,
                    reopenDrawer
                ));
            }
        }

        ScrollPane scroll = new ScrollPane(movementsList);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        scroll.setPadding(new Insets(0));
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox content = new VBox(12, headerRow, divider, loanInfoBox, scroll);
        content.setPadding(new Insets(14));
        content.setFillWidth(true);

        drawer.getChildren().add(content);
    }
}
