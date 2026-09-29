package com.myfinaces.ui;

import com.myfinaces.auth.AuthSession;
import com.myfinaces.config.AccountStyles;
import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.ObligationRepository;
import com.myfinaces.db.ObligationSettlementRepository;
import com.myfinaces.db.TransactionRepository;
import com.myfinaces.service.ObligationService;
import com.myfinaces.service.ObligationService.ObligationResolvedStatus;
import com.myfinaces.service.ObligationService.ResolvedObligationState;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javafx.animation.Interpolator;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Vista del módulo Obligaciones (Cuentas por Cobrar / Cuentas por Pagar).
 *
 * La UI solo representa el dominio implementado en ObligationService:
 * - crear/editar/cancelar obligaciones nunca crea Transactions;
 * - registrar un abono delega en ObligationService.registerSettlement
 *   (Transaction + Settlement atómicos);
 * - editar/eliminar settlement delega en el servicio (misma Transaction);
 * - la UI jamás toca Transactions ni Firebase directamente.
 */
public final class ObligationsView {

    private ObligationsView() {
    }

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public static Node buildObligationsView(
        AuthSession session,
        ObligationRepository obligationRepo,
        ObligationSettlementRepository settlementRepo,
        TransactionRepository txRepo,
        AccountRepository accountRepo,
        CategoryRepository categoryRepo,
        Supplier<Boolean> darkTheme,
        Runnable refreshBalances
    ) {
        ObligationService obligationService = new ObligationService(
            obligationRepo.database(), obligationRepo, settlementRepo, txRepo
        );
        String userUid = session.uid();
        boolean dk = Boolean.TRUE.equals(darkTheme.get());

        // ── Header ─────────────────────────────────────────────────
        Label title = new Label("Obligaciones");
        title.getStyleClass().add("app-title");
        Label subtitle = new Label("Gestiona cuentas por cobrar y cuentas por pagar");
        subtitle.getStyleClass().add("text-secondary");
        VBox titleBox = new VBox(2, title, subtitle);
        titleBox.setAlignment(Pos.CENTER_LEFT);

        Button btnNew = new Button("Nueva obligación");
        btnNew.getStyleClass().addAll("btn-primary", "nav-button");
        FontIcon newIcon = new FontIcon("fas-plus");
        newIcon.setIconSize(12);
        btnNew.setGraphic(newIcon);
        btnNew.setGraphicTextGap(6);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox topBar = new HBox(12, titleBox, spacer, btnNew);
        topBar.setAlignment(Pos.CENTER_LEFT);

        // ── Pills por tipo ─────────────────────────────────────────
        Button pillReceivable = createPill("Por cobrar", "fas-hand-holding-usd");
        Button pillPayable = createPill("Por pagar", "fas-file-invoice-dollar");
        Button[] allPills = { pillReceivable, pillPayable };
        HBox pillsBar = new HBox(10, pillReceivable, pillPayable);
        pillsBar.setAlignment(Pos.CENTER_LEFT);

        // ── Overlay + contenido ────────────────────────────────────
        ModalOverlay modalOverlay = new ModalOverlay();
        VBox contentContainer = new VBox(14);
        contentContainer.setFillWidth(true);

        Node[] heroCard = { buildHeroCard(obligationRepo, obligationService, userUid, dk) };
        String[] activeTab = { ObligationRepository.TYPE_RECEIVABLE };

        Runnable[] refreshContent = { null };
        Runnable[] refreshAll = { () -> {
            if (refreshContent[0] != null) refreshContent[0].run();
            try { refreshBalances.run(); } catch (Exception ignored) {}
        }};

        refreshContent[0] = () -> {
            contentContainer.getChildren().clear();
            try {
                List<ObligationRepository.Obligation> all = obligationRepo.listAllByUser(userUid);
                List<ObligationRepository.Obligation> filtered = new ArrayList<>();
                for (ObligationRepository.Obligation o : all) {
                    if (activeTab[0].equals(o.type())) filtered.add(o);
                }

                Map<String, ResolvedObligationState> states = new HashMap<>();
                for (ObligationRepository.Obligation o : filtered) {
                    states.put(o.id(), obligationService.getResolvedState(userUid, o.id(), nowSec()));
                }
                List<List<ObligationRepository.Obligation>> parts =
                    partitionByCancellation(filtered, states::get);
                List<ObligationRepository.Obligation> active = parts.get(0);
                List<ObligationRepository.Obligation> cancelled = parts.get(1);

                if (filtered.isEmpty()) {
                    contentContainer.getChildren().setAll(buildEmptyState(activeTab[0], dk));
                    return;
                }

                Label activeTitle = new Label("Activas");
                activeTitle.setStyle(
                    "-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: "
                        + (dk ? "rgba(229,231,235,0.60)" : "#64748B") + ";");
                contentContainer.getChildren().add(activeTitle);

                for (ObligationRepository.Obligation obligation : active) {
                    Node card = buildObligationCard(
                        obligation, states.get(obligation.id()), dk,
                        () -> showObligationDetail(
                            modalOverlay, obligationService, obligationRepo,
                            settlementRepo, txRepo, accountRepo, categoryRepo,
                            userUid, obligation.id(), darkTheme, refreshAll[0]
                        )
                    );
                    contentContainer.getChildren().add(card);
                }

                // Sección "Canceladas" — mismo patrón que Metas → Archivadas
                // (BudgetView): título clickable, contraída por defecto.
                if (!cancelled.isEmpty()) {
                    VBox cancelledRows = new VBox(14);
                    cancelledRows.setVisible(false);
                    cancelledRows.setManaged(false);
                    for (ObligationRepository.Obligation obligation : cancelled) {
                        cancelledRows.getChildren().add(buildObligationCard(
                            obligation, states.get(obligation.id()), dk,
                            () -> showObligationDetail(
                                modalOverlay, obligationService, obligationRepo,
                                settlementRepo, txRepo, accountRepo, categoryRepo,
                                userUid, obligation.id(), darkTheme, refreshAll[0]
                            )
                        ));
                    }

                    final int cancelledCount = cancelled.size();
                    Label cancelledTitle = new Label("Canceladas (" + cancelledCount + ") ▸");
                    cancelledTitle.setStyle(
                        "-fx-font-size: 13px; -fx-font-weight: 700; -fx-cursor: hand; -fx-text-fill: "
                            + (dk ? "rgba(229,231,235,0.60)" : "#64748B") + ";");
                    cancelledTitle.setOnMouseClicked(ev -> {
                        boolean show = !cancelledRows.isVisible();
                        cancelledRows.setVisible(show);
                        cancelledRows.setManaged(show);
                        cancelledTitle.setText("Canceladas (" + cancelledCount + ") " + (show ? "▾" : "▸"));
                    });
                    contentContainer.getChildren().addAll(cancelledTitle, cancelledRows);
                }
            } catch (Exception ex) {
                Label err = new Label("Error al cargar obligaciones");
                err.setStyle("-fx-text-fill: #DC2626; -fx-font-size: 12px;");
                contentContainer.getChildren().setAll(err);
            }
        };
        refreshContent[0].run();

        pillReceivable.setOnAction(e -> {
            for (Button p : allPills) applyPillInactive(p, dk);
            applyPillActive(pillReceivable, dk);
            activeTab[0] = ObligationRepository.TYPE_RECEIVABLE;
            refreshContent[0].run();
        });
        pillPayable.setOnAction(e -> {
            for (Button p : allPills) applyPillInactive(p, dk);
            applyPillActive(pillPayable, dk);
            activeTab[0] = ObligationRepository.TYPE_PAYABLE;
            refreshContent[0].run();
        });
        applyPillActive(pillReceivable, dk);
        applyPillInactive(pillPayable, dk);

        ScrollPane scroll = new ScrollPane(contentContainer);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        scroll.getStyleClass().addAll("edge-to-edge", "new-tx-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox root = new VBox(16, topBar, heroCard[0], pillsBar, scroll);
        root.getStyleClass().add("content");
        root.setPadding(new Insets(24));
        root.setFillWidth(true);
        VBox.setVgrow(root, Priority.ALWAYS);

        refreshAll[0] = () -> {
            Node newHero = buildHeroCard(obligationRepo, obligationService, userUid, dk);
            int idx = root.getChildren().indexOf(heroCard[0]);
            if (idx >= 0) {
                root.getChildren().set(idx, newHero);
                heroCard[0] = newHero;
            }
            refreshContent[0].run();
            try { refreshBalances.run(); } catch (Exception ignored) {}
        };

        btnNew.setOnAction(e -> showObligationForm(
            modalOverlay, obligationService, categoryRepo, userUid, null, darkTheme, refreshAll[0]
        ));

        return modalOverlay.wrapContent(root);
    }

    // ══════════════════════════════════════════════════════════════
    //  Hero summary: Por cobrar / Por pagar / Vencido (pendientes)
    // ══════════════════════════════════════════════════════════════
    private static Node buildHeroCard(
        ObligationRepository obligationRepo,
        ObligationService obligationService,
        String userUid,
        boolean dk
    ) {
        long receivableCents = 0L;
        long payableCents = 0L;
        long overdueCents = 0L;
        try {
            for (ObligationRepository.Obligation o : obligationRepo.listAllByUser(userUid)) {
                ResolvedObligationState state =
                    obligationService.getResolvedState(userUid, o.id(), nowSec());
                if (state.status() == ObligationResolvedStatus.CANCELADA) continue;
                if (ObligationRepository.TYPE_RECEIVABLE.equals(o.type())) {
                    receivableCents += state.pendingAmountCents();
                } else {
                    payableCents += state.pendingAmountCents();
                }
                if (state.status() == ObligationResolvedStatus.VENCIDA) {
                    overdueCents += state.pendingAmountCents();
                }
            }
        } catch (Exception ignored) {
        }

        Node mReceivable = buildCardMetric("Por cobrar", DashboardFormatters.formatMoney(receivableCents), "#16A34A", dk);
        Node mPayable = buildCardMetric("Por pagar", DashboardFormatters.formatMoney(payableCents), "#2563EB", dk);
        Node mOverdue = buildCardMetric("Vencido", DashboardFormatters.formatMoney(overdueCents), "#DC2626", dk);

        HBox metrics = new HBox(28, mReceivable, cardSeparator(dk), mPayable, cardSeparator(dk), mOverdue);
        metrics.setAlignment(Pos.CENTER_LEFT);

        StackPane card = new StackPane(metrics);
        card.setPadding(new Insets(18, 24, 18, 24));
        card.setStyle(
            "-fx-background-color: " + (dk ? "rgba(30,41,59,0.85)" : "#FFFFFF") + "; "
            + "-fx-background-radius: 16; "
            + "-fx-border-color: " + (dk ? "rgba(255,255,255,0.08)" : "#E2E8F0") + "; "
            + "-fx-border-radius: 16; -fx-border-width: 1;"
        );
        return card;
    }

    // ══════════════════════════════════════════════════════════════
    //  Card de obligación
    // ══════════════════════════════════════════════════════════════
    private static Node buildObligationCard(
        ObligationRepository.Obligation obligation,
        ResolvedObligationState state,
        boolean dk,
        Runnable onOpenDetail
    ) {
        boolean receivable = ObligationRepository.TYPE_RECEIVABLE.equals(obligation.type());
        String accent = receivable ? "#16A34A" : "#2563EB";

        // Avatar: iniciales de contraparte
        Label avatar = new Label(buildInitials(obligation.counterpartyName()));
        avatar.setStyle(
            "-fx-background-color: " + accent + "; -fx-background-radius: 50; "
            + "-fx-text-fill: white; -fx-font-weight: 800; -fx-font-size: 14px; "
            + "-fx-min-width: 44; -fx-min-height: 44; -fx-max-width: 44; -fx-max-height: 44; "
            + "-fx-alignment: center;"
        );

        Label counterparty = new Label(obligation.counterpartyName());
        counterparty.setStyle("-fx-font-size: 15px; -fx-font-weight: 800;" + (dk ? " -fx-text-fill: #E5E7EB;" : ""));

        Label titleLbl = new Label(obligation.title());
        titleLbl.setStyle("-fx-font-size: 12px; -fx-font-weight: 600; -fx-text-fill: " + (dk ? "rgba(229,231,235,0.60)" : "#64748B") + ";");

        FontIcon typeIcon = new FontIcon(receivable ? "fas-arrow-down" : "fas-arrow-up");
        typeIcon.setIconSize(10);
        typeIcon.setIconColor(Color.web(accent));
        Label typeLabel = new Label(receivable ? "Por cobrar" : "Por pagar");
        typeLabel.setStyle("-fx-font-size: 10px; -fx-font-weight: 800; -fx-text-fill: " + accent + ";");
        HBox typeRow = new HBox(4, typeIcon, typeLabel);
        typeRow.setAlignment(Pos.CENTER_LEFT);

        VBox info = new VBox(3, counterparty, titleLbl, typeRow);
        info.setAlignment(Pos.CENTER_LEFT);
        HBox left = new HBox(12, avatar, info);
        left.setAlignment(Pos.CENTER_LEFT);
        left.setMinWidth(230);

        // Métricas centrales
        Node mOriginal = buildCardMetric("Original", DashboardFormatters.formatMoney(obligation.originalAmountCents()), null, dk);
        Node mSettled = buildCardMetric("Abonado", DashboardFormatters.formatMoney(state.totalSettledCents()), "#16A34A", dk);
        Node mPending = buildCardMetric("Pendiente", DashboardFormatters.formatMoney(state.pendingAmountCents()), "#EA580C", dk);
        HBox metrics = new HBox(24, mOriginal, mSettled, mPending);
        metrics.setAlignment(Pos.CENTER_LEFT);

        // Progreso
        double pct = obligation.originalAmountCents() > 0
            ? Math.min(100.0, (state.totalSettledCents() * 100.0) / obligation.originalAmountCents())
            : 0.0;
        Region track = new Region();
        track.setPrefHeight(6);
        track.setMaxHeight(6);
        track.setStyle("-fx-background-color: " + (dk ? "rgba(255,255,255,0.10)" : "#E2E8F0") + "; -fx-background-radius: 3;");
        Region fill = new Region();
        fill.setPrefHeight(6);
        fill.setMaxHeight(6);
        fill.setPrefWidth(Math.max(8, pct * 2.4));
        fill.setStyle("-fx-background-color: " + accent + "; -fx-background-radius: 3;");
        StackPane bar = new StackPane(track);
        if (pct > 0) {
            bar.getChildren().add(fill);
            StackPane.setAlignment(fill, Pos.CENTER_LEFT);
        }
        bar.setMaxWidth(240);

        // Fecha vencimiento
        Label due = new Label(
            obligation.dueAtEpochSec() != null
                ? "Vence: " + formatDate(obligation.dueAtEpochSec())
                : "Sin vencimiento"
        );
        due.setStyle("-fx-font-size: 11px; -fx-font-weight: 600; -fx-text-fill: " + (dk ? "rgba(229,231,235,0.55)" : "#94A3B8") + ";");

        VBox center = new VBox(8, metrics, bar, due);
        center.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(center, Priority.ALWAYS);

        // Estado
        Label badge = statusBadge(state.status());

        Region rightSpacer = new Region();
        HBox.setHgrow(rightSpacer, Priority.ALWAYS);
        FontIcon chevron = new FontIcon("fas-chevron-right");
        chevron.setIconSize(12);
        chevron.setIconColor(Color.web(dk ? "rgba(229,231,235,0.40)" : "#94A3B8"));

        HBox row = new HBox(16, left, cardSeparator(dk), center, rightSpacer, badge, chevron);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(16, 20, 16, 20));
        row.setStyle(
            "-fx-background-color: " + (dk ? "rgba(30,41,59,0.85)" : "#FFFFFF") + "; "
            + "-fx-background-radius: 14; "
            + "-fx-border-color: " + (dk ? "rgba(255,255,255,0.08)" : "#E2E8F0") + "; "
            + "-fx-border-radius: 14; -fx-border-width: 1; "
            + "-fx-cursor: hand;"
        );
        row.setOnMouseClicked(e -> onOpenDetail.run());
        return row;
    }

