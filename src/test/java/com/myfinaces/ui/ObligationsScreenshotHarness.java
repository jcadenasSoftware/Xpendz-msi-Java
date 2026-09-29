package com.myfinaces.ui;

import com.myfinaces.auth.AuthSession;
import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.AppSchema;
import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.ObligationRepository;
import com.myfinaces.db.ObligationSettlementRepository;
import com.myfinaces.db.SqliteDatabase;
import com.myfinaces.db.TransactionKind;
import com.myfinaces.db.TransactionRepository;
import com.myfinaces.db.UserRepository;
import com.myfinaces.service.ObligationService;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Harness de validación visual Fase 2E. No corre bajo surefire
 * (nombre sin sufijo Test). Renderiza ObligationsView y sus modales
 * en una Stage real y exporta PNGs a target/obligations-shots/.
 *
 * Uso: mvn -q test-compile exec:java ^
 *   -Dexec.mainClass=com.myfinaces.ui.ObligationsScreenshotHarness ^
 *   -Dexec.classpathScope=test
 */
public final class ObligationsScreenshotHarness {

    private static final String UID = "visual-check-user";
    private static final Path OUT = Path.of("target", "obligations-shots");

    private static SqliteDatabase database;
    private static AccountRepository accountRepo;
    private static CategoryRepository categoryRepo;
    private static TransactionRepository txRepo;
    private static ObligationRepository obligationRepo;
    private static ObligationSettlementRepository settlementRepo;
    private static ObligationService service;
    private static AuthSession session;

    private static Stage stage;
    private static Scene scene;
    private static boolean dark;
    private static String accountId;
    private static String cashAccountId;
    private static String incomeCatId;
    private static String expenseCatId;
    private static String obligationCatId;

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUT);
        setupDatabase();
        seed();

        Platform.startup(() -> {});
        // Esperar a que el toolkit FX quede listo.
        fx(() -> {});

        // ── 1. Menú lateral + lista Por cobrar (light) ────────────
        openDashboardLike(true);
        snap("01-sidebar-y-lista-por-cobrar-light");

        // ── 1b. Sección Canceladas: expandir + detalle cancelada ──
        snapScrollContent("01a-seccion-canceladas-contraida-light");
        clickLabelStartingWith("Canceladas (");
        snapScrollContent("01b-seccion-canceladas-expandida-light");
        clickCardByCounterparty("Cliente sin nombre");
        snap("01c-detalle-cancelada-light");
        closeModal();
        settle();

        // ── 2. Tab Por pagar ──────────────────────────────────────
        fireButton("Por pagar");
        snap("02-lista-por-pagar-light");
        fireButton("Por cobrar");

        // ── 3. Formulario de creación ─────────────────────────────
        fireButton("Nueva obligación");
        snap("03-formulario-crear-light");
        // ── 4. Validación: submit vacío ───────────────────────────
        fireButtonContaining("Crear obligación");
        snap("04-validacion-formulario-vacio-light");
        closeModal();
        settle();

        // ── 5. Detalle + historial ────────────────────────────────
        clickCardByCounterparty("Pedro Gómez");
        snap("05-detalle-historial-light");

        // ── 6. Formulario registrar cobro ─────────────────────────
        fireButton("Registrar cobro");
        snap("06-formulario-registrar-cobro-light");
        // Popups del modal: cuenta, categoría financiera, calendario
        openComboPopup(0);
        snapPopup("06b-popup-combo-cuenta-light");
        closePopups();
        openComboPopup(1);
        snapPopup("06c-popup-combo-categoria-light");
        closePopups();
        openDatePickerPopup();
        snapPopup("06d-popup-datepicker-light");
        closePopups();
        closeModal();
        settle();

        // ── 7. Editar obligación (pen del actionsRow del detalle) ─
        clickCardByCounterparty("Pedro Gómez");
        fireIconButtonLast("fas-pen");
        snap("07-formulario-editar-obligacion-light");
        closeModal();
        settle();

        // ── 8. Cancelar obligación (Alert bloqueante, ventana propia) ─
        clickCardByCounterparty("Pedro Gómez");
        fireIconButtonDetached("fas-ban", true);
        sleep(700);
        snapTopWindowAndClose("08-confirmar-cancelar-obligacion-light");
        settle();

        // ── 9. Editar settlement (primer pen = fila de historial) ─
        clickCardByCounterparty("Pedro Gómez");
        fireIconButtonAt("fas-pen", 0);
        snap("09-formulario-editar-settlement-light");
        closeModal();
        settle();

        // ── 10. Eliminar settlement (Alert bloqueante, ventana propia) ─
        clickCardByCounterparty("Pedro Gómez");
        fireIconButtonDetached("fas-trash", false);
        sleep(700);
        snapTopWindowAndClose("10-confirmar-eliminar-settlement-light");
        settle();
        closeModal(); // cierra detalle
        settle();

        // ── 11. Transactions: tx enlazada protegida ───────────────
        // showTransactionsDialog usa showAndWait (nested event loop): los
        // runLater posteriores sí se ejecutan mientras el diálogo está abierto.
        Platform.runLater(() -> {
            try {
                DashboardTransactionsDialog.showTransactionsDialog(
                    session, txRepo, accountRepo, categoryRepo, dark, () -> {}
                );
            } catch (Throwable t) {
                t.printStackTrace();
            }
        });
        sleep(2000);
        fx(() -> {
            javafx.stage.Window w = javafx.stage.Window.getWindows().stream()
                .filter(x -> x != stage && x.isShowing())
                .findFirst().orElse(null);
            if (w instanceof Stage s) {
                try {
                    WritableImage img = s.getScene().snapshot(null);
                    File out = OUT.resolve("11-transactions-tx-enlazada-light.png").toFile();
                    ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", out);
                    System.out.println("OK " + out.getName());
                } catch (Exception e) {
                    e.printStackTrace();
                }
                s.close();
            } else {
                System.out.println("[WARN] dialogo transactions no encontrado");
            }
        });
        sleep(400);

        // ── 12. Tema oscuro: lista + detalle + formulario ─────────
        openDashboardLike(false);
        snap("12-lista-por-cobrar-dark");
        clickLabelStartingWith("Canceladas (");
        snapScrollContent("12b-seccion-canceladas-expandida-dark");
        clickCardByCounterparty("Pedro Gómez");
        snap("13-detalle-historial-dark");
        fireButton("Registrar cobro");
        snap("14-formulario-registrar-cobro-dark");
        openComboPopup(0);
        snapPopup("14b-popup-combo-cuenta-dark");
        closePopups();
        openDatePickerPopup();
        snapPopup("14c-popup-datepicker-dark");
        closePopups();
        closeModal();
        settle();
        closeModal();

        // ── 13. Estado vacío (usuario sin obligaciones) ───────────
        openEmptyView();
        snap("15-estado-vacio-light");

        fx(Platform::exit);
        System.out.println("Screenshots en: " + OUT.toAbsolutePath());
        System.exit(0);
    }

    // ══════════════════════════════════════════════════════════
    //  Datos
    // ══════════════════════════════════════════════════════════
    private static void setupDatabase() throws Exception {
        Path dbPath = Path.of("target", "obligations-visual.db");
        Files.deleteIfExists(dbPath);
        database = new SqliteDatabase(dbPath);
        AppSchema.init(database);
        new UserRepository(database).upsert(UID, "visual@xpendz.dev");
        accountRepo = new AccountRepository(database);
        categoryRepo = new CategoryRepository(database);
        txRepo = new TransactionRepository(database);
        obligationRepo = new ObligationRepository(database);
        settlementRepo = new ObligationSettlementRepository(database);
        service = new ObligationService(database, obligationRepo, settlementRepo, txRepo);
        session = new AuthSession(UID, "visual@xpendz.dev", "Validación Visual", "t", "r", 0L);
    }

    private static void seed() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        accountId = accountRepo.create(UID, "Banco Principal", "BANK", "COP", null).id();
        cashAccountId = accountRepo.create(UID, "Caja", "CASH", "COP", null).id();
        incomeCatId = categoryRepo.create(UID, "Ventas", null, "INCOME", "fas-wallet").id();
        expenseCatId = categoryRepo.create(UID, "Servicios", null, "EXPENSE", "fas-briefcase").id();
        obligationCatId = categoryRepo.create(UID, "Clientes", null, "BOTH", "fas-users").id();

        txRepo.create(UID, accountId, incomeCatId, TransactionKind.INCOME.name(), 2_000_000L, now - 86_400 * 60, "Saldo inicial");
        txRepo.create(UID, cashAccountId, incomeCatId, TransactionKind.INCOME.name(), 800_000L, now - 86_400 * 60, "Saldo inicial");

        // Por cobrar: parcial, vencida, pendiente, pagada, cancelada
        ObligationRepository.Obligation parcial = service.createObligation(
            UID, ObligationRepository.TYPE_RECEIVABLE, "Compra de herramienta", "Pedro Gómez",
            "COP", 500_000L, now - 86_400 * 20, now + 86_400 * 10, obligationCatId, "FAC-100", "Crédito a 30 días");
        service.registerSettlement(UID, parcial.id(), accountId, incomeCatId, 200_000L, now - 86_400 * 12, "Primer abono");

        ObligationRepository.Obligation vencida = service.createObligation(
            UID, ObligationRepository.TYPE_RECEIVABLE, "Instalación de red", "Laura Martínez",
            "COP", 180_000L, now - 86_400 * 45, now - 86_400 * 5, obligationCatId, "FAC-087", null);

        service.createObligation(
            UID, ObligationRepository.TYPE_RECEIVABLE, "Mantenimiento mensual", "Cafetería Luna",
            "COP", 120_000L, now - 86_400 * 3, now + 86_400 * 27, obligationCatId, null, "Contrato anual");

        ObligationRepository.Obligation pagada = service.createObligation(
            UID, ObligationRepository.TYPE_RECEIVABLE, "Diseño de logo", "Andrés Ruiz",
            "COP", 90_000L, now - 86_400 * 30, now + 86_400 * 5, null, "FAC-091", null);
        service.registerSettlement(UID, pagada.id(), cashAccountId, incomeCatId, 90_000L, now - 86_400 * 8, "Pago completo");

        ObligationRepository.Obligation cancelada = service.createObligation(
            UID, ObligationRepository.TYPE_RECEIVABLE, "Reparación cancelada", "Cliente sin nombre",
            "COP", 60_000L, now - 86_400 * 15, null, null, null, "El cliente desistió");
        service.cancelObligation(UID, cancelada.id(), now - 86_400 * 2);

        // Por pagar
        ObligationRepository.Obligation porPagar = service.createObligation(
            UID, ObligationRepository.TYPE_PAYABLE, "Compra de inventario", "Distribuidora Norte",
            "COP", 600_000L, now - 86_400 * 18, now + 86_400 * 12, obligationCatId, "OC-204", "Pedido grande");
        service.registerSettlement(UID, porPagar.id(), accountId, expenseCatId, 250_000L, now - 86_400 * 6, "Abono inicial");

        service.createObligation(
            UID, ObligationRepository.TYPE_PAYABLE, "Arriendo oficina", "Inmobiliaria Centro",
            "COP", 350_000L, now - 86_400 * 2, now + 86_400 * 28, null, null, null);
    }

    // ══════════════════════════════════════════════════════════
    //  Escena
    // ══════════════════════════════════════════════════════════
    private static void openDashboardLike(boolean light) {
        dark = !light;
        fx(() -> {
            if (stage == null) {
                stage = new Stage();
                stage.setTitle("Xpendz — Validación visual Obligaciones");
            }
            boolean d = !light;
            Node content = ObligationsView.buildObligationsView(
                session, obligationRepo, settlementRepo, txRepo, accountRepo, categoryRepo,
                () -> d, () -> {}
            );
            HBox.setHgrow(content, Priority.ALWAYS);
            HBox root = new HBox(buildSidebar(), content);
            root.getStyleClass().add("app-root");
            scene = new Scene(root, 1280, 800);
            scene.getStylesheets().add(
                ObligationsScreenshotHarness.class.getResource(light ? "/styles/light.css" : "/styles/dark.css").toExternalForm()
            );
            stage.setScene(scene);
            if (!stage.isShowing()) stage.show();
        });
        settle();
    }

    private static void openEmptyView() {
        fx(() -> {
            AuthSession empty = new AuthSession("empty-visual-user", "empty@xpendz.dev", "Sin datos", "t", "r", 0L);
            try {
                new UserRepository(database).upsert("empty-visual-user", "empty@xpendz.dev");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            boolean d = dark;
            Node content = ObligationsView.buildObligationsView(
                empty, obligationRepo, settlementRepo, txRepo, accountRepo, categoryRepo,
                () -> d, () -> {}
            );
            HBox.setHgrow(content, Priority.ALWAYS);
            HBox root = new HBox(buildSidebar(), content);
            root.getStyleClass().add("app-root");
            scene = new Scene(root, 1280, 800);
            scene.getStylesheets().add(
                ObligationsScreenshotHarness.class.getResource(d ? "/styles/dark.css" : "/styles/light.css").toExternalForm()
            );
            stage.setScene(scene);
        });
        settle();
    }

    private static ScrollPane buildSidebar() {
        java.util.function.Function<String[], Button> nav = parts -> {
            Button b = new Button(parts[0]);
            b.getStyleClass().addAll("btn-primary", "nav-button");
            b.setMaxWidth(Double.MAX_VALUE);
            FontIcon icon = new FontIcon(parts[1]);
            icon.setIconSize(14);
            b.setGraphic(icon);
            b.setGraphicTextGap(8);
            return b;
        };
        Label syncStatus = new Label("Sincronizado");
        syncStatus.getStyleClass().add("sync-status-label");
        return DashboardSidebarPane.build(
            "Validación Visual", "visual@xpendz.dev",
            nav.apply(new String[]{"Inicio", "fas-home"}),
            nav.apply(new String[]{"Transacciones", "fas-exchange-alt"}),
            nav.apply(new String[]{"Transferencias", "fas-random"}),
            nav.apply(new String[]{"Resumen financiero", "fas-chart-line"}),
            nav.apply(new String[]{"Préstamos", "fas-hand-holding-usd"}),
            nav.apply(new String[]{"Obligaciones", "fas-file-invoice-dollar"}),
            nav.apply(new String[]{"Presupuesto y Metas", "fas-piggy-bank"}),
            nav.apply(new String[]{"Agregar cuenta", "fas-plus"}),
            nav.apply(new String[]{"Categorías", "fas-tags"}),
            nav.apply(new String[]{"Actualizar", "fas-sync"}),
            syncStatus,
            nav.apply(new String[]{"Cerrar sesión", "fas-sign-out-alt"}),
            nav.apply(new String[]{"Salir", "fas-times-circle"})
        );
    }

    // ══════════════════════════════════════════════════════════
    //  Interacción
    // ══════════════════════════════════════════════════════════
    private static boolean isShown(Node n) {
        for (Node p = n; p != null; p = p.getParent()) {
            if (!p.isVisible()) return false;
        }
        return true;
    }

    private static void fireButton(String text) {
        fx(() -> {
            for (Node n : scene.getRoot().lookupAll(".button")) {
                if (n instanceof Button b && text.equals(b.getText()) && isShown(b)) {
                    b.fire();
                    return;
                }
            }
            System.out.println("[WARN] botón no encontrado: " + text);
        });
        settle();
    }

    private static void fireButtonContaining(String text) {
        fx(() -> {
            for (Node n : scene.getRoot().lookupAll(".button")) {
                if (n instanceof Button b && b.getText() != null && b.getText().contains(text)
                        && isShown(b)) {
                    b.fire();
                    return;
                }
            }
            System.out.println("[WARN] botón no encontrado contiene: " + text);
        });
        settle();
    }

    private static List<Button> iconButtons(String literal) {
        List<Button> matches = new ArrayList<>();
        for (Node n : scene.getRoot().lookupAll(".button")) {
            if (n instanceof Button b && b.getGraphic() instanceof FontIcon f
                    && literal.equals(f.getIconLiteral()) && isShown(b)) {
                matches.add(b);
            }
        }
        return matches;
    }

    private static void fireIconButtonAt(String literal, int index) {
        fx(() -> {
            List<Button> matches = iconButtons(literal);
            if (index < matches.size()) {
                matches.get(index).fire();
            } else {
                System.out.println("[WARN] icon-button no encontrado: " + literal + " idx=" + index + " total=" + matches.size());
            }
        });
        settle();
    }

    private static void fireIconButtonLast(String literal) {
        fx(() -> {
            List<Button> matches = iconButtons(literal);
            if (!matches.isEmpty()) {
                matches.get(matches.size() - 1).fire();
            } else {
                System.out.println("[WARN] icon-button no encontrado (last): " + literal);
            }
        });
        settle();
    }

    /** Dispara sin esperar: para handlers que abren Alert.showAndWait (bloquean el task). */
    private static void fireIconButtonDetached(String literal, boolean last) {
        Platform.runLater(() -> {
            List<Button> matches = iconButtons(literal);
            if (matches.isEmpty()) {
                System.out.println("[WARN] icon-button no encontrado (detached): " + literal);
                return;
            }
            matches.get(last ? matches.size() - 1 : 0).fire();
        });
    }

    /** Captura la ventana-dialogo superior (Alert) y la cierra como Cancelar. */
    private static void snapTopWindowAndClose(String name) {
        fx(() -> {
            javafx.stage.Window w = javafx.stage.Window.getWindows().stream()
                .filter(x -> x != stage && x.isShowing())
                .reduce((a, b) -> b).orElse(null);
            if (w == null || w.getScene() == null) {
                System.out.println("[WARN] alert no encontrado: " + name);
                return;
            }
            try {
                WritableImage img = w.getScene().snapshot(null);
                File out = OUT.resolve(name + ".png").toFile();
                ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", out);
                System.out.println("OK " + out.getName());
            } catch (Exception e) {
                e.printStackTrace();
            }
            w.hide();
        });
    }

    private static void clickCardByCounterparty(String name) {
        fx(() -> {
            for (Node n : scene.getRoot().lookupAll("*")) {
                if (n instanceof Label l && name.equals(l.getText()) && isShown(l)) {
                    Node p = l.getParent();
                    while (p != null && p.getOnMouseClicked() == null) p = p.getParent();
                    if (p != null && isShown(p)) {
                        Bounds b = p.localToScene(p.getBoundsInLocal());
                        Event.fireEvent(p, new MouseEvent(
                            MouseEvent.MOUSE_CLICKED,
                            b.getCenterX(), b.getCenterY(), b.getCenterX(), b.getCenterY(),
                            MouseButton.PRIMARY, 1,
                            false, false, false, false, false,
                            true, false, false, true, false, null
                        ));
                        return;
                    }
                }
            }
            System.out.println("[WARN] card no encontrada para: " + name);
        });
        settle();
    }

    private static void scrollListToBottom() {
        fx(() -> {
            for (Node n : scene.getRoot().lookupAll(".scroll-pane")) {
                if (n instanceof ScrollPane sp && isShown(sp) && sp.getVmax() > 0) {
                    sp.setVvalue(sp.getVmax());
                    sp.layout();
                    return;
                }
            }
            System.out.println("[WARN] scroll-pane no encontrado");
        });
        sleep(200);
        settle();
    }

    /** Captura el contenido completo del ScrollPane de la lista, incluido lo que queda fuera del viewport. */
    private static void snapScrollContent(String name) {
        fx(() -> {
            for (Node n : scene.getRoot().lookupAll(".scroll-pane")) {
                if (!(n instanceof ScrollPane sp) || !isShown(sp) || sp.getContent() == null) continue;
                Node content = sp.getContent();
                if (content.getBoundsInLocal().getHeight() <= 300) continue;
                boolean hasObligationSection = false;
                for (Node sub : content.lookupAll(".label")) {
                    if (sub instanceof Label l && l.getText() != null
                            && (l.getText().equals("Activas") || l.getText().startsWith("Canceladas ("))) {
                        hasObligationSection = true;
                        break;
                    }
                }
                if (!hasObligationSection) continue;
                try {
                    WritableImage img = content.snapshot(null, null);
                    File out = OUT.resolve(name + ".png").toFile();
                    ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", out);
                    System.out.println("OK " + out.getName());
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return;
            }
            System.out.println("[WARN] scroll-content no encontrado");
        });
        settle();
    }

    private static void clickLabelStartingWith(String prefix) {
        fx(() -> {
            for (Node n : scene.getRoot().lookupAll(".label")) {
                if (n instanceof Label l && l.getText() != null && l.getText().startsWith(prefix)
                        && l.getOnMouseClicked() != null && isShown(l)) {
                    Bounds b = l.localToScene(l.getBoundsInLocal());
                    Event.fireEvent(l, new MouseEvent(
                        MouseEvent.MOUSE_CLICKED,
                        b.getCenterX(), b.getCenterY(), b.getCenterX(), b.getCenterY(),
                        MouseButton.PRIMARY, 1,
                        false, false, false, false, false,
                        true, false, false, true, false, null
                    ));
                    return;
                }
            }
            System.out.println("[WARN] label no encontrado: " + prefix);
        });
        settle();
    }

    private static void closeModal() {
        fx(() -> {
            // Botón de cierre del header del modal (fas-times) o botón Cancelar/Volver.
            for (Node n : scene.getRoot().lookupAll(".button")) {
                if (n instanceof Button b && b.getGraphic() instanceof FontIcon f
                        && "fas-times".equals(f.getIconLiteral()) && isShown(b)) {
                    b.fire();
                    return;
                }
            }
            for (Node n : scene.getRoot().lookupAll(".button")) {
                if (n instanceof Button b && b.getText() != null
                        && (b.getText().equals("Cancelar") || b.getText().equals("Volver"))
                        && isShown(b)) {
                    b.fire();
                    return;
                }
            }
        });
        settle();
    }

    /** Abre el popup del ComboBox visible #index dentro del modal activo. */
    private static void openComboPopup(int index) {
        fx(() -> {
            List<javafx.scene.control.ComboBox<?>> found = new ArrayList<>();
            for (Node n : scene.getRoot().lookupAll(".combo-box")) {
                if (n instanceof javafx.scene.control.ComboBox<?> c && isShown(c)) {
                    found.add(c);
                }
            }
            if (index < found.size()) {
                found.get(index).show();
            } else {
                System.out.println("[WARN] combo idx=" + index + " no encontrado, total=" + found.size());
            }
        });
        sleep(500);
    }

    /** Abre el popup del primer DatePicker visible dentro del modal activo. */
    private static void openDatePickerPopup() {
        fx(() -> {
            for (Node n : scene.getRoot().lookupAll(".date-picker")) {
                if (n instanceof javafx.scene.control.DatePicker dp && isShown(dp)) {
                    dp.show();
                    return;
                }
            }
            System.out.println("[WARN] datepicker no encontrado");
        });
        sleep(600);
    }

    /** Captura la ventana popup (ComboBox/DatePicker) actualmente visible. */
    private static void snapPopup(String name) {
        fx(() -> {
            javafx.stage.Window w = javafx.stage.Window.getWindows().stream()
                .filter(x -> x != stage && x.isShowing())
                .reduce((a, b) -> b)
                .orElse(null);
            if (w == null || w.getScene() == null) {
                System.out.println("[WARN] popup no encontrado: " + name);
                return;
            }
            try {
                WritableImage img = w.getScene().snapshot(null);
                File out = OUT.resolve(name + ".png").toFile();
                ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", out);
                System.out.println("OK " + out.getName());
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        sleep(150);
    }

    private static void closePopups() {
        fx(() -> javafx.stage.Window.getWindows().forEach(w -> {
            if (w != stage && w.isShowing()) w.hide();
        }));
        settle();
    }

    // ══════════════════════════════════════════════════════════
    //  Utilidades FX
    // ══════════════════════════════════════════════════════════
    private static void fx(Runnable r) {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                latch.countDown();
            }
        });
        await(latch);
    }

    private static void settle() {
        fx(() -> {});
        fx(() -> {});
        sleep(350);
    }

    private static void snap(String name) {
        fx(() -> {
            try {
                WritableImage img = scene.snapshot(null);
                File out = OUT.resolve(name + ".png").toFile();
                ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", out);
                System.out.println("OK " + out.getName());
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        sleep(120);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ObligationsScreenshotHarness() {
    }
}