    private static Node buildEmptyState(String type, boolean dk) {
        boolean receivable = ObligationRepository.TYPE_RECEIVABLE.equals(type);
        FontIcon icon = new FontIcon(receivable ? "fas-hand-holding-usd" : "fas-file-invoice-dollar");
        icon.setIconSize(34);
        icon.setIconColor(Color.web(dk ? "rgba(229,231,235,0.35)" : "#CBD5E1"));
        Label lbl = new Label(receivable ? "No hay cuentas por cobrar" : "No hay cuentas por pagar");
        lbl.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: " + (dk ? "rgba(229,231,235,0.55)" : "#94A3B8") + ";");
        Label hint = new Label("Usa \"Nueva obligación\" para registrar la primera");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: " + (dk ? "rgba(229,231,235,0.40)" : "#CBD5E1") + ";");
        VBox box = new VBox(10, icon, lbl, hint);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(50, 20, 50, 20));
        return box;
    }

    // ══════════════════════════════════════════════════════════════
    //  Modal: Detalle de obligación + historial de settlements
    // ══════════════════════════════════════════════════════════════
    private static void showObligationDetail(
        ModalOverlay overlay,
        ObligationService obligationService,
        ObligationRepository obligationRepo,
        ObligationSettlementRepository settlementRepo,
        TransactionRepository txRepo,
        AccountRepository accountRepo,
        CategoryRepository categoryRepo,
        String userUid,
        String obligationId,
        Supplier<Boolean> darkTheme,
        Runnable refreshAll
    ) {
        boolean dk = Boolean.TRUE.equals(darkTheme.get());
        try {
            ObligationRepository.Obligation obligation = obligationRepo.getByIdOrNull(userUid, obligationId);
            if (obligation == null) return;
            ResolvedObligationState state = obligationService.getResolvedState(userUid, obligationId, nowSec());
            boolean receivable = ObligationRepository.TYPE_RECEIVABLE.equals(obligation.type());

            HBox header = overlay.buildHeader(obligation.title(), obligation.counterpartyName());

            // Estado + tipo
            Label badge = statusBadge(state.status());
            Label typeLbl = new Label(receivable ? "Por cobrar" : "Por pagar");
            typeLbl.setStyle("-fx-font-size: 11px; -fx-font-weight: 800; -fx-text-fill: " + (receivable ? "#16A34A" : "#2563EB") + ";");
            HBox stateRow = new HBox(10, badge, typeLbl);
            stateRow.setAlignment(Pos.CENTER_LEFT);

            // Info grid
            Node iOriginal = buildCardMetric("Monto original", DashboardFormatters.formatMoney(obligation.originalAmountCents()), null, dk);
            Node iSettled = buildCardMetric("Abonado", DashboardFormatters.formatMoney(state.totalSettledCents()), "#16A34A", dk);
            Node iPending = buildCardMetric("Pendiente", DashboardFormatters.formatMoney(state.pendingAmountCents()), "#EA580C", dk);
            HBox infoRow1 = new HBox(24, iOriginal, iSettled, iPending);
            infoRow1.setAlignment(Pos.CENTER_LEFT);

            String categoryName = "—";
            if (obligation.obligationCategoryId() != null) {
                try {
                    for (CategoryRepository.Category c : categoryRepo.listAll(userUid)) {
                        if (c.id().equals(obligation.obligationCategoryId())) { categoryName = c.name(); break; }
                    }
                } catch (Exception ignored) {}
            }
            Node iIssued = buildCardMetric("Emitida", formatDate(obligation.issuedAtEpochSec()), null, dk);
            Node iDue = buildCardMetric("Vencimiento",
                obligation.dueAtEpochSec() != null ? formatDate(obligation.dueAtEpochSec()) : "—", null, dk);
            Node iCategory = buildCardMetric("Categoría", categoryName, null, dk);
            HBox infoRow2 = new HBox(24, iIssued, iDue, iCategory);
            infoRow2.setAlignment(Pos.CENTER_LEFT);

            VBox infoBox = new VBox(14, infoRow1, infoRow2);

            if (obligation.reference() != null || obligation.notes() != null) {
                StringBuilder extra = new StringBuilder();
                if (obligation.reference() != null) extra.append("Ref: ").append(obligation.reference());
                if (obligation.notes() != null) {
                    if (extra.length() > 0) extra.append("   ·   ");
                    extra.append(obligation.notes());
                }
                Label extraLbl = new Label(extra.toString());
                extraLbl.setWrapText(true);
                extraLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: " + (dk ? "rgba(229,231,235,0.60)" : "#64748B") + ";");
                infoBox.getChildren().add(extraLbl);
            }

            // ── Historial de settlements ───────────────────────────
            Label histTitle = new Label("Abonos registrados");
            histTitle.setStyle("-fx-font-size: 12px; -fx-font-weight: 800;" + (dk ? " -fx-text-fill: #E5E7EB;" : ""));
            VBox settlementsBox = new VBox(8);
            List<ObligationSettlementRepository.ObligationSettlement> settlements =
                settlementRepo.listByObligation(userUid, obligationId);
            if (settlements.isEmpty()) {
                Label none = new Label("Sin abonos todavía");
                none.setStyle("-fx-font-size: 11px; -fx-text-fill: " + (dk ? "rgba(229,231,235,0.50)" : "#94A3B8") + ";");
                settlementsBox.getChildren().add(none);
            } else {
                for (ObligationSettlementRepository.ObligationSettlement s : settlements) {
                    settlementsBox.getChildren().add(buildSettlementRow(
                        s, accountRepo, txRepo, userUid, dk,
                        () -> showSettlementForm(
                            overlay, obligationService, accountRepo, categoryRepo, txRepo,
                            userUid, obligation, s, darkTheme,
                            () -> refreshDetailAndAll(overlay, obligationService, obligationRepo,
                                settlementRepo, txRepo, accountRepo, categoryRepo,
                                userUid, obligationId, darkTheme, refreshAll)
                        ),
                        () -> confirmDeleteSettlement(
                            overlay, obligationService, settlementRepo, txRepo, accountRepo,
                            categoryRepo, obligationRepo, userUid, obligation, s, darkTheme, refreshAll
                        )
                    ));
                }
            }

            // ── Acciones ───────────────────────────────────────────
            boolean canSettle = state.status() != ObligationResolvedStatus.CANCELADA
                && state.status() != ObligationResolvedStatus.PAGADA;
            Button btnSettle = ModalOverlay.buildPrimaryButton(
                receivable ? "Registrar cobro" : "Registrar pago",
                receivable ? "fas-hand-holding-usd" : "fas-money-bill-wave",
                "#2563EB", "#1D4ED8"
            );
            btnSettle.setDisable(!canSettle);
            btnSettle.setOnAction(e -> showSettlementForm(
                overlay, obligationService, accountRepo, categoryRepo, txRepo,
                userUid, obligation, null, darkTheme,
                () -> refreshDetailAndAll(overlay, obligationService, obligationRepo,
                    settlementRepo, txRepo, accountRepo, categoryRepo,
                    userUid, obligationId, darkTheme, refreshAll)
            ));

            Button btnEdit = buildCompactAction("fas-pen", "#2563EB", dk);
            btnEdit.setOnAction(e -> showObligationForm(
                overlay, obligationService, categoryRepo, userUid, obligation, darkTheme,
                () -> refreshDetailAndAll(overlay, obligationService, obligationRepo,
                    settlementRepo, txRepo, accountRepo, categoryRepo,
                    userUid, obligationId, darkTheme, refreshAll)
            ));

            Button btnCancel = buildCompactAction("fas-ban", "#DC2626", dk);
            btnCancel.setDisable(state.status() == ObligationResolvedStatus.CANCELADA);
            btnCancel.setOnAction(e -> confirmCancelObligation(
                overlay, obligationService, obligationRepo, settlementRepo, txRepo,
                accountRepo, categoryRepo, userUid, obligation, darkTheme, refreshAll
            ));

            HBox actionsRow = new HBox(12, btnSettle, btnEdit, btnCancel);
            HBox.setHgrow(btnSettle, Priority.ALWAYS);
            actionsRow.setAlignment(Pos.CENTER_LEFT);

            overlay.register("obl-detail", 620,
                header, stateRow, infoBox, histTitle,
                boundedModalScroll(settlementsBox, overlay, 480),
                actionsRow);
            overlay.show("obl-detail");
        } catch (Exception ex) {
            ModernDialogs.danger("Error", "No se pudo abrir el detalle: " + safeMsg(ex), darkTheme);
        }
    }

    private static void refreshDetailAndAll(
        ModalOverlay overlay,
        ObligationService obligationService,
        ObligationRepository obligationRepo,
        ObligationSettlementRepository settlementRepo,
        TransactionRepository txRepo,
        AccountRepository accountRepo,
        CategoryRepository categoryRepo,
        String userUid,
        String obligationId,
        Supplier<Boolean> darkTheme,
        Runnable refreshAll
    ) {
        refreshAll.run();
        // Reabrir el detalle con datos frescos
        showObligationDetail(overlay, obligationService, obligationRepo, settlementRepo,
            txRepo, accountRepo, categoryRepo, userUid, obligationId, darkTheme, refreshAll);
    }

    private static Node buildSettlementRow(
        ObligationSettlementRepository.ObligationSettlement s,
        AccountRepository accountRepo,
        TransactionRepository txRepo,
        String userUid,
        boolean dk,
        Runnable onEdit,
        Runnable onDelete
    ) {
        String accountName = s.accountId();
        try {
            AccountRepository.Account acc = accountRepo.getById(userUid, s.accountId());
            if (acc != null) accountName = acc.name();
        } catch (Exception ignored) {}

        TransactionRepository.TransactionSyncRow tx = null;
        try { tx = txRepo.getForSyncByIdOrNull(userUid, s.linkedTransactionId()); } catch (Exception ignored) {}

        FontIcon txIcon = new FontIcon("fas-receipt");
        txIcon.setIconSize(11);
        txIcon.setIconColor(Color.web("#94A3B8"));

        Label amount = new Label(DashboardFormatters.formatMoney(s.amountCents()));
        amount.setStyle("-fx-font-size: 13px; -fx-font-weight: 900;" + (dk ? " -fx-text-fill: #E5E7EB;" : ""));
        Label date = new Label(formatDate(s.occurredAtEpochSec()));
        date.setStyle("-fx-font-size: 11px; -fx-font-weight: 600; -fx-text-fill: " + (dk ? "rgba(229,231,235,0.60)" : "#64748B") + ";");

        String txDesc = tx != null
            ? "Movimiento " + ("EXPENSE".equals(tx.kind()) ? "de egreso" : "de ingreso") + " · " + accountName
            : "Movimiento enlazado · " + accountName;
        Label txLbl = new Label(txDesc);
        txLbl.setStyle("-fx-font-size: 10px; -fx-text-fill: " + (dk ? "rgba(229,231,235,0.50)" : "#94A3B8") + ";");
        HBox txRow = new HBox(5, txIcon, txLbl);
        txRow.setAlignment(Pos.CENTER_LEFT);

        VBox mid = new VBox(3, amount, date, txRow);
        mid.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(mid, Priority.ALWAYS);

        Label note = new Label(s.note() == null ? "" : s.note());
        note.setStyle("-fx-font-size: 10px; -fx-text-fill: " + (dk ? "rgba(229,231,235,0.45)" : "#94A3B8") + ";");
        note.setWrapText(true);
        note.setMaxWidth(180);

        Button btnEdit = buildCompactAction("fas-pen", "#2563EB", dk);
        btnEdit.setOnAction(e -> onEdit.run());
        Button btnDelete = buildCompactAction("fas-trash", "#DC2626", dk);
        btnDelete.setOnAction(e -> onDelete.run());

        HBox row = new HBox(12, mid, note, btnEdit, btnDelete);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 14, 10, 14));
        row.setStyle(
            "-fx-background-color: " + (dk ? "rgba(255,255,255,0.04)" : "#F8FAFC") + "; "
            + "-fx-background-radius: 10; "
            + "-fx-border-color: " + (dk ? "rgba(255,255,255,0.06)" : "#E2E8F0") + "; "
            + "-fx-border-radius: 10; -fx-border-width: 1;"
        );
        return row;
    }

    // ══════════════════════════════════════════════════════════════
    //  Modal: Crear / Editar obligación (metadata — nunca Transaction)
    // ══════════════════════════════════════════════════════════════
    private static void showObligationForm(
        ModalOverlay overlay,
        ObligationService obligationService,
        CategoryRepository categoryRepo,
        String userUid,
        ObligationRepository.Obligation existing,
        Supplier<Boolean> darkTheme,
        Runnable refreshAll
    ) {
        boolean dk = Boolean.TRUE.equals(darkTheme.get());
        boolean editing = existing != null;
        String[] selectedType = { editing ? existing.type() : ObligationRepository.TYPE_RECEIVABLE };

        HBox header = overlay.buildHeader(
            editing ? "Editar obligación" : "Nueva obligación",
            editing ? "Modifica la metadata de la obligación" : "Una obligación no mueve dinero: registra una cuenta por cobrar o por pagar"
        );

        // Segmentado tipo
        Button segReceivable = new Button("Por cobrar");
        Button segPayable = new Button("Por pagar");
        FontIcon segR = new FontIcon("fas-arrow-down");
        segR.setIconSize(12);
        segReceivable.setGraphic(segR);
        segReceivable.setGraphicTextGap(6);
        FontIcon segP = new FontIcon("fas-arrow-up");
        segP.setIconSize(12);
        segPayable.setGraphic(segP);
        segPayable.setGraphicTextGap(6);
        segReceivable.getStyleClass().addAll("loan-seg-btn");
        segPayable.getStyleClass().add("loan-seg-btn");
        applySeg(segReceivable, segPayable, selectedType[0]);
        segReceivable.setOnAction(e -> { selectedType[0] = ObligationRepository.TYPE_RECEIVABLE; applySeg(segReceivable, segPayable, selectedType[0]); });
        segPayable.setOnAction(e -> { selectedType[0] = ObligationRepository.TYPE_PAYABLE; applySeg(segReceivable, segPayable, selectedType[0]); });
        segReceivable.setMaxWidth(Double.MAX_VALUE);
        segPayable.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(segReceivable, Priority.ALWAYS);
        HBox.setHgrow(segPayable, Priority.ALWAYS);
        HBox segmentRow = new HBox(0, segReceivable, segPayable);
        segmentRow.getStyleClass().add("loan-segment-row");

        // Campos
        Label titleLabel = ModalOverlay.fieldLabel("Título", "fas-tag");
        TextField titleField = new TextField(editing ? existing.title() : "");
        titleField.setPromptText("Ej: Factura de herramientas");
        titleField.getStyleClass().add("modal-text-input");

        Label personLabel = ModalOverlay.fieldLabel("Contraparte", "fas-user");
        TextField personField = new TextField(editing ? existing.counterpartyName() : "");
        personField.setPromptText("Nombre de la persona o empresa");
        personField.getStyleClass().add("modal-text-input");

        Label amountLabel = ModalOverlay.fieldLabel("Monto original", "fas-dollar-sign");
        MoneyInputField amountField = new MoneyInputField();
        amountField.setPromptText("$0");
        amountField.getStyleClass().add("modal-amount-input");
        if (editing) amountField.setAmountCents(existing.originalAmountCents());

        Label issuedLabel = ModalOverlay.fieldLabel("Fecha de emisión", "fas-calendar-alt");
        DatePicker issuedPicker = new DatePicker(
            editing ? toLocalDate(existing.issuedAtEpochSec()) : LocalDate.now()
        );
        issuedPicker.setMaxWidth(Double.MAX_VALUE);
        issuedPicker.getStyleClass().add("modal-text-input");

        Label dueLabel = ModalOverlay.fieldLabel("Vencimiento (opcional)", "fas-calendar-day");
        DatePicker duePicker = new DatePicker(
            editing && existing.dueAtEpochSec() != null ? toLocalDate(existing.dueAtEpochSec()) : null
        );
        duePicker.setMaxWidth(Double.MAX_VALUE);
        duePicker.getStyleClass().add("modal-text-input");
        Button clearDue = new Button("Quitar");
        clearDue.getStyleClass().add("btn-secondary");
        clearDue.setOnAction(e -> duePicker.setValue(null));
        HBox dueRow = new HBox(8, duePicker, clearDue);
        HBox.setHgrow(duePicker, Priority.ALWAYS);
        dueRow.setAlignment(Pos.CENTER_LEFT);

        Label catLabel = ModalOverlay.fieldLabel("Categoría de obligación (opcional)", "fas-tags");
        ComboBox<CategoryRepository.Category> catCombo = new ComboBox<>();
        catCombo.setMaxWidth(Double.MAX_VALUE);
        catCombo.getStyleClass().add("account-combo");
        catCombo.setConverter(new StringConverter<>() {
            @Override public String toString(CategoryRepository.Category c) { return c == null ? "Sin categoría" : c.name(); }
            @Override public CategoryRepository.Category fromString(String s) { return null; }
        });
        List<CategoryRepository.Category> categories = new ArrayList<>();
        categories.add(null);
        try { categories.addAll(categoryRepo.listAll(userUid)); } catch (Exception ignored) {}
        catCombo.setItems(FXCollections.observableArrayList(categories));
        if (editing && existing.obligationCategoryId() != null) {
            for (CategoryRepository.Category c : categories) {
                if (c != null && c.id().equals(existing.obligationCategoryId())) {
                    catCombo.getSelectionModel().select(c);
                    break;
                }
            }
        } else {
            catCombo.getSelectionModel().selectFirst();
        }

        Label refLabel = ModalOverlay.fieldLabel("Referencia (opcional)", "fas-hashtag");
        TextField refField = new TextField(editing && existing.reference() != null ? existing.reference() : "");
        refField.setPromptText("N° factura, contrato, etc.");
        refField.getStyleClass().add("modal-text-input");

        Label notesLabel = ModalOverlay.fieldLabel("Notas (opcional)", "fas-sticky-note");
        TextField notesField = new TextField(editing && existing.notes() != null ? existing.notes() : "");
        notesField.setPromptText("Detalles adicionales");
        notesField.getStyleClass().add("modal-text-input");

        Label errorLabel = modalErrorLabel();

        VBox footer = overlay.buildFooter(editing ? "Guardar cambios" : "Crear obligación", "fas-check", "#2563EB", "#1D4ED8");
        Button submitBtn = findPrimaryButton(footer);
        AtomicBoolean saving = new AtomicBoolean(false);

        if (submitBtn != null) {
            submitBtn.setOnAction(ev -> {
                if (!saving.compareAndSet(false, true)) return;
                submitBtn.setDisable(true);
                try {
                    hideError(errorLabel);

                    String title = trim(titleField.getText());
                    String person = trim(personField.getText());
                    long cents = amountField.getAmountCentsOrZero();
                    if (title.isBlank()) { showError(errorLabel, "Ingresa un título"); return; }
                    if (person.isBlank()) { showError(errorLabel, "Ingresa la contraparte"); return; }
                    if (cents <= 0) { showError(errorLabel, "El monto debe ser mayor a $0"); return; }
                    if (issuedPicker.getValue() == null) { showError(errorLabel, "Selecciona la fecha de emisión"); return; }

                    long issuedAt = toEpochSec(issuedPicker.getValue());
                    Long dueAt = duePicker.getValue() != null ? toEpochSec(duePicker.getValue()) : null;
                    String categoryId = catCombo.getValue() != null ? catCombo.getValue().id() : null;
                    String reference = emptyToNull(refField.getText());
                    String notes = emptyToNull(notesField.getText());

                    try {
                        if (editing) {
                            obligationService.updateObligationMetadata(
                                userUid, existing.id(), selectedType[0], title, person, "COP",
                                cents, issuedAt, dueAt, categoryId, reference, notes
                            );
                        } else {
                            obligationService.createObligation(
                                userUid, selectedType[0], title, person, "COP",
                                cents, issuedAt, dueAt, categoryId, reference, notes
                            );
                        }
                        overlay.hide();
                        Platform.runLater(() -> {
                            refreshAll.run();
                            ModernDialogs.success(
                                editing ? "Obligación actualizada" : "Obligación creada",
                                editing ? "La metadata se actualizó correctamente."
                                        : "La obligación quedó registrada sin movimientos financieros.",
                                darkTheme
                            );
                        });
                    } catch (Exception ex) {
                        showError(errorLabel, friendlyServiceError(ex));
                    }
                } finally {
                    saving.set(false);
                    submitBtn.setDisable(false);
                }
            });
        }

        VBox form = new VBox(14,
            new VBox(6, titleLabel, titleField),
            new VBox(6, personLabel, personField),
            new VBox(6, amountLabel, amountField),
            new VBox(6, issuedLabel, issuedPicker),
            new VBox(6, dueLabel, dueRow),
            new VBox(6, catLabel, catCombo),
            new VBox(6, refLabel, refField),
            new VBox(6, notesLabel, notesField)
        );
        overlay.register("obl-form", 520, header, segmentRow,
            boundedModalScroll(form, overlay, 340), errorLabel, footer);
        overlay.setInitialFocus("obl-form", titleField);
        overlay.show("obl-form");
    }

    // ══════════════════════════════════════════════════════════════
    //  Modal: Registrar / Editar settlement (vía ObligationService)
    // ══════════════════════════════════════════════════════════════
    private static void showSettlementForm(
        ModalOverlay overlay,
        ObligationService obligationService,
        AccountRepository accountRepo,
        CategoryRepository categoryRepo,
        TransactionRepository txRepo,
        String userUid,
        ObligationRepository.Obligation obligation,
        ObligationSettlementRepository.ObligationSettlement existing,
        Supplier<Boolean> darkTheme,
        Runnable onSaved
    ) {
        boolean dk = Boolean.TRUE.equals(darkTheme.get());
        boolean editing = existing != null;
        boolean receivable = ObligationRepository.TYPE_RECEIVABLE.equals(obligation.type());
        String action = receivable ? "cobro" : "pago";

        HBox header = overlay.buildHeader(
            editing ? "Editar " + action : ("Registrar " + action),
            obligation.title() + " · " + obligation.counterpartyName()
        );

        // Cuenta
        Label accountLabel = ModalOverlay.fieldLabel("Cuenta", "fas-wallet");
        ComboBox<AccountRepository.Account> accountCombo = new ComboBox<>();
        accountCombo.setMaxWidth(Double.MAX_VALUE);
        accountCombo.getStyleClass().add("account-combo");
        accountCombo.setConverter(new StringConverter<>() {
            @Override public String toString(AccountRepository.Account a) { return a == null ? "" : a.name() + " · " + a.currency(); }
            @Override public AccountRepository.Account fromString(String s) { return null; }
        });
        accountCombo.setCellFactory(accountCellFactory());
        accountCombo.setButtonCell(accountCellFactory().call(null));
        try {
            List<AccountRepository.Account> accounts = accountRepo.list(userUid);
            accountCombo.setItems(FXCollections.observableArrayList(accounts));
            if (editing) {
                for (AccountRepository.Account a : accounts) {
                    if (a.id().equals(existing.accountId())) { accountCombo.getSelectionModel().select(a); break; }
                }
            }
            if (accountCombo.getValue() == null && !accounts.isEmpty()) accountCombo.getSelectionModel().selectFirst();
        } catch (Exception ignored) {}

        Label balanceLabel = new Label();
        balanceLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: 700; -fx-text-fill: #16A34A;");
        Runnable refreshBalance = () -> {
            AccountRepository.Account acc = accountCombo.getValue();
            if (acc == null) { balanceLabel.setText(""); return; }
            try {
                long cents = accountRepo.computeBalanceCents(userUid, acc.id());
                balanceLabel.setText("Saldo disponible: " + DashboardFormatters.formatMoney(cents, acc.currency()));
            } catch (Exception ex) { balanceLabel.setText(""); }
        };
        accountCombo.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> refreshBalance.run());
        refreshBalance.run();

        // Monto
        Label amountLabel = ModalOverlay.fieldLabel("Monto", "fas-dollar-sign");
        MoneyInputField amountField = new MoneyInputField();
        amountField.setPromptText("$0");
        amountField.getStyleClass().add("modal-amount-input");
        if (editing) amountField.setAmountCents(existing.amountCents());

        // Categoría financiera (de la Transaction, no de la obligación):
        // mismo concepto que Nueva transacción → Categoría → Subcategoría.
        String wantedKind = receivable ? "INCOME" : "EXPENSE";
        StringConverter<CategoryRepository.Category> catNameConverter = new StringConverter<>() {
            @Override public String toString(CategoryRepository.Category c) { return c == null ? "" : c.name(); }
            @Override public CategoryRepository.Category fromString(String s) { return null; }
        };

        Label finCatLabel = ModalOverlay.fieldLabel("Categoría financiera", "fas-tags");
        ComboBox<CategoryRepository.Category> finCatCombo = new ComboBox<>();
        finCatCombo.setMaxWidth(Double.MAX_VALUE);
        finCatCombo.getStyleClass().add("account-combo");
        finCatCombo.setConverter(catNameConverter);

        Label finSubCatLabel = ModalOverlay.fieldLabel("Subcategoría", "fas-tag");
        ComboBox<CategoryRepository.Category> finSubCatCombo = new ComboBox<>();
        finSubCatCombo.setMaxWidth(Double.MAX_VALUE);
        finSubCatCombo.getStyleClass().add("account-combo");
        finSubCatCombo.setConverter(catNameConverter);
        VBox finSubCatBox = new VBox(6, finSubCatLabel, finSubCatCombo);
        finSubCatBox.setVisible(false);
        finSubCatBox.setManaged(false);

        List<CategoryRepository.Category> allCats = new ArrayList<>();
        try { allCats.addAll(categoryRepo.listAll(userUid)); } catch (Exception ignored) {}
        List<CategoryRepository.Category> roots = settlementCompatibleRoots(allCats, wantedKind);
        finCatCombo.setItems(FXCollections.observableArrayList(roots));

        // La primera opción de subcategoría (vacía) significa "usar la categoría raíz".
        Runnable refreshSubCats = () -> {
            CategoryRepository.Category root = finCatCombo.getValue();
            CategoryRepository.Category previousSub = finSubCatCombo.getValue();
            finSubCatCombo.getItems().clear();
            List<CategoryRepository.Category> subs = root == null
                ? List.of()
                : settlementCompatibleSubcategories(allCats, root.id(), wantedKind);
            if (subs.isEmpty()) {
                finSubCatBox.setVisible(false);
                finSubCatBox.setManaged(false);
            } else {
                finSubCatCombo.getItems().add(null);
                finSubCatCombo.getItems().addAll(subs);
                finSubCatCombo.getSelectionModel().selectFirst();
                if (previousSub != null) {
                    for (CategoryRepository.Category s : subs) {
                        if (s.id().equals(previousSub.id())) {
                            finSubCatCombo.getSelectionModel().select(s);
                            break;
                        }
                    }
                }
                finSubCatBox.setVisible(true);
                finSubCatBox.setManaged(true);
            }
        };
        finCatCombo.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> refreshSubCats.run());

        try {
            // La categoría financiera vive en la Transaction enlazada: al editar
            // se restaura raíz (y subcategoría si la tx apuntaba a una hija).
            if (editing) {
                TransactionRepository.TransactionSyncRow linkedTx =
                    txRepo.getForSyncByIdOrNull(userUid, existing.linkedTransactionId());
                if (linkedTx != null) {
                    CategoryRepository.Category saved = null;
                    for (CategoryRepository.Category c : allCats) {
                        if (c.id().equals(linkedTx.categoryId())) { saved = c; break; }
                    }
                    if (saved != null) {
                        String savedRootId = (saved.parentId() == null || saved.parentId().isBlank())
                            ? saved.id() : saved.parentId();
                        for (CategoryRepository.Category r : roots) {
                            if (r.id().equals(savedRootId)) { finCatCombo.getSelectionModel().select(r); break; }
                        }
                        if (!saved.id().equals(savedRootId) && finCatCombo.getValue() != null) {
                            for (var i = 0; i < finSubCatCombo.getItems().size(); i++) {
                                CategoryRepository.Category s = finSubCatCombo.getItems().get(i);
                                if (s != null && s.id().equals(saved.id())) {
                                    finSubCatCombo.getSelectionModel().select(i);
                                    break;
                                }
                            }
                        }
                    }
                }
            }
            if (finCatCombo.getValue() == null && !roots.isEmpty()) finCatCombo.getSelectionModel().selectFirst();
            refreshSubCats.run();
        } catch (Exception ignored) {}

        // Fecha
        Label dateLabel = ModalOverlay.fieldLabel("Fecha", "fas-calendar-alt");
        DatePicker datePicker = new DatePicker(editing ? toLocalDate(existing.occurredAtEpochSec()) : LocalDate.now());
        datePicker.setMaxWidth(Double.MAX_VALUE);
        datePicker.getStyleClass().add("modal-text-input");

        // Nota
        Label noteLabel = ModalOverlay.fieldLabel("Nota (opcional)", "fas-sticky-note");
        TextField noteField = new TextField(editing && existing.note() != null ? existing.note() : "");
        noteField.setPromptText("Agrega una descripción...");
        noteField.getStyleClass().add("modal-text-input");

        Label errorLabel = modalErrorLabel();

        VBox footer = overlay.buildFooter(
            editing ? "Guardar " + action : "Confirmar " + action,
            "fas-check-circle", "#2563EB", "#1D4ED8"
        );
        Button submitBtn = findPrimaryButton(footer);
        AtomicBoolean saving = new AtomicBoolean(false);

        if (submitBtn != null) {
            submitBtn.setOnAction(ev -> {
                if (!saving.compareAndSet(false, true)) return;
                submitBtn.setDisable(true);
                try {
                    hideError(errorLabel);

                    AccountRepository.Account acc = accountCombo.getValue();
                    long cents = amountField.getAmountCentsOrZero();
                    CategoryRepository.Category finCat = finSubCatCombo.getValue() != null
                        ? finSubCatCombo.getValue() : finCatCombo.getValue();
                    if (acc == null) { showError(errorLabel, "Selecciona una cuenta"); return; }
                    if (cents <= 0) { showError(errorLabel, "El monto debe ser mayor a $0"); return; }
                    if (finCat == null) { showError(errorLabel, "Selecciona la categoría financiera"); return; }
                    if (datePicker.getValue() == null) { showError(errorLabel, "Selecciona la fecha"); return; }

                    // El DatePicker cambia solo el día: la hora real del registro se
                    // conserva (alta: ahora mismo; edición: hora original del abono).
                    long occurredAt = occurredAtForPickedDate(
                        datePicker.getValue(),
                        editing ? existing.occurredAtEpochSec() : Instant.now().getEpochSecond());
                    String note = emptyToNull(noteField.getText());

                    try {
                        if (editing) {
                            obligationService.updateSettlement(
                                userUid, existing.id(), acc.id(), finCat.id(),
                                cents, occurredAt, note
                            );
                        } else {
                            obligationService.registerSettlement(
                                userUid, obligation.id(), acc.id(), finCat.id(),
                                cents, occurredAt, note
                            );
                        }
                        overlay.hide();
                        Platform.runLater(() -> {
                            onSaved.run();
                            ModernDialogs.success(
                                editing ? "Abono actualizado" : "Abono registrado",
                                "El movimiento financiero se actualizó de forma atómica con el abono.",
                                darkTheme
                            );
                        });
                    } catch (Exception ex) {
                        showError(errorLabel, friendlyServiceError(ex));
                    }
                } finally {
                    saving.set(false);
                    submitBtn.setDisable(false);
                }
            });
        }

        VBox settleFields = new VBox(14,
            new VBox(6, accountLabel, accountCombo, balanceLabel),
            new VBox(6, amountLabel, amountField),
            new VBox(6, finCatLabel, finCatCombo),
            finSubCatBox,
            new VBox(6, dateLabel, datePicker),
            new VBox(6, noteLabel, noteField)
        );
        overlay.register("obl-settle", 480, header,
            boundedModalScroll(settleFields, overlay, 330), errorLabel, footer);
        overlay.setInitialFocus("obl-settle", amountField);
        overlay.show("obl-settle");
    }

    // ══════════════════════════════════════════════════════════════
    //  Confirmaciones
    // ══════════════════════════════════════════════════════════════
    private static void confirmCancelObligation(
        ModalOverlay overlay,
        ObligationService obligationService,
        ObligationRepository obligationRepo,
        ObligationSettlementRepository settlementRepo,
        TransactionRepository txRepo,
        AccountRepository accountRepo,
        CategoryRepository categoryRepo,
        String userUid,
        ObligationRepository.Obligation obligation,
        Supplier<Boolean> darkTheme,
        Runnable refreshAll
    ) {
        boolean ok = ModernDialogs.confirm(
            "Cancelar obligación",
            "Cancelar \"" + obligation.title() + "\" NO registra un pago ni un cobro. "
                + "No se creará ninguna transacción ni se modificará el saldo de tus cuentas. "
                + "La obligación quedará marcada como CANCELADA.",
            "Sí, cancelar", "Volver", darkTheme
        );
        if (!ok) return;
        try {
            obligationService.cancelObligation(userUid, obligation.id(), nowSec());
            refreshAll.run();
            ModernDialogs.success("Obligación cancelada", "La obligación quedó marcada como cancelada.", darkTheme);
            overlay.hide();
        } catch (Exception ex) {
            ModernDialogs.danger("No se pudo cancelar", friendlyServiceError(ex), darkTheme);
        }
    }

    private static void confirmDeleteSettlement(
        ModalOverlay overlay,
        ObligationService obligationService,
        ObligationSettlementRepository settlementRepo,
        TransactionRepository txRepo,
        AccountRepository accountRepo,
        CategoryRepository categoryRepo,
        ObligationRepository obligationRepo,
        String userUid,
        ObligationRepository.Obligation obligation,
        ObligationSettlementRepository.ObligationSettlement settlement,
        Supplier<Boolean> darkTheme,
        Runnable refreshAll
    ) {
        boolean ok = ModernDialogs.confirmDestructive(
            "Eliminar abono",
            "Se eliminará el abono de " + DashboardFormatters.formatMoney(settlement.amountCents())
                + " y también se eliminará el movimiento financiero asociado a él. "
                + "El saldo de la cuenta se revertirá. Esta acción no se puede deshacer.",
            darkTheme
        );
        if (!ok) return;
        try {
            obligationService.deleteSettlement(userUid, settlement.id());
            refreshAll.run();
            showObligationDetail(overlay, obligationService, obligationRepo, settlementRepo,
                txRepo, accountRepo, categoryRepo, userUid, obligation.id(), darkTheme, refreshAll);
            ModernDialogs.success("Abono eliminado", "El abono y su movimiento financiero fueron eliminados.", darkTheme);
        } catch (Exception ex) {
            ModernDialogs.danger("No se pudo eliminar", friendlyServiceError(ex), darkTheme);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Helpers visuales (mismo lenguaje que LoansView)
    // ══════════════════════════════════════════════════════════════
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
        pill.setStyle("-fx-background-color: #2563EB; -fx-text-fill: white; "
            + "-fx-background-radius: 10; -fx-border-radius: 10; "
            + "-fx-font-size: 13px; -fx-font-weight: 700; -fx-cursor: hand; -fx-padding: 7 20 7 20;");
        pill.getStyleClass().add("pill-active");
        if (pill.getGraphic() instanceof FontIcon fi) fi.setIconColor(Color.WHITE);
        ScaleTransition pulse = new ScaleTransition(Duration.millis(150), pill);
        pulse.setFromX(0.95); pulse.setFromY(0.95);
        pulse.setToX(1.0); pulse.setToY(1.0);
        pulse.setInterpolator(Interpolator.EASE_OUT);
        pulse.play();
    }

    private static void applyPillInactive(Button pill, boolean dk) {
        pill.setStyle("-fx-background-color: transparent; -fx-text-fill: " + (dk ? "rgba(229,231,235,0.60)" : "#64748B") + "; "
            + "-fx-background-radius: 10; -fx-border-radius: 10; "
            + "-fx-font-size: 13px; -fx-font-weight: 600; -fx-cursor: hand; -fx-padding: 7 20 7 20;");
        pill.getStyleClass().remove("pill-active");
        if (pill.getGraphic() instanceof FontIcon fi) fi.setIconColor(Color.web(dk ? "rgba(229,231,235,0.60)" : "#64748B"));
        pill.setScaleX(1.0);
        pill.setScaleY(1.0);
    }

    private static void applySeg(Button receivable, Button payable, String type) {
        receivable.getStyleClass().remove("loan-seg-active");
        payable.getStyleClass().remove("loan-seg-active");
        (ObligationRepository.TYPE_RECEIVABLE.equals(type) ? receivable : payable)
            .getStyleClass().add("loan-seg-active");
    }

    private static ScrollPane boundedModalScroll(Node content, ModalOverlay overlay, double reservedPx) {
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setPannable(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        sp.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        sp.getStyleClass().add("new-tx-scroll");
        sp.maxHeightProperty().bind(
            Bindings.max(160, overlay.getModalContainer().heightProperty().subtract(reservedPx))
        );
        return sp;
    }

    private static Node buildCardMetric(String label, String value, String valueColor, boolean dk) {
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-size: 10px; -fx-font-weight: 700; -fx-text-fill: " + (dk ? "rgba(229,231,235,0.55)" : "#94A3B8") + ";");
        Label val = new Label(value);
        String valColor = valueColor != null ? valueColor : (dk ? "#E5E7EB" : null);
        val.setStyle("-fx-font-size: 14px; -fx-font-weight: 900;" + (valColor != null ? " -fx-text-fill: " + valColor + ";" : ""));
        VBox block = new VBox(2, lbl, val);
        block.setAlignment(Pos.CENTER_LEFT);
        return block;
    }

    private static Region cardSeparator(boolean dk) {
        Region sep = new Region();
        sep.setStyle("-fx-background-color: " + (dk ? "rgba(255,255,255,0.10)" : "#F1F5F9") + "; "
            + "-fx-pref-width: 1; -fx-min-width: 1; -fx-max-width: 1;");
        sep.setMinHeight(60);
        return sep;
    }

    private static Button buildCompactAction(String iconLiteral, String iconColor, boolean dk) {
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(13);
        icon.setIconColor(Color.web(iconColor));
        Button btn = new Button();
        btn.setGraphic(icon);
        String base = dk
            ? "-fx-background-color: rgba(255,255,255,0.06); -fx-background-radius: 8; -fx-border-radius: 8; "
              + "-fx-border-color: rgba(255,255,255,0.10); -fx-border-width: 1; "
              + "-fx-min-width: 34; -fx-min-height: 34; -fx-max-width: 34; -fx-max-height: 34; -fx-cursor: hand; -fx-padding: 0;"
            : "-fx-background-color: #F8FAFC; -fx-background-radius: 8; -fx-border-radius: 8; "
              + "-fx-border-color: #E2E8F0; -fx-border-width: 1; "
              + "-fx-min-width: 34; -fx-min-height: 34; -fx-max-width: 34; -fx-max-height: 34; -fx-cursor: hand; -fx-padding: 0;";
        String hover = dk
            ? "-fx-background-color: rgba(59,130,246,0.18); -fx-background-radius: 8; -fx-border-radius: 8; "
              + "-fx-border-color: rgba(59,130,246,0.35); -fx-border-width: 1; "
              + "-fx-min-width: 34; -fx-min-height: 34; -fx-max-width: 34; -fx-max-height: 34; -fx-cursor: hand; -fx-padding: 0;"
            : "-fx-background-color: #EEF2FF; -fx-background-radius: 8; -fx-border-radius: 8; "
              + "-fx-border-color: #CBD5E1; -fx-border-width: 1; "
              + "-fx-min-width: 34; -fx-min-height: 34; -fx-max-width: 34; -fx-max-height: 34; -fx-cursor: hand; -fx-padding: 0;";
        btn.setStyle(base);
        btn.setOnMouseEntered(ev -> btn.setStyle(hover));
        btn.setOnMouseExited(ev -> btn.setStyle(base));
        return btn;
    }

    private static Label statusBadge(ObligationResolvedStatus status) {
        String text;
        String color;
        switch (status) {
            case PAGADA -> { text = "PAGADA"; color = "#16A34A"; }
            case VENCIDA -> { text = "VENCIDA"; color = "#DC2626"; }
            case PARCIAL -> { text = "PARCIAL"; color = "#D97706"; }
            case CANCELADA -> { text = "CANCELADA"; color = "#6B7280"; }
            default -> { text = "PENDIENTE"; color = "#64748B"; }
        }
        Label badge = new Label(text);
        badge.setStyle("-fx-font-size: 10px; -fx-font-weight: 900; -fx-text-fill: " + color + "; "
            + "-fx-background-color: " + color + "22; -fx-background-radius: 20; -fx-padding: 4 12 4 12;");
        return badge;
    }

    private static javafx.util.Callback<javafx.scene.control.ListView<AccountRepository.Account>, ListCell<AccountRepository.Account>> accountCellFactory() {
        return cb -> new ListCell<>() {
            @Override
            protected void updateItem(AccountRepository.Account item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setGraphic(null); return; }
                String typeKey = AccountRepository.normalizeType(item.type());
                String hex = AccountStyles.resolveColor(typeKey, item.color());
                Circle bg = new Circle(15);
                try { bg.setFill(Color.web(hex).deriveColor(0, 1.0, 1.0, 0.18)); }
                catch (Exception e) { bg.setFill(Color.web(AccountStyles.BANK.color(), 0.18)); }
                FontIcon icon = new FontIcon(AccountStyles.resolveIcon(typeKey));
                icon.setIconSize(12);
                try { icon.setIconColor(Color.web(hex)); }
                catch (Exception e) { icon.setIconColor(Color.web(AccountStyles.BANK.color())); }
                StackPane avatar = new StackPane(bg, icon);
                avatar.setMinSize(30, 30);
                avatar.setPrefSize(30, 30);
                avatar.setMaxSize(30, 30);
                avatar.setAlignment(Pos.CENTER);
                Label nameLabel = new Label(item.name() == null ? "" : item.name());
                nameLabel.setStyle("-fx-font-weight: 700; -fx-font-size: 13px;");
                Label typeLabel = new Label(AccountStyles.resolveLabel(item.type()));
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

    // ══════════════════════════════════════════════════════════════
    //  Utilidades
    // ══════════════════════════════════════════════════════════════
    private static long nowSec() {
        return Instant.now().getEpochSecond();
    }

    private static long toEpochSec(LocalDate date) {
        return date.atStartOfDay(ZONE).toEpochSecond();
    }

    /**
     * Combina la fecha elegida en el DatePicker con la hora del timestamp base:
     * mismo día → conserva el base exacto; otro día → traslada la hora del base.
     */
    static long occurredAtForPickedDate(LocalDate picked, long baseEpochSec) {
        long dayStart = toEpochSec(picked);
        long baseDayStart = toEpochSec(toLocalDate(baseEpochSec));
        return dayStart == baseDayStart ? baseEpochSec : dayStart + (baseEpochSec - baseDayStart);
    }

    /**
     * Separa la lista en activas (PENDIENTE/PARCIAL/VENCIDA/PAGADA) y
     * canceladas. Equivalente a Metas → Archivadas: las canceladas se
     * muestran en una sección aparte, contraída por defecto.
     */
    static List<List<ObligationRepository.Obligation>> partitionByCancellation(
        List<ObligationRepository.Obligation> obligations,
        java.util.function.Function<String, ResolvedObligationState> resolver
    ) {
        List<ObligationRepository.Obligation> active = new ArrayList<>();
        List<ObligationRepository.Obligation> cancelled = new ArrayList<>();
        for (ObligationRepository.Obligation o : obligations) {
            ResolvedObligationState state = resolver.apply(o.id());
            if (state != null && state.status() == ObligationResolvedStatus.CANCELADA) {
                cancelled.add(o);
            } else {
                active.add(o);
            }
        }
        return List.of(active, cancelled);
    }

    // ── Categoría → Subcategoría del abono (Transaction) ───────────
    // Mismo concepto que Nueva transacción: la raíz deriva su kind por
    // nombre cuando es BOTH/sin kind; las hojas deben ser compatibles.
    static String settlementEffectiveRootKind(CategoryRepository.Category root) {
        if (root == null) return null;
        String kind = root.kind() == null ? "" : root.kind().trim();
        if (!kind.isEmpty() && !"BOTH".equalsIgnoreCase(kind)) return kind;
        String name = root.name() == null ? "" : root.name().trim().toUpperCase();
        if (name.startsWith("INGRESOS")) return "INCOME";
        if (name.startsWith("GASTOS")) return "EXPENSE";
        return null;
    }

    static boolean settlementKindCompatible(String kind, String wantedKind) {
        if (kind == null || kind.isBlank()) return true; // hereda el contexto del padre
        String k = kind.trim();
        return wantedKind.equalsIgnoreCase(k) || "BOTH".equalsIgnoreCase(k);
    }

    static List<CategoryRepository.Category> settlementCompatibleRoots(
        List<CategoryRepository.Category> all, String wantedKind) {
        List<CategoryRepository.Category> out = new ArrayList<>();
        for (CategoryRepository.Category c : all) {
            if (c.parentId() != null && !c.parentId().isBlank()) continue;
            if (c.id() != null && c.id().startsWith("system-")) continue;
            if (wantedKind.equalsIgnoreCase(settlementEffectiveRootKind(c))) out.add(c);
        }
        return out;
    }

    static List<CategoryRepository.Category> settlementCompatibleSubcategories(
        List<CategoryRepository.Category> all, String rootId, String wantedKind) {
        List<CategoryRepository.Category> out = new ArrayList<>();
        if (rootId == null || rootId.isBlank()) return out;
        for (CategoryRepository.Category c : all) {
            if (!rootId.equals(c.parentId())) continue;
            if (c.id() != null && c.id().startsWith("system-")) continue;
            if (settlementKindCompatible(c.kind(), wantedKind)) out.add(c);
        }
        return out;
    }

    private static LocalDate toLocalDate(long epochSec) {
        return Instant.ofEpochSecond(epochSec).atZone(ZONE).toLocalDate();
    }

    private static String formatDate(long epochSec) {
        return DATE_FMT.format(Instant.ofEpochSecond(epochSec).atZone(ZONE).toLocalDate());
    }

    private static String buildInitials(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        return (parts[0].substring(0, 1) + parts[parts.length - 1].substring(0, 1)).toUpperCase();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String emptyToNull(String s) {
        String t = trim(s);
        return t.isEmpty() ? null : t;
    }

    private static Label modalErrorLabel() {
        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: 700; -fx-text-fill: #DC2626;");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        return errorLabel;
    }

    private static void showError(Label errorLabel, String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private static void hideError(Label errorLabel) {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }

    private static Button findPrimaryButton(VBox footer) {
        Button submitBtn = (Button) footer.lookup(".btn-primary-modal");
        if (submitBtn == null) {
            for (Node n : footer.getChildren()) {
                if (n instanceof Button b) { submitBtn = b; break; }
                if (n instanceof HBox hb) {
                    for (Node c : hb.getChildren()) {
                        if (c instanceof Button b) { submitBtn = b; break; }
                    }
                }
            }
        }
        return submitBtn;
    }

    private static String friendlyServiceError(Exception ex) {
        String msg = ex.getMessage();
        if (msg == null) return "Error inesperado";
        return switch (msg) {
            case "obligation_cancelled" -> "La obligación está cancelada";
            case "obligation_paid" -> "La obligación ya está pagada por completo";
            case "settlement_exceeds_pending" -> "El monto excede el saldo pendiente de la obligación";
            case "obligation_amount_below_settled" -> "El monto original no puede ser menor a lo ya abonado";
            case "linked_transaction_missing" -> "Inconsistencia: el movimiento enlazado no existe";
            case "Saldo insuficiente" -> "Saldo insuficiente en la cuenta seleccionada";
            case "amountCents" -> "El monto debe ser mayor a $0";
            case "accountId" -> "Selecciona una cuenta";
            case "financialCategoryId" -> "Selecciona la categoría financiera";
            case "obligation_not_found" -> "La obligación no existe";
            case "settlement_not_found" -> "El abono no existe";
            default -> msg;
        };
    }

    private static String safeMsg(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
