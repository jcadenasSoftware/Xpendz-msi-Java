package com.myfinaces.ui;

import com.myfinaces.auth.AuthSession;
import com.myfinaces.db.CategoryRepository;
import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * CategoriesView - Vista principal maestro-detalle PREMIUM para gestion de categorias.
 * 
 * FASE 3: Drawer contextual premium con:
 * - Selectores visuales de iconos y colores
 * - Preview en tiempo real
 * - Formularios para crear/editar categorias y subcategorias
 * - Microinteracciones modernas
 * - Validaciones visuales elegantes
 */
public final class CategoriesView {

    private CategoriesView() {
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PALETAS Y CATALOGOS VISUALES
    // ═══════════════════════════════════════════════════════════════════════
    
    // Paleta de 24 colores premium para categorias
    private static final String[] CATEGORY_COLORS = {
        "#EF4444", // Red 500
        "#F97316", // Orange 500
        "#F59E0B", // Amber 500
        "#84CC16", // Lime 500
        "#10B981", // Emerald 500
        "#14B8A6", // Teal 500
        "#06B6D4", // Cyan 500
        "#3B82F6", // Blue 500
        "#6366F1", // Indigo 500
        "#8B5CF6", // Violet 500
        "#A855F7", // Purple 500
        "#D946EF", // Fuchsia 500
        "#EC4899", // Pink 500
        "#F43F5E", // Rose 500
        "#64748B", // Slate 500
        "#94A3B8", // Slate 400
        "#DC2626", // Red 600
        "#EA580C", // Orange 600
        "#D97706", // Amber 600
        "#65A30D", // Lime 600
        "#059669", // Emerald 600
        "#0D9488", // Teal 600
        "#0891B2", // Cyan 600
        "#2563EB", // Blue 600
    };
    
    // Catalogo de iconos FontAwesome 5 validos
    private static final String[] AVAILABLE_ICONS = {
        // Finanzas
        "fas-wallet", "fas-credit-card", "fas-money-bill", "fas-coins", "fas-piggy-bank",
        "fas-chart-line", "fas-chart-pie", "fas-chart-bar", "fas-dollar-sign", "fas-euro-sign",
        "fas-pound-sign", "fas-receipt", "fas-file-invoice", "fas-file-invoice-dollar",
        // Transporte
        "fas-car", "fas-bus", "fas-train", "fas-plane", "fas-gas-pump",
        "fas-bicycle", "fas-motorcycle", "fas-taxi", "fas-ship", "fas-truck",
        "fas-rocket", "fas-road", "fas-traffic-light", "fas-subway",
        // Hogar
        "fas-home", "fas-building", "fas-couch", "fas-bed",
        "fas-bath", "fas-utensils", "fas-lightbulb", "fas-fan",
        "fas-door-open", "fas-door-closed", "fas-window-maximize", "fas-window-minimize",
        // Salud
        "fas-heartbeat", "fas-heart", "fas-user-md", "fas-pills", "fas-hospital",
        "fas-tooth", "fas-eye", "fas-stethoscope", "fas-syringe",
        "fas-ambulance", "fas-medkit", "fas-briefcase-medical", "fas-hand-holding-medical",
        // Tecnologia
        "fas-laptop", "fas-mobile", "fas-wifi", "fas-gamepad",
        "fas-camera", "fas-headphones", "fas-tv", "fas-print", "fas-keyboard",
        "fas-mouse", "fas-microphone", "fas-volume-up", "fas-volume-down", "fas-volume-mute",
        // Ocio
        "fas-film", "fas-music", "fas-book", "fas-gamepad",
        "fas-dumbbell", "fas-gift", "fas-paw", "fas-dice",
        "fas-puzzle-piece", "fas-chess", "fas-check", "fas-star",
        // Educacion y Trabajo
        "fas-graduation-cap", "fas-book-open", "fas-briefcase", "fas-university",
        "fas-pencil-alt", "fas-calculator", "fas-flask", "fas-search", "fas-globe",
        "fas-landmark", "fas-school", "fas-chalkboard-teacher",
        // Ropa y Belleza
        "fas-gem", "fas-shopping-cart", "fas-shopping-bag", "fas-store",
        "fas-magic", "fas-hand-sparkles", "fas-cut", "fas-tshirt",
        // Otros
        "fas-leaf", "fas-sun", "fas-moon", "fas-cloud",
        "fas-bolt", "fas-fire", "fas-tint", "fas-coffee", "fas-beer",
        "fas-wine-glass", "fas-glass-martini", "fas-utensils", "fas-hamburger"
    };
    
    // Iconos por defecto segun tipo
    private static final String DEFAULT_INCOME_ICON = "fas-arrow-up";
    private static final String DEFAULT_EXPENSE_ICON = "fas-arrow-down";
    private static final String DEFAULT_CATEGORY_ICON = "fas-tag";

    // ═══════════════════════════════════════════════════════════════════════
    // HELPERS VISUALES
    // ═══════════════════════════════════════════════════════════════════════
    
    private static String getCategoryColor(String categoryId, int index) {
        if (categoryId == null) return CATEGORY_COLORS[0];
        int hash = categoryId.hashCode();
        return CATEGORY_COLORS[Math.abs(hash) % CATEGORY_COLORS.length];
    }
    
    private static String getCategoryIcon(String kind) {
        if ("INCOME".equalsIgnoreCase(kind)) return DEFAULT_INCOME_ICON;
        if ("EXPENSE".equalsIgnoreCase(kind)) return DEFAULT_EXPENSE_ICON;
        return DEFAULT_CATEGORY_ICON;
    }
    
    private static Color getContrastColor(String hexColor) {
        try {
            Color c = Color.web(hexColor);
            // Calcular luminosidad relativa
            double luminance = (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue());
            return luminance > 0.5 ? Color.BLACK : Color.WHITE;
        } catch (Exception e) {
            return Color.WHITE;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // VISTA PRINCIPAL
    // ═══════════════════════════════════════════════════════════════════════
    
    public static Node buildCategoriesView(
            AuthSession session,
            CategoryRepository categoryRepo,
            BooleanSupplier darkTheme,
            Runnable refreshCallback
    ) {
        String userUid = session.uid();
        boolean isDark = darkTheme.getAsBoolean();

        SideDrawer sideDrawer = new SideDrawer();

        // ═══════════════════════════════════════════════════════════════════
        // TOOLBAR SUPERIOR PREMIUM
        // ═══════════════════════════════════════════════════════════════════
        Label title = new Label("Categorías");
        title.setStyle("-fx-font-size: 32px; -fx-font-weight: 800; -fx-text-fill: #0F172A; -fx-letter-spacing: -1px;");
        
        Label subtitle = new Label("Organiza y personaliza tus ingresos y gastos");
        subtitle.setStyle("-fx-font-size: 15px; -fx-text-fill: #64748B;");
        
        VBox titleBox = new VBox(6, title, subtitle);
        titleBox.setAlignment(Pos.CENTER_LEFT);

        // Búsqueda premium integrada
        FontIcon searchIcon = new FontIcon("fas-search");
        searchIcon.setIconSize(16);
        searchIcon.setIconColor(Color.web("#94A3B8"));
        
        TextField searchField = new TextField();
        searchField.setPromptText("Buscar categoría...");
        searchField.setPrefWidth(340);
        searchField.setStyle(
            "-fx-background-color: " + (isDark ? "rgba(255,255,255,0.06)" : "#F1F5F9") + "; " +
            "-fx-background-radius: 14; " +
            "-fx-border-radius: 14; " +
            "-fx-border-color: " + (isDark ? "rgba(255,255,255,0.08)" : "#E2E8F0") + "; " +
            "-fx-border-width: 1.5; " +
            "-fx-padding: 14 18 14 44; " +
            "-fx-font-size: 14px; " +
            "-fx-prompt-text-fill: #94A3B8;"
        );
        
        StackPane searchWrapper = new StackPane(searchIcon, searchField);
        searchWrapper.setAlignment(Pos.CENTER_LEFT);
        StackPane.setMargin(searchIcon, new Insets(0, 0, 0, 16));
        
        // Botón Nueva Categoria premium
        FontIcon plusIcon = new FontIcon("fas-plus");
        plusIcon.setIconSize(14);
        plusIcon.setIconColor(Color.WHITE);
        
        Button btnNewCategory = new Button("Nueva categoría");
        btnNewCategory.setGraphic(plusIcon);
        btnNewCategory.setGraphicTextGap(10);
        btnNewCategory.setStyle(
            "-fx-background-color: linear-gradient(to right, #1E6DFF, #3B82F6); " +
            "-fx-text-fill: white; " +
            "-fx-font-weight: 700; " +
            "-fx-font-size: 14px; " +
            "-fx-padding: 14 24; " +
            "-fx-background-radius: 14; " +
            "-fx-cursor: hand; " +
            "-fx-effect: dropshadow(gaussian, rgba(30,109,255,0.3), 12, 0, 0, 4);"
        );
        
        // Efecto hover premium
        btnNewCategory.setOnMouseEntered(e -> {
            btnNewCategory.setStyle(
                "-fx-background-color: linear-gradient(to right, #1D4ED8, #2563EB); " +
                "-fx-text-fill: white; " +
                "-fx-font-weight: 700; " +
                "-fx-font-size: 14px; " +
                "-fx-padding: 14 24; " +
                "-fx-background-radius: 14; " +
                "-fx-cursor: hand; " +
                "-fx-effect: dropshadow(gaussian, rgba(30,109,255,0.4), 16, 0, 0, 6);"
            );
            ScaleTransition st = new ScaleTransition(Duration.millis(200), btnNewCategory);
            st.setToX(1.03);
            st.setToY(1.03);
            st.play();
        });
        btnNewCategory.setOnMouseExited(e -> {
            btnNewCategory.setStyle(
                "-fx-background-color: linear-gradient(to right, #1E6DFF, #3B82F6); " +
                "-fx-text-fill: white; " +
                "-fx-font-weight: 700; " +
                "-fx-font-size: 14px; " +
                "-fx-padding: 14 24; " +
                "-fx-background-radius: 14; " +
                "-fx-cursor: hand; " +
                "-fx-effect: dropshadow(gaussian, rgba(30,109,255,0.3), 12, 0, 0, 4);"
            );
            ScaleTransition st = new ScaleTransition(Duration.millis(200), btnNewCategory);
            st.setToX(1.0);
            st.setToY(1.0);
            st.play();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox toolbar = new HBox(24, titleBox, spacer, searchWrapper, btnNewCategory);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(0, 0, 32, 0));

        // ═══════════════════════════════════════════════════════════════════
        // PANEL IZQUIERDO - CATEGORIAS (Maestro)
        // ═══════════════════════════════════════════════════════════════════
        Label categoriesHeader = new Label("TUS CATEGORÍAS");
        categoriesHeader.setStyle("-fx-font-size: 11px; -fx-font-weight: 800; -fx-text-fill: #94A3B8; -fx-letter-spacing: 1.5px;");
        
        VBox categoriesList = new VBox(8);
        categoriesList.setFillWidth(false);
        categoriesList.setPrefWidth(340);
        categoriesList.setMaxWidth(340);
        
        ScrollPane categoriesScroll = new ScrollPane(categoriesList);
        categoriesScroll.setFitToWidth(false);
        categoriesScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        categoriesScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        categoriesScroll.setStyle("-fx-background-color: transparent; -fx-padding: 0;");
        
        VBox leftPanel = new VBox(20, categoriesHeader, categoriesScroll);
        leftPanel.setPrefWidth(400);
        leftPanel.setMinWidth(400);
        leftPanel.setMaxWidth(400);
        leftPanel.setPadding(new Insets(16, 20, 16, 20));
        leftPanel.setStyle(
            "-fx-background-color: " + (isDark ? "rgba(255,255,255,0.02)" : "#FAFBFC") + "; " +
            "-fx-background-radius: 20;"
        );
        VBox.setVgrow(categoriesScroll, Priority.ALWAYS);

        // ═══════════════════════════════════════════════════════════════════
        // PANEL CENTRAL - SUBCATEGORIAS (Detalle)
        // ═══════════════════════════════════════════════════════════════════
        FontIcon emptyIcon = new FontIcon("fas-layer-group");
        emptyIcon.setIconSize(72);
        emptyIcon.setIconColor(Color.web("#CBD5E1"));
        
        Label emptyTitle = new Label("Selecciona una categoría");
        emptyTitle.setStyle("-fx-font-size: 22px; -fx-font-weight: 700; -fx-text-fill: #334155;");
        
        Label emptyDesc = new Label("Elige una categoría del panel izquierdo para ver y gestionar sus subcategorías de forma inteligente.");
        emptyDesc.setStyle("-fx-font-size: 15px; -fx-text-fill: #64748B; -fx-wrap-text: true; -fx-text-alignment: center;");
        emptyDesc.setMaxWidth(400);
        emptyDesc.setWrapText(true);
        emptyDesc.setAlignment(Pos.CENTER);
        
        Button emptyActionBtn = new Button("Crear primera categoría");
        emptyActionBtn.setStyle(
            "-fx-background-color: transparent; " +
            "-fx-border-color: #1E6DFF; " +
            "-fx-border-width: 2; " +
            "-fx-border-radius: 12; " +
            "-fx-text-fill: #1E6DFF; " +
            "-fx-font-weight: 700; " +
            "-fx-font-size: 14px; " +
            "-fx-padding: 14 28; " +
            "-fx-cursor: hand;"
        );
        
        VBox emptyState = new VBox(20, emptyIcon, emptyTitle, emptyDesc, emptyActionBtn);
        emptyState.setAlignment(Pos.CENTER);
        emptyState.setPadding(new Insets(80));
        
        FlowPane subcategoriesGrid = new FlowPane();
        subcategoriesGrid.setHgap(20);
        subcategoriesGrid.setVgap(20);
        subcategoriesGrid.setPrefWrapLength(900);
        subcategoriesGrid.setAlignment(Pos.TOP_LEFT);
        
        VBox subcategoriesWrapper = new VBox(subcategoriesGrid);
        subcategoriesWrapper.setFillWidth(true);
        subcategoriesWrapper.setVisible(false);
        subcategoriesWrapper.setManaged(false);
        
        ScrollPane detailScroll = new ScrollPane(subcategoriesWrapper);
        detailScroll.setFitToWidth(true);
        detailScroll.setFitToHeight(false);
        detailScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        detailScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        detailScroll.setStyle("-fx-background-color: transparent; -fx-padding: 0;");
        detailScroll.setMinHeight(300);
        detailScroll.setPrefHeight(600);
        detailScroll.setVisible(false);
        detailScroll.setManaged(false);
        
        StackPane centerPanel = new StackPane(emptyState, detailScroll);
        centerPanel.setPadding(new Insets(0, 0, 0, 16));
        centerPanel.setMinWidth(400);
        centerPanel.setPrefWidth(600);
        centerPanel.setMinHeight(400);
        centerPanel.setPrefHeight(600);
        VBox.setVgrow(centerPanel, Priority.ALWAYS);
        HBox.setHgrow(centerPanel, Priority.ALWAYS);

        // Layout principal
        HBox mainContent = new HBox(24, leftPanel, centerPanel);
        mainContent.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(centerPanel, Priority.ALWAYS);
        VBox.setVgrow(mainContent, Priority.ALWAYS);

        VBox content = new VBox(toolbar, mainContent);
        content.setFillWidth(true);
        content.setPadding(new Insets(28, 32, 28, 32));
        VBox.setVgrow(mainContent, Priority.ALWAYS);

        // ═══════════════════════════════════════════════════════════════════
        // ESTADO Y REFERENCIAS
        // ═══════════════════════════════════════════════════════════════════
        AtomicReference<CategoryRepository.Category> selectedCategoryRef = new AtomicReference<>();
        AtomicReference<List<CategoryRepository.Category>> allCategoriesRef = new AtomicReference<>(new ArrayList<>());
        AtomicReference<Map<String, Integer>> subcategoryCountsRef = new AtomicReference<>(new HashMap<>());
        AtomicReference<Runnable> loadCategoriesRef = new AtomicReference<>();
        
        // ═══════════════════════════════════════════════════════════════════
        // BUILDER DE TARJETA DE CATEGORIA PREMIUM
        // ═══════════════════════════════════════════════════════════════════
        java.util.function.Function<CategoryRepository.Category, Node> buildCategoryCard = cat -> {
            String kind = cat.kind();
            String color = getCategoryColor(cat.id(), allCategoriesRef.get().indexOf(cat));
            String iconLiteral = cat.icon() != null && !cat.icon().isBlank() ? cat.icon() : getCategoryIcon(kind);
            int subCount = subcategoryCountsRef.get().getOrDefault(cat.id(), 0);
            
            // Icono circular con fondo
            FontIcon catIcon = new FontIcon(iconLiteral);
            catIcon.setIconSize(22);
            catIcon.setIconColor(Color.web(color));
            
            Circle iconBg = new Circle(24);
            iconBg.setFill(Color.web(color, 0.15));
            
            StackPane iconContainer = new StackPane(iconBg, catIcon);
            iconContainer.setMinSize(40, 40);
            iconContainer.setMaxSize(40, 40);
            
            // Nombre en dos lineas si es necesario (no truncar, respetar palabras completas)
            Label nameLabel = new Label(cat.name());
            nameLabel.setStyle("-fx-font-weight: 700; -fx-font-size: 14px; -fx-text-fill: #1E293B;");
            nameLabel.setWrapText(true);
            nameLabel.setMaxWidth(130);
            nameLabel.setMinHeight(18);
            
            // Badge tipo
            String typeText = "INCOME".equalsIgnoreCase(kind) ? "Ingreso" : "Gasto";
            String typeColor = "INCOME".equalsIgnoreCase(kind) ? "#10B981" : "#EF4444";
            Label typeBadge = new Label(typeText);
            typeBadge.setStyle(
                "-fx-font-size: 9px; -fx-font-weight: 800; " +
                "-fx-text-fill: " + typeColor + "; " +
                "-fx-background-color: " + typeColor + "15; " +
                "-fx-padding: 3 8; " +
                "-fx-background-radius: 5;"
            );
            
            // Contador
            Label countLabel = new Label(subCount + " sub" + (subCount != 1 ? "s" : ""));
            countLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #94A3B8; -fx-font-weight: 500;");
            
            HBox topRow = new HBox(8, nameLabel, typeBadge);
            topRow.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(nameLabel, Priority.ALWAYS);
            
            VBox textBox = new VBox(6, topRow, countLabel);
            textBox.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(textBox, Priority.ALWAYS);
            
            // Botones de acción (aparecen en hover)
            FontIcon editIcon = new FontIcon("fas-pencil-alt");
            editIcon.setIconSize(12);
            editIcon.setIconColor(Color.web("#94A3B8"));
            
            Button editBtn = new Button();
            editBtn.setGraphic(editIcon);
            editBtn.setStyle(
                "-fx-background-color: transparent; " +
                "-fx-padding: 10; " +
                "-fx-cursor: hand; " +
                "-fx-opacity: 0.7;"
            );
            
            // Handler para editar categoría
            editBtn.setOnAction(e -> {
                e.consume();
                Runnable refresher = loadCategoriesRef.get();
                VBox editForm = buildEditCategoryFormPremium(sideDrawer, session, categoryRepo, cat, refresher != null ? refresher : () -> {}, isDark);
                sideDrawer.register("edit-category", isDark, editForm);
                sideDrawer.show("edit-category");
            });
            
            // Botón eliminar
            FontIcon deleteIcon = new FontIcon("fas-trash");
            deleteIcon.setIconSize(12);
            deleteIcon.setIconColor(Color.web("#EF4444"));
            
            Button deleteBtn = new Button();
            deleteBtn.setGraphic(deleteIcon);
            deleteBtn.setStyle(
                "-fx-background-color: transparent; " +
                "-fx-padding: 10; " +
                "-fx-cursor: hand; " +
                "-fx-opacity: 0;"
            );
            
            HBox actionBtns = new HBox(4, editBtn, deleteBtn);
            
            HBox card = new HBox(10, iconContainer, textBox, actionBtns);
            card.setAlignment(Pos.CENTER_LEFT);
            card.setPadding(new Insets(12, 12, 12, 14));
            card.setSpacing(8);
            // Fijar ancho para evitar deformación - tarjeta con espacio suficiente
            card.setPrefWidth(340);
            card.setMinWidth(340);
            card.setMaxWidth(340);
            card.setStyle(
                "-fx-background-radius: 16; " +
                "-fx-cursor: hand; " +
                "-fx-background-color: " + (isDark ? "rgba(255,255,255,0.05)" : "#FFFFFF") + "; " +
                "-fx-border-radius: 16; " +
                "-fx-border-color: " + (isDark ? "rgba(255,255,255,0.08)" : "#E2E8F0") + "; " +
                "-fx-border-width: 1.5; " +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.04), 10, 0, 0, 2);"
            );
            card.setUserData(cat);
            
            // Hover premium con botones de acción
            card.setOnMouseEntered(e -> {
                boolean isSelected = cat.equals(selectedCategoryRef.get());
                if (!isSelected) {
                    card.setStyle(
                        "-fx-background-radius: 16; " +
                        "-fx-cursor: hand; " +
                        "-fx-background-color: " + (isDark ? "rgba(255,255,255,0.08)" : "#F8FAFC") + "; " +
                        "-fx-border-radius: 16; " +
                        "-fx-border-color: " + (isDark ? "rgba(255,255,255,0.12)" : "#CBD5E1") + "; " +
                        "-fx-border-width: 1.5; " +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.08), 16, 0, 0, 4);"
                    );
                }
                editBtn.setStyle(
                    "-fx-background-color: " + (isDark ? "rgba(255,255,255,0.1)" : "#F1F5F9") + "; " +
                    "-fx-padding: 10; " +
                    "-fx-cursor: hand; " +
                    "-fx-background-radius: 8; " +
                    "-fx-opacity: 1;"
                );
                deleteBtn.setStyle(
                    "-fx-background-color: " + (isDark ? "rgba(255,255,255,0.1)" : "#FEF2F2") + "; " +
                    "-fx-padding: 10; " +
                    "-fx-cursor: hand; " +
                    "-fx-background-radius: 8; " +
                    "-fx-opacity: 1;"
                );
                FadeTransition ft = new FadeTransition(Duration.millis(150), actionBtns);
                ft.setToValue(1);
                ft.play();
            });
            card.setOnMouseExited(e -> {
                boolean isSelected = cat.equals(selectedCategoryRef.get());
                if (!isSelected) {
                    card.setStyle(
                        "-fx-background-radius: 16; " +
                        "-fx-cursor: hand; " +
                        "-fx-background-color: " + (isDark ? "rgba(255,255,255,0.05)" : "#FFFFFF") + "; " +
                        "-fx-border-radius: 16; " +
                        "-fx-border-color: " + (isDark ? "rgba(255,255,255,0.08)" : "#E2E8F0") + "; " +
                        "-fx-border-width: 1.5; " +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.04), 10, 0, 0, 2);"
                    );
                } else {
                    // Mantener estilo seleccionado
                    card.setStyle(
                        "-fx-background-radius: 16; " +
                        "-fx-cursor: hand; " +
                        "-fx-border-radius: 16; " +
                        "-fx-border-width: 1.5; " +
                        "-fx-background-color: " + (isDark ? "rgba(30,109,255,0.12)" : "#EFF6FF") + "; " +
                        "-fx-border-color: #3B82F6; " +
                        "-fx-effect: dropshadow(gaussian, rgba(59,130,246,0.25), 15, 0, 0, 4);"
                    );
                }
                editBtn.setStyle(
                    "-fx-background-color: transparent; " +
                    "-fx-padding: 10; " +
                    "-fx-cursor: hand; " +
                    "-fx-opacity: 1;"
                );
                deleteBtn.setStyle(
                    "-fx-background-color: transparent; " +
                    "-fx-padding: 10; " +
                    "-fx-cursor: hand; " +
                    "-fx-opacity: 0;"
                );
            });
            
            // Handler para eliminar categoría
            deleteBtn.setOnAction(e -> {
                e.consume();
                showDeleteCategoryConfirmation(sideDrawer, session, categoryRepo, cat, 
                    loadCategoriesRef.get() != null ? loadCategoriesRef.get() : () -> {}, isDark);
            });
            
            // Click handler
            card.setOnMouseClicked(e -> {
                // Verificar si el click fue en alguno de los botones de acción
                Node target = (Node) e.getTarget();
                if (target == editBtn || target == editIcon || 
                    (target instanceof Node && ((Node)target).getParent() == editBtn)) {
                    // Abrir drawer de edición
                    Runnable refresher = loadCategoriesRef.get();
                    VBox editForm = buildEditCategoryFormPremium(sideDrawer, session, categoryRepo, cat, refresher != null ? refresher : () -> {}, isDark);
                    sideDrawer.register("edit-category", isDark, editForm);
                    sideDrawer.show("edit-category");
                    return;
                }
                if (target == deleteBtn || target == deleteIcon || 
                    (target instanceof Node && ((Node)target).getParent() == deleteBtn)) {
                    // El handler del botón ya maneja esto
                    return;
                }
                
                selectedCategoryRef.set(cat);
                
                // Actualizar estilos visuales (manteniendo padding y spacing consistentes)
                for (Node node : categoriesList.getChildren()) {
                    if (node instanceof HBox c) {
                        CategoryRepository.Category catData = (CategoryRepository.Category) c.getUserData();
                        boolean isSelected = cat.equals(catData);
                        // Solo propiedades de estilo visual, NO padding/spacing (ya definidos en el HBox)
                        String baseStyle = "-fx-background-radius: 16; -fx-cursor: hand; " +
                            "-fx-border-radius: 16; " +
                            "-fx-border-width: 1.5; ";
                        
                        if (isSelected) {
                            c.setStyle(baseStyle + 
                                "-fx-background-color: " + (isDark ? "rgba(30,109,255,0.12)" : "#EFF6FF") + "; " +
                                "-fx-border-color: #3B82F6; " +
                                "-fx-effect: dropshadow(gaussian, rgba(59,130,246,0.25), 15, 0, 0, 4);"
                            );
                        } else {
                            c.setStyle(baseStyle + 
                                "-fx-background-color: " + (isDark ? "rgba(255,255,255,0.05)" : "#FFFFFF") + "; " +
                                "-fx-border-color: " + (isDark ? "rgba(255,255,255,0.08)" : "#E2E8F0") + "; " +
                                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.04), 10, 0, 0, 2);"
                            );
                        }
                    }
                }
                
                Runnable globalRefresher = loadCategoriesRef.get();
                Runnable reloadSubs = () -> showSubcategoriesPremium(cat, subcategoriesGrid, subcategoriesWrapper, detailScroll, emptyState,
                    userUid, categoryRepo, isDark, sideDrawer, session,
                    globalRefresher != null ? globalRefresher : () -> {});
                showSubcategoriesPremium(cat, subcategoriesGrid, subcategoriesWrapper, detailScroll, emptyState, 
                    userUid, categoryRepo, isDark, sideDrawer, session, reloadSubs);
            });
            
            return card;
        };

        // ═══════════════════════════════════════════════════════════════════
        // CARGA DE CATEGORIAS
        // ═══════════════════════════════════════════════════════════════════
        Runnable loadCategories = () -> {
            System.out.println("[DEBUG] Iniciando carga de categorías para userUid: " + userUid);
            try {
                List<CategoryRepository.Category> roots = categoryRepo.listRoots(userUid);
                System.out.println("[DEBUG] Categorías raíz cargadas: " + roots.size());
                allCategoriesRef.set(roots);
                
                Map<String, Integer> counts = new HashMap<>();
                for (CategoryRepository.Category cat : roots) {
                    try {
                        List<CategoryRepository.Category> children = categoryRepo.listChildren(userUid, cat.id());
                        counts.put(cat.id(), children.size());
                        System.out.println("[DEBUG] Categoría '" + cat.name() + "' tiene " + children.size() + " subcategorías");
                    } catch (Exception ex) {
                        System.err.println("[ERROR] Error al cargar subcategorías de '" + cat.name() + "': " + ex.getMessage());
                        counts.put(cat.id(), 0);
                    }
                }
                subcategoryCountsRef.set(counts);

                Platform.runLater(() -> {
                    System.out.println("[DEBUG] Actualizando UI con " + roots.size() + " categorías");
                    categoriesList.getChildren().clear();
                    for (CategoryRepository.Category cat : roots) {
                        Node card = buildCategoryCard.apply(cat);
                        categoriesList.getChildren().add(card);
                    }
                    System.out.println("[DEBUG] UI actualizada");
                });
            } catch (SQLException e) {
                System.err.println("[ERROR] Error al cargar categorías raíz: " + e.getMessage());
                Platform.runLater(() -> {
                    Label errorLabel = new Label("Error al cargar categorías");
                    errorLabel.setStyle("-fx-text-fill: #EF4444; -fx-font-size: 14px;");
                    categoriesList.getChildren().setAll(errorLabel);
                });
            }
        };

        loadCategoriesRef.set(loadCategories);
        loadCategories.run();

        // ═══════════════════════════════════════════════════════════════════
        // BÚSQUEDA EN TIEMPO REAL
        // ═══════════════════════════════════════════════════════════════════
        searchField.textProperty().addListener((obs, old, val) -> {
            String query = val == null ? "" : val.toLowerCase().trim();
            List<CategoryRepository.Category> allCats = allCategoriesRef.get();
            
            Platform.runLater(() -> {
                categoriesList.getChildren().clear();
                
                if (query.isEmpty()) {
                    // Mostrar todas las categorías
                    for (CategoryRepository.Category cat : allCats) {
                        Node card = buildCategoryCard.apply(cat);
                        categoriesList.getChildren().add(card);
                    }
                } else {
                    // Filtrar categorías que coincidan con la búsqueda
                    boolean hasResults = false;
                    for (CategoryRepository.Category cat : allCats) {
                        if (cat.name().toLowerCase().contains(query)) {
                            Node card = buildCategoryCard.apply(cat);
                            categoriesList.getChildren().add(card);
                            hasResults = true;
                        }
                    }
                    
                    // Mostrar mensaje si no hay resultados
                    if (!hasResults) {
                        VBox emptySearch = new VBox(12);
                        emptySearch.setAlignment(Pos.CENTER);
                        emptySearch.setPadding(new Insets(40, 0, 40, 0));
                        
                        FontIcon searchEmptyIcon = new FontIcon("fas-search");
                        searchEmptyIcon.setIconSize(40);
                        searchEmptyIcon.setIconColor(Color.web("#CBD5E1"));
                        
                        Label noResultsLabel = new Label("No se encontraron categorías");
                        noResultsLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: 600; -fx-text-fill: #64748B;");
                        
                        Label tryAgainLabel = new Label("Prueba con otro término de búsqueda");
                        tryAgainLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #94A3B8;");
                        
                        emptySearch.getChildren().addAll(searchEmptyIcon, noResultsLabel, tryAgainLabel);
                        categoriesList.getChildren().add(emptySearch);
                    }
                }
            });
        });

        // ═══════════════════════════════════════════════════════════════════
        // ACCIONES
        // ═══════════════════════════════════════════════════════════════════
        btnNewCategory.setOnAction(e -> {
            VBox newCategoryForm = buildNewCategoryFormPremium(sideDrawer, session, categoryRepo, loadCategories, isDark);
            sideDrawer.register("new-category", isDark, newCategoryForm);
            sideDrawer.show("new-category");
        });
        
        emptyActionBtn.setOnAction(e -> {
            VBox newCategoryForm = buildNewCategoryFormPremium(sideDrawer, session, categoryRepo, loadCategories, isDark);
            sideDrawer.register("new-category", isDark, newCategoryForm);
            sideDrawer.show("new-category");
        });

        // Wrap con drawer
        StackPane contentWithDrawer = sideDrawer.wrapContent(content);
        VBox.setVgrow(contentWithDrawer, Priority.ALWAYS);
        HBox.setHgrow(contentWithDrawer, Priority.ALWAYS);

        return contentWithDrawer;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SUBCATEGORIAS PREMIUM CON MENU CONTEXTUAL
    // ═══════════════════════════════════════════════════════════════════════
    
    private static void showSubcategoriesPremium(
            CategoryRepository.Category parent,
            FlowPane grid,
            VBox wrapper,
            ScrollPane scroll,
            VBox emptyState,
            String userUid,
            CategoryRepository categoryRepo,
            boolean isDark,
            SideDrawer drawer,
            AuthSession session,
            Runnable refreshCallback
    ) {
        grid.getChildren().clear();

        String color = getCategoryColor(parent.id(), 0);
        String parentIcon = parent.icon() != null && !parent.icon().isBlank() ? parent.icon() : getCategoryIcon(parent.kind());
        
        FontIcon catIcon = new FontIcon(parentIcon);
        catIcon.setIconSize(28);
        catIcon.setIconColor(Color.web(color));
        
        Circle colorIndicator = new Circle(8);
        colorIndicator.setFill(Color.web(color));
        
        Label title = new Label(parent.name());
        title.setStyle("-fx-font-size: 26px; -fx-font-weight: 800; -fx-text-fill: #0F172A;");
        
        HBox titleRow = new HBox(14, colorIndicator, title);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        
        String typeLabel = "INCOME".equalsIgnoreCase(parent.kind()) ? "Categoría de Ingresos" : "Categoría de Gastos";
        Label subtitle = new Label(typeLabel + " • Personaliza y organiza tus subcategorías");
        subtitle.setStyle("-fx-font-size: 14px; -fx-text-fill: #64748B; -fx-font-weight: 500;");
        
        // Botón nueva subcategoria
        FontIcon plusIcon = new FontIcon("fas-plus");
        plusIcon.setIconSize(14);
        plusIcon.setIconColor(Color.web("#1E6DFF"));
        
        Button btnNewSub = new Button("Nueva subcategoría");
        btnNewSub.setGraphic(plusIcon);
        btnNewSub.setGraphicTextGap(10);
        btnNewSub.setStyle(
            "-fx-background-color: transparent; " +
            "-fx-border-color: #1E6DFF; " +
            "-fx-border-width: 2; " +
            "-fx-border-radius: 12; " +
            "-fx-text-fill: #1E6DFF; " +
            "-fx-font-weight: 700; " +
            "-fx-font-size: 14px; " +
            "-fx-padding: 12 20; " +
            "-fx-cursor: hand;"
        );
        
        btnNewSub.setOnMouseEntered(e -> {
            btnNewSub.setStyle(
                "-fx-background-color: #1E6DFF15; " +
                "-fx-border-color: #1E6DFF; " +
                "-fx-border-width: 2; " +
                "-fx-border-radius: 12; " +
                "-fx-text-fill: #1E6DFF; " +
                "-fx-font-weight: 700; " +
                "-fx-font-size: 14px; " +
                "-fx-padding: 12 20; " +
                "-fx-cursor: hand;"
            );
        });
        btnNewSub.setOnMouseExited(e -> {
            btnNewSub.setStyle(
                "-fx-background-color: transparent; " +
                "-fx-border-color: #1E6DFF; " +
                "-fx-border-width: 2; " +
                "-fx-border-radius: 12; " +
                "-fx-text-fill: #1E6DFF; " +
                "-fx-font-weight: 700; " +
                "-fx-font-size: 14px; " +
                "-fx-padding: 12 20; " +
                "-fx-cursor: hand;"
            );
        });

        VBox headerText = new VBox(8, titleRow, subtitle);
        HBox.setHgrow(headerText, Priority.ALWAYS);

        HBox header = new HBox(20, catIcon, headerText, btnNewSub);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 28, 0));

        // Limpiar y reconstruir el wrapper
        wrapper.getChildren().clear();
        wrapper.setSpacing(20);
        wrapper.setPadding(new Insets(0, 16, 20, 0));
        wrapper.setFillWidth(true);
        wrapper.getChildren().addAll(header, grid);

        btnNewSub.setOnAction(e -> {
            try {
                VBox form = buildNewSubcategoryFormPremium(drawer, session, categoryRepo, parent, refreshCallback, isDark);
                drawer.register("new-subcategory", isDark, form);
                drawer.show("new-subcategory");
            } catch (Exception ex) {
                System.err.println("Error al abrir drawer de nueva subcategoría: " + ex.getMessage());
                ex.printStackTrace();
            }
        });

        // Cargar subcategorias
        System.out.println("[DEBUG] Cargando subcategorías para parent: " + parent.name() + " (ID: " + parent.id() + ")");
        new Thread(() -> {
            try {
                List<CategoryRepository.Category> children = categoryRepo.listChildren(userUid, parent.id());
                System.out.println("[DEBUG] Subcategorías encontradas: " + children.size());
                for (CategoryRepository.Category child : children) {
                    System.out.println("[DEBUG]  - Subcategoría: " + child.name() + " (ID: " + child.id() + ")");
                }
                Platform.runLater(() -> {
                    if (children.isEmpty()) {
                        FontIcon emptyIcon = new FontIcon("fas-folder-open");
                        emptyIcon.setIconSize(64);
                        emptyIcon.setIconColor(Color.web("#CBD5E1"));

                        Label emptyTitle = new Label("Sin subcategorías");
                        emptyTitle.setStyle("-fx-font-size: 22px; -fx-font-weight: 700; -fx-text-fill: #475569;");

                        Label emptyDesc = new Label("Esta categoría aún no tiene subcategorías.\nCrea una para organizar mejor tus " + 
                            ("INCOME".equalsIgnoreCase(parent.kind()) ? "ingresos" : "gastos") + ".");
                        emptyDesc.setStyle("-fx-font-size: 14px; -fx-text-fill: #64748B; -fx-text-alignment: center;");
                        emptyDesc.setWrapText(true);
                        emptyDesc.setMaxWidth(400);
                        emptyDesc.setAlignment(Pos.CENTER);

                        // Botón para crear subcategoría
                        FontIcon plusIconEmpty = new FontIcon("fas-plus");
                        plusIconEmpty.setIconSize(14);
                        plusIconEmpty.setIconColor(Color.web("#FFFFFF"));
                        
                        Button btnCreateSub = new Button("Crear subcategoría");
                        btnCreateSub.setGraphic(plusIconEmpty);
                        btnCreateSub.setGraphicTextGap(10);
                        btnCreateSub.setStyle(
                            "-fx-background-color: #1E6DFF; " +
                            "-fx-text-fill: #FFFFFF; " +
                            "-fx-font-weight: 700; " +
                            "-fx-font-size: 14px; " +
                            "-fx-padding: 12 24; " +
                            "-fx-background-radius: 12; " +
                            "-fx-cursor: hand;"
                        );
                        btnCreateSub.setOnMouseEntered(e -> btnCreateSub.setStyle(
                            "-fx-background-color: #1E5AD6; " +
                            "-fx-text-fill: #FFFFFF; " +
                            "-fx-font-weight: 700; " +
                            "-fx-font-size: 14px; " +
                            "-fx-padding: 12 24; " +
                            "-fx-background-radius: 12; " +
                            "-fx-cursor: hand;"
                        ));
                        btnCreateSub.setOnMouseExited(e -> btnCreateSub.setStyle(
                            "-fx-background-color: #1E6DFF; " +
                            "-fx-text-fill: #FFFFFF; " +
                            "-fx-font-weight: 700; " +
                            "-fx-font-size: 14px; " +
                            "-fx-padding: 12 24; " +
                            "-fx-background-radius: 12; " +
                            "-fx-cursor: hand;"
                        ));
                        btnCreateSub.setOnAction(e -> {
                            try {
                                VBox form = buildNewSubcategoryFormPremium(drawer, session, categoryRepo, parent, refreshCallback, isDark);
                                drawer.register("new-subcategory", isDark, form);
                                drawer.show("new-subcategory");
                            } catch (Exception ex) {
                                System.err.println("Error al abrir drawer: " + ex.getMessage());
                                ex.printStackTrace();
                            }
                        });

                        VBox emptyBox = new VBox(20, emptyIcon, emptyTitle, emptyDesc, btnCreateSub);
                        emptyBox.setAlignment(Pos.CENTER);
                        emptyBox.setPadding(new Insets(60, 0, 60, 0));
                        
                        grid.getChildren().add(emptyBox);
                    } else {
                        for (CategoryRepository.Category child : children) {
                            Node card = buildSubcategoryCardPremium(child, parent, isDark, drawer, session, categoryRepo, refreshCallback);
                            grid.getChildren().add(card);
                        }
                    }

                    emptyState.setVisible(false);
                    emptyState.setManaged(false);
                    scroll.setVisible(true);
                    scroll.setManaged(true);
                    wrapper.setVisible(true);
                    wrapper.setManaged(true);
                    
                    FadeTransition ft = new FadeTransition(Duration.millis(250), wrapper);
                    ft.setFromValue(0);
                    ft.setToValue(1);
                    ft.play();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Label errorLabel = new Label("Error al cargar subcategorías");
                    errorLabel.setStyle("-fx-text-fill: #EF4444;");
                    grid.getChildren().add(errorLabel);
                });
            }
        }).start();
    }

    private static Node buildSubcategoryCardPremium(
            CategoryRepository.Category sub,
            CategoryRepository.Category parent,
            boolean isDark,
            SideDrawer drawer,
            AuthSession session,
            CategoryRepository categoryRepo,
            Runnable refreshCallback
    ) {
        String color = getCategoryColor(parent.id(), 0);
        String iconLiteral = sub.icon() != null && !sub.icon().isBlank()
                ? sub.icon()
                : getSmartIconForName(sub.name());

        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(24);
        icon.setIconColor(Color.web(color));

        Circle iconBg = new Circle(28);
        iconBg.setFill(Color.web(color, 0.12));

        StackPane iconContainer = new StackPane(iconBg, icon);
        iconContainer.setMinSize(56, 56);
        iconContainer.setMaxSize(56, 56);

        Label nameLabel = new Label(sub.name());
        nameLabel.setStyle("-fx-font-weight: 700; -fx-font-size: 14px; -fx-text-fill: #1E293B;");
        nameLabel.setWrapText(true);
        nameLabel.setMaxWidth(180);
        nameLabel.setMinHeight(18);

        Label parentLabel = new Label("Dentro de " + parent.name());
        parentLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #94A3B8; -fx-font-weight: 500;");

        // Botones de acción
        FontIcon editIcon = new FontIcon("fas-pencil-alt");
        editIcon.setIconSize(14);
        editIcon.setIconColor(Color.web("#64748B"));
        
        Button editBtn = new Button();
        editBtn.setGraphic(editIcon);
        editBtn.setStyle(
            "-fx-background-color: transparent; " +
            "-fx-padding: 10; " +
            "-fx-cursor: hand; " +
            "-fx-background-radius: 8; " +
            "-fx-opacity: 0;"
        );
        
        FontIcon deleteIcon = new FontIcon("fas-trash");
        deleteIcon.setIconSize(14);
        deleteIcon.setIconColor(Color.web("#EF4444"));
        
        Button deleteBtn = new Button();
        deleteBtn.setGraphic(deleteIcon);
        deleteBtn.setStyle(
            "-fx-background-color: transparent; " +
            "-fx-padding: 10; " +
            "-fx-cursor: hand; " +
            "-fx-background-radius: 8; " +
            "-fx-opacity: 0;"
        );
        
        HBox actionBtns = new HBox(4, editBtn, deleteBtn);
        
        // Handlers de acciones
        editBtn.setOnAction(e -> {
            e.consume();
            VBox form = buildEditSubcategoryFormPremium(drawer, session, categoryRepo, sub, parent, refreshCallback, isDark);
            drawer.register("edit-subcategory", isDark, form);
            drawer.show("edit-subcategory");
        });
        
        deleteBtn.setOnAction(e -> {
            e.consume();
            showDeleteSubcategoryConfirmation(drawer, session, categoryRepo, sub, parent, refreshCallback, isDark);
        });

        VBox textBox = new VBox(6, nameLabel, parentLabel);
        textBox.setAlignment(Pos.CENTER_LEFT);

        HBox content = new HBox(16, iconContainer, textBox);
        content.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        HBox card = new HBox(content, actionBtns);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPadding(new Insets(16, 14, 16, 18));
        card.setSpacing(12);
        card.setPrefWidth(320);
        card.setMinWidth(320);
        card.setMaxWidth(320);
        card.setStyle(
            "-fx-background-radius: 18; " +
            "-fx-cursor: hand; " +
            "-fx-background-color: " + (isDark ? "rgba(255,255,255,0.04)" : "#FFFFFF") + "; " +
            "-fx-border-radius: 18; " +
            "-fx-border-color: " + (isDark ? "rgba(255,255,255,0.06)" : "#F1F5F9") + "; " +
            "-fx-border-width: 1.5; " +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.06), 14, 0, 0, 3);"
        );

        // Hover premium con botones de acción
        card.setOnMouseEntered(e -> {
            card.setStyle(
                "-fx-background-radius: 18; " +
                "-fx-cursor: hand; " +
                "-fx-background-color: " + (isDark ? "rgba(255,255,255,0.08)" : "#FFFFFF") + "; " +
                "-fx-border-radius: 18; " +
                "-fx-border-color: " + (isDark ? "rgba(255,255,255,0.12)" : "#E2E8F0") + "; " +
                "-fx-border-width: 1.5; " +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 22, 0, 0, 6);"
            );
            editBtn.setStyle(
                "-fx-background-color: " + (isDark ? "rgba(255,255,255,0.1)" : "#F1F5F9") + "; " +
                "-fx-padding: 10; " +
                "-fx-cursor: hand; " +
                "-fx-background-radius: 8; " +
                "-fx-opacity: 1;"
            );
            deleteBtn.setStyle(
                "-fx-background-color: " + (isDark ? "rgba(255,255,255,0.1)" : "#FEF2F2") + "; " +
                "-fx-padding: 10; " +
                "-fx-cursor: hand; " +
                "-fx-background-radius: 8; " +
                "-fx-opacity: 1;"
            );
            
            ScaleTransition st = new ScaleTransition(Duration.millis(200), card);
            st.setToX(1.02);
            st.setToY(1.02);
            st.play();
        });
        card.setOnMouseExited(e -> {
            card.setStyle(
                "-fx-background-radius: 18; " +
                "-fx-cursor: hand; " +
                "-fx-background-color: " + (isDark ? "rgba(255,255,255,0.04)" : "#FFFFFF") + "; " +
                "-fx-border-radius: 18; " +
                "-fx-border-color: " + (isDark ? "rgba(255,255,255,0.06)" : "#F1F5F9") + "; " +
                "-fx-border-width: 1.5; " +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.06), 14, 0, 0, 3);"
            );
            editBtn.setStyle(
                "-fx-background-color: transparent; " +
                "-fx-padding: 10; " +
                "-fx-cursor: hand; " +
                "-fx-opacity: 0;"
            );
            deleteBtn.setStyle(
                "-fx-background-color: transparent; " +
                "-fx-padding: 10; " +
                "-fx-cursor: hand; " +
                "-fx-opacity: 0;"
            );
            
            ScaleTransition st = new ScaleTransition(Duration.millis(200), card);
            st.setToX(1.0);
            st.setToY(1.0);
            st.play();
        });

        return card;
    }

    private static String getSmartIconForName(String name) {
        String lower = name.toLowerCase();
        
        // Transporte
        if (lower.contains("uber") || lower.contains("taxi")) return "fas-taxi";
        if (lower.contains("gasolina") || lower.contains("combustible")) return "fas-gas-pump";
        if (lower.contains("bus") || lower.contains("metro")) return "fas-bus";
        if (lower.contains("tren")) return "fas-train";
        if (lower.contains("avion") || lower.contains("vuelo")) return "fas-plane";
        if (lower.contains("carro") || lower.contains("auto")) return "fas-car";
        
        // Comida
        if (lower.contains("restaurant") || lower.contains("comida")) return "fas-utensils";
        if (lower.contains("cafe")) return "fas-coffee";
        if (lower.contains("super") || lower.contains("mercado")) return "fas-shopping-cart";
        if (lower.contains("hamburguesa")) return "fas-hamburger";
        
        // Hogar
        if (lower.contains("luz") || lower.contains("electricidad")) return "fas-bolt";
        if (lower.contains("agua")) return "fas-tint";
        if (lower.contains("internet") || lower.contains("wifi")) return "fas-wifi";
        if (lower.contains("casa") || lower.contains("hogar") || lower.contains("renta") || lower.contains("hipoteca")) return "fas-home";
        
        // Salud
        if (lower.contains("doctor") || lower.contains("medico")) return "fas-user-md";
        if (lower.contains("medicina") || lower.contains("pastillas")) return "fas-pills";
        if (lower.contains("dentista")) return "fas-tooth";
        if (lower.contains("hospital")) return "fas-hospital";
        if (lower.contains("ambulancia")) return "fas-ambulance";
        
        // Educacion
        if (lower.contains("universidad") || lower.contains("colegio") || lower.contains("escuela")) return "fas-graduation-cap";
        if (lower.contains("libro") || lower.contains("curso")) return "fas-book-open";
        if (lower.contains("profe") || lower.contains("maestro")) return "fas-chalkboard-teacher";
        
        // Entretenimiento
        if (lower.contains("cine") || lower.contains("pelicula")) return "fas-film";
        if (lower.contains("musica") || lower.contains("cancion")) return "fas-music";
        if (lower.contains("juego") || lower.contains("videojuego")) return "fas-gamepad";
        if (lower.contains("gimnasio") || lower.contains("gym")) return "fas-dumbbell";
        if (lower.contains("ejercicio")) return "fas-dumbbell";
        
        // Ropa y compras
        if (lower.contains("ropa") || lower.contains("camisa")) return "fas-tshirt";
        if (lower.contains("tienda") || lower.contains("compras")) return "fas-shopping-bag";
        if (lower.contains("joya") || lower.contains("anillo")) return "fas-gem";
        
        // Tecnologia
        if (lower.contains("computador") || lower.contains("laptop") || lower.contains("pc")) return "fas-laptop";
        if (lower.contains("celular") || lower.contains("movil") || lower.contains("iphone")) return "fas-mobile";
        if (lower.contains("camara")) return "fas-camera";
        
        // Mascotas
        if (lower.contains("mascota") || lower.contains("perro") || lower.contains("gato")) return "fas-paw";
        
        // Regalos
        if (lower.contains("regalo")) return "fas-gift";
        
        // Finanzas específicas
        if (lower.contains("banco")) return "fas-university";
        if (lower.contains("tarjeta")) return "fas-credit-card";
        if (lower.contains("ahorro")) return "fas-piggy-bank";
        
        return "fas-tag";
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SELECTORES VISUALES PREMIUM
    // ═══════════════════════════════════════════════════════════════════════
    
    private static Node buildIconSelector(AtomicReference<String> selectedIcon, String defaultIcon, boolean isDark) {
        Label sectionLabel = new Label("Icono representativo");
        sectionLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: #475569;");
        
        FlowPane iconGrid = new FlowPane();
        iconGrid.setHgap(8);
        iconGrid.setVgap(8);
        iconGrid.setPrefWrapLength(400);
        
        ToggleGroup group = new ToggleGroup();
        
        for (String iconLiteral : AVAILABLE_ICONS) {
            ToggleButton btn = new ToggleButton();
            FontIcon icon = new FontIcon(iconLiteral);
            icon.setIconSize(18);
            icon.setIconColor(Color.web("#64748B"));
            btn.setGraphic(icon);
            btn.setToggleGroup(group);
            btn.setUserData(iconLiteral);
            btn.setStyle(
                "-fx-background-radius: 10; " +
                "-fx-padding: 12; " +
                "-fx-cursor: hand; " +
                "-fx-background-color: " + (isDark ? "rgba(255,255,255,0.04)" : "#F8FAFC") + "; " +
                "-fx-border-radius: 10; " +
                "-fx-border-color: " + (isDark ? "rgba(255,255,255,0.06)" : "#E2E8F0") + "; " +
                "-fx-border-width: 1;"
            );
            
            btn.selectedProperty().addListener((obs, old, selected) -> {
                if (selected) {
                    selectedIcon.set(iconLiteral);
                    btn.setStyle(
                        "-fx-background-radius: 10; " +
                        "-fx-padding: 12; " +
                        "-fx-cursor: hand; " +
                        "-fx-background-color: #1E6DFF20; " +
                        "-fx-border-radius: 10; " +
                        "-fx-border-color: #1E6DFF; " +
                        "-fx-border-width: 2;"
                    );
                    icon.setIconColor(Color.web("#1E6DFF"));
                } else {
                    btn.setStyle(
                        "-fx-background-radius: 10; " +
                        "-fx-padding: 12; " +
                        "-fx-cursor: hand; " +
                        "-fx-background-color: " + (isDark ? "rgba(255,255,255,0.04)" : "#F8FAFC") + "; " +
                        "-fx-border-radius: 10; " +
                        "-fx-border-color: " + (isDark ? "rgba(255,255,255,0.06)" : "#E2E8F0") + "; " +
                        "-fx-border-width: 1;"
                    );
                    icon.setIconColor(Color.web("#64748B"));
                }
            });
            
            btn.setOnMouseEntered(e -> {
                if (!btn.isSelected()) {
                    btn.setStyle(
                        "-fx-background-radius: 10; " +
                        "-fx-padding: 12; " +
                        "-fx-cursor: hand; " +
                        "-fx-background-color: " + (isDark ? "rgba(255,255,255,0.08)" : "#EFF6FF") + "; " +
                        "-fx-border-radius: 10; " +
                        "-fx-border-color: #3B82F6; " +
                        "-fx-border-width: 1;"
                    );
                }
            });
            btn.setOnMouseExited(e -> {
                if (!btn.isSelected()) {
                    btn.setStyle(
                        "-fx-background-radius: 10; " +
                        "-fx-padding: 12; " +
                        "-fx-cursor: hand; " +
                        "-fx-background-color: " + (isDark ? "rgba(255,255,255,0.04)" : "#F8FAFC") + "; " +
                        "-fx-border-radius: 10; " +
                        "-fx-border-color: " + (isDark ? "rgba(255,255,255,0.06)" : "#E2E8F0") + "; " +
                        "-fx-border-width: 1;"
                    );
                }
            });
            
            if (iconLiteral.equals(defaultIcon)) {
                btn.setSelected(true);
            }
            
            iconGrid.getChildren().add(btn);
        }
        
        ScrollPane scroll = new ScrollPane(iconGrid);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setPrefHeight(140);
        scroll.setStyle("-fx-background-color: transparent;");
        
        return new VBox(8, sectionLabel, scroll);
    }

    private static Node buildIconSelectorCompact(AtomicReference<String> selectedIcon, String defaultIcon, boolean isDark) {
        Label sectionLabel = new Label("Icono");
        sectionLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: 600; -fx-text-fill: #475569;");

        FlowPane iconGrid = new FlowPane();
        iconGrid.setHgap(6);
        iconGrid.setVgap(6);
        iconGrid.setPrefWrapLength(200);

        ToggleGroup group = new ToggleGroup();

        for (String iconLiteral : AVAILABLE_ICONS) {
            ToggleButton btn = new ToggleButton();
            FontIcon icon = new FontIcon(iconLiteral);
            icon.setIconSize(16);
            icon.setIconColor(Color.web("#64748B"));
            btn.setGraphic(icon);
            btn.setToggleGroup(group);
            btn.setUserData(iconLiteral);
            btn.setStyle(
                "-fx-background-radius: 8; " +
                "-fx-padding: 8; " +
                "-fx-cursor: hand; " +
                "-fx-background-color: " + (isDark ? "rgba(255,255,255,0.04)" : "#F8FAFC") + "; " +
                "-fx-border-radius: 8; " +
                "-fx-border-color: " + (isDark ? "rgba(255,255,255,0.06)" : "#E2E8F0") + "; " +
                "-fx-border-width: 1;"
            );

            btn.selectedProperty().addListener((obs, old, selected) -> {
                if (selected) {
                    selectedIcon.set(iconLiteral);
                    btn.setStyle(
                        "-fx-background-radius: 8; " +
                        "-fx-padding: 8; " +
                        "-fx-cursor: hand; " +
                        "-fx-background-color: #1E6DFF20; " +
                        "-fx-border-radius: 8; " +
                        "-fx-border-color: #1E6DFF; " +
                        "-fx-border-width: 2;"
                    );
                    icon.setIconColor(Color.web("#1E6DFF"));
                } else {
                    btn.setStyle(
                        "-fx-background-radius: 8; " +
                        "-fx-padding: 8; " +
                        "-fx-cursor: hand; " +
                        "-fx-background-color: " + (isDark ? "rgba(255,255,255,0.04)" : "#F8FAFC") + "; " +
                        "-fx-border-radius: 8; " +
                        "-fx-border-color: " + (isDark ? "rgba(255,255,255,0.06)" : "#E2E8F0") + "; " +
                        "-fx-border-width: 1;"
                    );
                    icon.setIconColor(Color.web("#64748B"));
                }
            });

            if (iconLiteral.equals(defaultIcon)) {
                btn.setSelected(true);
            }

            iconGrid.getChildren().add(btn);
        }

        ScrollPane scroll = new ScrollPane(iconGrid);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setPrefHeight(180);
        scroll.setMaxHeight(180);
        scroll.setStyle("-fx-background-color: transparent;");

        return new VBox(6, sectionLabel, scroll);
    }

    private static Node buildColorSelectorCompact(AtomicReference<String> selectedColor, String defaultColor, boolean isDark) {
        Label sectionLabel = new Label("Color");
        sectionLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: 600; -fx-text-fill: #475569;");

        FlowPane colorGrid = new FlowPane();
        colorGrid.setHgap(6);
        colorGrid.setVgap(6);
        colorGrid.setPrefWrapLength(200);

        ToggleGroup group = new ToggleGroup();

        for (String colorHex : CATEGORY_COLORS) {
            ToggleButton btn = new ToggleButton();
            btn.setPrefSize(28, 28);
            btn.setToggleGroup(group);
            btn.setUserData(colorHex);
            btn.setStyle(
                "-fx-background-radius: 14; " +
                "-fx-background-color: " + colorHex + "; " +
                "-fx-cursor: hand; " +
                "-fx-padding: 0;"
            );

            btn.selectedProperty().addListener((obs, old, selected) -> {
                if (selected) {
                    selectedColor.set(colorHex);
                    btn.setStyle(
                        "-fx-background-radius: 14; " +
                        "-fx-background-color: " + colorHex + "; " +
                        "-fx-cursor: hand; " +
                        "-fx-padding: 0; " +
                        "-fx-border-radius: 14; " +
                        "-fx-border-color: #1E6DFF; " +
                        "-fx-border-width: 3;"
                    );
                } else {
                    btn.setStyle(
                        "-fx-background-radius: 14; " +
                        "-fx-background-color: " + colorHex + "; " +
                        "-fx-cursor: hand; " +
                        "-fx-padding: 0;"
                    );
                }
            });

            if (colorHex.equals(defaultColor)) {
                btn.setSelected(true);
            }

            colorGrid.getChildren().add(btn);
        }

        return new VBox(6, sectionLabel, colorGrid);
    }

    private static Node buildColorSelector(AtomicReference<String> selectedColor, String defaultColor, boolean isDark) {
        Label sectionLabel = new Label("Color de identidad");
        sectionLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: #475569;");
        
        FlowPane colorGrid = new FlowPane();
        colorGrid.setHgap(8);
        colorGrid.setVgap(8);
        colorGrid.setPrefWrapLength(380);
        
        ToggleGroup group = new ToggleGroup();
        
        for (String colorHex : CATEGORY_COLORS) {
            ToggleButton btn = new ToggleButton();
            btn.setToggleGroup(group);
            btn.setUserData(colorHex);
            
            Circle colorCircle = new Circle(16);
            colorCircle.setFill(Color.web(colorHex));
            colorCircle.setStroke(Color.web(colorHex).darker());
            colorCircle.setStrokeWidth(1);
            
            btn.setGraphic(colorCircle);
            btn.setStyle(
                "-fx-background-radius: 20; " +
                "-fx-padding: 6; " +
                "-fx-cursor: hand; " +
                "-fx-background-color: transparent; " +
                "-fx-border-radius: 20;"
            );
            
            btn.selectedProperty().addListener((obs, old, selected) -> {
                if (selected) {
                    selectedColor.set(colorHex);
                    btn.setStyle(
                        "-fx-background-radius: 20; " +
                        "-fx-padding: 4; " +
                        "-fx-cursor: hand; " +
                        "-fx-background-color: transparent; " +
                        "-fx-border-radius: 20; " +
                        "-fx-border-color: " + colorHex + "; " +
                        "-fx-border-width: 3;"
                    );
                } else {
                    btn.setStyle(
                        "-fx-background-radius: 20; " +
                        "-fx-padding: 6; " +
                        "-fx-cursor: hand; " +
                        "-fx-background-color: transparent; " +
                        "-fx-border-radius: 20;"
                    );
                }
            });
            
            btn.setOnMouseEntered(e -> {
                if (!btn.isSelected()) {
                    btn.setStyle(
                        "-fx-background-radius: 20; " +
                        "-fx-padding: 6; " +
                        "-fx-cursor: hand; " +
                        "-fx-background-color: " + colorHex + "20; " +
                        "-fx-border-radius: 20;"
                    );
                }
            });
            btn.setOnMouseExited(e -> {
                if (!btn.isSelected()) {
                    btn.setStyle(
                        "-fx-background-radius: 20; " +
                        "-fx-padding: 6; " +
                        "-fx-cursor: hand; " +
                        "-fx-background-color: transparent; " +
                        "-fx-border-radius: 20;"
                    );
                }
            });
            
            if (colorHex.equals(defaultColor)) {
                btn.setSelected(true);
            }
            
            colorGrid.getChildren().add(btn);
        }
        
        return new VBox(10, sectionLabel, colorGrid);
    }
    
    private static Node buildLivePreview(AtomicReference<String> name, AtomicReference<String> icon, AtomicReference<String> color, String kind) {
        Label previewLabel = new Label("Vista previa");
        previewLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: #475569;");
        
        VBox previewContainer = new VBox(12);
        previewContainer.setAlignment(Pos.CENTER);
        previewContainer.setPadding(new Insets(24));
        previewContainer.setStyle(
            "-fx-background-color: #F8FAFC; " +
            "-fx-background-radius: 16; " +
            "-fx-border-radius: 16; " +
            "-fx-border-color: #E2E8F0; " +
            "-fx-border-width: 1.5;"
        );
        
        Runnable updatePreview = () -> {
            String currentName = name.get() != null && !name.get().isBlank() ? name.get() : "Nombre de categoría";
            String currentIcon = icon.get() != null ? icon.get() : getCategoryIcon(kind);
            String currentColor = color.get() != null ? color.get() : CATEGORY_COLORS[0];
            
            FontIcon iconNode = new FontIcon(currentIcon);
            iconNode.setIconSize(28);
            iconNode.setIconColor(Color.web(currentColor));
            
            Circle iconBg = new Circle(32);
            iconBg.setFill(Color.web(currentColor, 0.15));
            
            StackPane iconContainer = new StackPane(iconBg, iconNode);
            iconContainer.setMinSize(64, 64);
            iconContainer.setMaxSize(64, 64);
            
            Label nameLabel = new Label(currentName);
            nameLabel.setStyle("-fx-font-weight: 700; -fx-font-size: 18px; -fx-text-fill: #1E293B;");
            
            String typeText = "INCOME".equalsIgnoreCase(kind) ? "Categoría de Ingresos" : "Categoría de Gastos";
            String typeColor = "INCOME".equalsIgnoreCase(kind) ? "#10B981" : "#EF4444";
            Label typeLabel = new Label(typeText);
            typeLabel.setStyle(
                "-fx-font-size: 12px; -fx-font-weight: 700; " +
                "-fx-text-fill: " + typeColor + "; " +
                "-fx-background-color: " + typeColor + "15; " +
                "-fx-padding: 4 12; " +
                "-fx-background-radius: 6;"
            );
            
            previewContainer.getChildren().setAll(iconContainer, nameLabel, typeLabel);
        };
        
        updatePreview.run();
        
        // Listener para actualizar cuando cambien los valores
        previewContainer.setUserData(updatePreview);
        
        return new VBox(12, previewLabel, previewContainer);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // FORMULARIOS PREMIUM COMPLETOS
    // ═══════════════════════════════════════════════════════════════════════
    
    private static VBox buildNewCategoryFormPremium(
            SideDrawer drawer,
            AuthSession session,
            CategoryRepository categoryRepo,
            Runnable onSuccess,
            boolean isDark
    ) {
        return buildCategoryFormPremium(drawer, session, categoryRepo, null, onSuccess, isDark, "Nueva categoría", "Crea una categoría raíz para organizar tus movimientos");
    }
    
    private static VBox buildEditCategoryFormPremium(
            SideDrawer drawer,
            AuthSession session,
            CategoryRepository categoryRepo,
            CategoryRepository.Category existing,
            Runnable onSuccess,
            boolean isDark
    ) {
        return buildCategoryFormPremium(drawer, session, categoryRepo, existing, onSuccess, isDark, "Editar categoría", "Modifica los detalles de tu categoría");
    }
    
    private static VBox buildCategoryFormPremium(
            SideDrawer drawer,
            AuthSession session,
            CategoryRepository categoryRepo,
            CategoryRepository.Category existing,
            Runnable onSuccess,
            boolean isDark,
            String titleText,
            String subtitleText
    ) {
        String kind = existing != null ? existing.kind() : "EXPENSE";
        
        // Estado del formulario
        AtomicReference<String> nameRef = new AtomicReference<>(existing != null ? existing.name() : "");
        AtomicReference<String> iconRef = new AtomicReference<>(existing != null && existing.icon() != null ? existing.icon() : getCategoryIcon(kind));
        AtomicReference<String> colorRef = new AtomicReference<>(existing != null ? getCategoryColor(existing.id(), 0) : CATEGORY_COLORS[0]);
        
        // Header compacto
        Label title = new Label(titleText);
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: 800; -fx-text-fill: #0F172A;");
        
        Label subtitle = new Label(subtitleText);
        subtitle.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748B;");
        
        Button closeBtn = drawer.buildCloseButton();
        HBox headerRow = new HBox(title, new Region(), closeBtn);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(headerRow.getChildren().get(1), Priority.ALWAYS);
        headerRow.setPadding(new Insets(0, 0, 4, 0));
        
        // Preview en tiempo real
        Node previewSection = buildLivePreview(nameRef, iconRef, colorRef, kind);
        VBox previewBox = (VBox) previewSection;
        Runnable updatePreview = (Runnable) previewBox.getChildren().get(1).getUserData();
        
        // Campo nombre
        Label nameLabel = SideDrawer.fieldLabel("Nombre de la categoría", "fas-tag");
        TextField nameField = new TextField(nameRef.get());
        nameField.setPromptText("Ej: Transporte, Salud, Entretenimiento...");
        nameField.setStyle(
            "-fx-background-color: " + (isDark ? "rgba(255,255,255,0.05)" : "#F8FAFC") + "; " +
            "-fx-background-radius: 12; " +
            "-fx-border-radius: 12; " +
            "-fx-border-color: " + (isDark ? "rgba(255,255,255,0.08)" : "#E2E8F0") + "; " +
            "-fx-border-width: 1.5; " +
            "-fx-padding: 14 18; " +
            "-fx-font-size: 15px;"
        );
        nameField.textProperty().addListener((obs, old, val) -> {
            nameRef.set(val);
            updatePreview.run();
        });
        
        // Selector de tipo (solo para nuevas) - Segmented Control moderno
        VBox typeSection = new VBox(8);
        AtomicReference<String> selectedType = new AtomicReference<>(existing != null ? existing.kind() : "EXPENSE");
        
        if (existing == null) {
            Label typeLabel = SideDrawer.fieldLabel("Tipo de categoría", "fas-filter");
            
            // Segmented control container - ancho total
            HBox typeContainer = new HBox(4);
            typeContainer.setAlignment(Pos.CENTER);
            HBox.setHgrow(typeContainer, Priority.ALWAYS);
            typeContainer.setMaxWidth(Double.MAX_VALUE);
            typeContainer.setStyle(
                "-fx-background-color: " + (isDark ? "rgba(255,255,255,0.05)" : "#F1F5F9") + "; " +
                "-fx-background-radius: 12; " +
                "-fx-padding: 4;"
            );
            
            // Botón Gasto (seleccionado por defecto) - ancho 50%
            Button expenseBtn = new Button("Gasto");
            HBox.setHgrow(expenseBtn, Priority.ALWAYS);
            expenseBtn.setMaxWidth(Double.MAX_VALUE);
            expenseBtn.setStyle(
                "-fx-background-color: #EF4444; " +
                "-fx-text-fill: white; " +
                "-fx-font-weight: 700; " +
                "-fx-font-size: 14px; " +
                "-fx-padding: 12 0; " +
                "-fx-background-radius: 10; " +
                "-fx-cursor: hand; " +
                "-fx-effect: dropshadow(gaussian, rgba(239,68,68,0.3), 6, 0, 0, 2);"
            );
            
            // Botón Ingreso - ancho 50%
            Button incomeBtn = new Button("Ingreso");
            HBox.setHgrow(incomeBtn, Priority.ALWAYS);
            incomeBtn.setMaxWidth(Double.MAX_VALUE);
            incomeBtn.setStyle(
                "-fx-background-color: transparent; " +
                "-fx-text-fill: " + (isDark ? "#94A3B8" : "#64748B") + "; " +
                "-fx-font-weight: 600; " +
                "-fx-font-size: 14px; " +
                "-fx-padding: 12 0; " +
                "-fx-background-radius: 10; " +
                "-fx-cursor: hand;"
            );
            
            // Handler para seleccionar Gasto
            expenseBtn.setOnAction(e -> {
                selectedType.set("EXPENSE");
                expenseBtn.setStyle(
                    "-fx-background-color: #EF4444; " +
                    "-fx-text-fill: white; " +
                    "-fx-font-weight: 700; " +
                    "-fx-font-size: 14px; " +
                    "-fx-padding: 12 0; " +
                    "-fx-background-radius: 10; " +
                    "-fx-cursor: hand; " +
                    "-fx-effect: dropshadow(gaussian, rgba(239,68,68,0.3), 6, 0, 0, 2);"
                );
                incomeBtn.setStyle(
                    "-fx-background-color: transparent; " +
                    "-fx-text-fill: " + (isDark ? "#94A3B8" : "#64748B") + "; " +
                    "-fx-font-weight: 600; " +
                    "-fx-font-size: 14px; " +
                    "-fx-padding: 12 0; " +
                    "-fx-background-radius: 10; " +
                    "-fx-cursor: hand;"
                );
            });
            
            // Handler para seleccionar Ingreso
            incomeBtn.setOnAction(e -> {
                selectedType.set("INCOME");
                incomeBtn.setStyle(
                    "-fx-background-color: #10B981; " +
                    "-fx-text-fill: white; " +
                    "-fx-font-weight: 700; " +
                    "-fx-font-size: 14px; " +
                    "-fx-padding: 12 0; " +
                    "-fx-background-radius: 10; " +
                    "-fx-cursor: hand; " +
                    "-fx-effect: dropshadow(gaussian, rgba(16,185,129,0.3), 6, 0, 0, 2);"
                );
                expenseBtn.setStyle(
                    "-fx-background-color: transparent; " +
                    "-fx-text-fill: " + (isDark ? "#94A3B8" : "#64748B") + "; " +
                    "-fx-font-weight: 600; " +
                    "-fx-font-size: 14px; " +
                    "-fx-padding: 12 0; " +
                    "-fx-background-radius: 10; " +
                    "-fx-cursor: hand;"
                );
            });
            
            typeContainer.getChildren().addAll(expenseBtn, incomeBtn);
            typeSection.getChildren().addAll(typeLabel, typeContainer);
        }
        
        // Selector de icono compacto
        Node iconSelector = buildIconSelectorCompact(iconRef, iconRef.get(), isDark);
        
        // Error label
        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: #EF4444; -fx-font-size: 13px;");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        
        // Botones
        Button btnSave = SideDrawer.buildPrimaryButton(existing != null ? "Guardar cambios" : "Crear categoría", "fas-check");
        btnSave.setStyle(
            "-fx-background-color: #1E6DFF; " +
            "-fx-text-fill: white; " +
            "-fx-font-weight: 700; " +
            "-fx-font-size: 15px; " +
            "-fx-padding: 16; " +
            "-fx-background-radius: 12; " +
            "-fx-cursor: hand;"
        );
        
        btnSave.setOnAction(e -> {
            errorLabel.setVisible(false);
            errorLabel.setManaged(false);
            
            String name = nameField.getText() == null ? "" : nameField.getText().trim();
            if (name.isBlank()) {
                errorLabel.setText("⚠ El nombre es obligatorio");
                errorLabel.setVisible(true);
                errorLabel.setManaged(true);
                nameField.setStyle(
                    "-fx-background-color: #FEF2F2; " +
                    "-fx-background-radius: 12; " +
                    "-fx-border-radius: 12; " +
                    "-fx-border-color: #EF4444; " +
                    "-fx-border-width: 2; " +
                    "-fx-padding: 14 18; " +
                    "-fx-font-size: 15px;"
                );
                return;
            }
            
            // Guardado real en base de datos
            String userUid = session.uid();
            String selectedKind = existing != null ? existing.kind() : selectedType.get();
            
            System.out.println("[DEBUG] Creando categoría - userUid: " + userUid + ", name: " + name + ", kind: " + selectedKind);
            
            new Thread(() -> {
                try {
                    String selectedIcon = iconRef.get();
                    if (existing != null) {
                        // Actualizar categoría existente
                        System.out.println("[DEBUG] Actualizando categoría existente: " + existing.id());
                        categoryRepo.update(userUid, existing.id(), name, selectedKind, selectedIcon);
                        System.out.println("[DEBUG] Categoría actualizada exitosamente");
                    } else {
                        // Crear nueva categoría raíz
                        System.out.println("[DEBUG] Creando nueva categoría raíz...");
                        CategoryRepository.Category newCat = categoryRepo.create(userUid, name, null, selectedKind, selectedIcon);
                        System.out.println("[DEBUG] Nueva categoría creada con ID: " + newCat.id());
                    }
                    
                    Platform.runLater(() -> {
                        System.out.println("[DEBUG] Cerrando drawer y ejecutando callback de éxito");
                        drawer.hide();
                        onSuccess.run();
                        System.out.println("[DEBUG] Callback ejecutado");
                    });
                } catch (SQLException ex) {
                    System.err.println("[ERROR] Error al guardar categoría: " + ex.getMessage());
                    ex.printStackTrace();
                    Platform.runLater(() -> {
                        errorLabel.setText("⚠ Error al guardar: " + ex.getMessage());
                        errorLabel.setVisible(true);
                        errorLabel.setManaged(true);
                    });
                } catch (Exception ex) {
                    System.err.println("[ERROR] Error inesperado: " + ex.getMessage());
                    ex.printStackTrace();
                    Platform.runLater(() -> {
                        errorLabel.setText("⚠ Error inesperado: " + ex.getMessage());
                        errorLabel.setVisible(true);
                        errorLabel.setManaged(true);
                    });
                }
            }).start();
        });
        
        Button btnCancel = drawer.buildCancelButton("Cancelar");
        btnCancel.setStyle(
            "-fx-background-color: transparent; " +
            "-fx-text-fill: #64748B; " +
            "-fx-font-weight: 600; " +
            "-fx-font-size: 15px; " +
            "-fx-padding: 16; " +
            "-fx-background-radius: 12; " +
            "-fx-cursor: hand;"
        );
        
        VBox footer = new VBox(12, errorLabel, btnSave, btnCancel);
        footer.setPadding(new Insets(16, 0, 0, 0));
        
        VBox body = new VBox(10, 
            headerRow, 
            subtitle,
            previewSection,
            new VBox(4, nameLabel, nameField),
            typeSection,
            iconSelector,
            footer
        );
        body.getStyleClass().add("drawer-body");
        body.setPadding(new Insets(12, 16, 12, 16));
        
        return body;
    }

    private static VBox buildNewSubcategoryFormPremium(
            SideDrawer drawer,
            AuthSession session,
            CategoryRepository categoryRepo,
            CategoryRepository.Category parent,
            Runnable onSuccess,
            boolean isDark
    ) {
        return buildSubcategoryFormPremium(drawer, session, categoryRepo, parent, null, onSuccess, isDark, "Nueva subcategoría", "Dentro de " + parent.name());
    }
    
    private static VBox buildEditSubcategoryFormPremium(
            SideDrawer drawer,
            AuthSession session,
            CategoryRepository categoryRepo,
            CategoryRepository.Category existing,
            CategoryRepository.Category parent,
            Runnable onSuccess,
            boolean isDark
    ) {
        return buildSubcategoryFormPremium(drawer, session, categoryRepo, parent, existing, onSuccess, isDark, "Editar subcategoría", "Dentro de " + parent.name());
    }
    
    private static VBox buildSubcategoryFormPremium(
            SideDrawer drawer,
            AuthSession session,
            CategoryRepository categoryRepo,
            CategoryRepository.Category parent,
            CategoryRepository.Category existing,
            Runnable onSuccess,
            boolean isDark,
            String titleText,
            String subtitleText
    ) {
        String color = getCategoryColor(parent.id(), 0);
        
        // Estado
        AtomicReference<String> nameRef = new AtomicReference<>(existing != null ? existing.name() : "");
        AtomicReference<String> iconRef = new AtomicReference<>(
            existing != null && existing.icon() != null && !existing.icon().isBlank()
                ? existing.icon()
                : (existing != null ? getSmartIconForName(existing.name()) : "fas-tag")
        );
        AtomicReference<String> colorRef = new AtomicReference<>(color);
        
        // Header
        Label title = new Label(titleText);
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: 800; -fx-text-fill: #0F172A;");
        
        Label subtitle = new Label(subtitleText);
        subtitle.setStyle("-fx-font-size: 14px; -fx-text-fill: " + color + "; -fx-font-weight: 600;");
        
        Button closeBtn = drawer.buildCloseButton();
        HBox headerRow = new HBox(title, new Region(), closeBtn);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(headerRow.getChildren().get(1), Priority.ALWAYS);
        headerRow.setPadding(new Insets(0, 0, 8, 0));
        
        // Preview
        VBox previewBox = new VBox(10);
        previewBox.setAlignment(Pos.CENTER);
        previewBox.setPadding(new Insets(16));
        previewBox.setStyle(
            "-fx-background-color: #F8FAFC; " +
            "-fx-background-radius: 14; " +
            "-fx-border-radius: 14; " +
            "-fx-border-color: #E2E8F0; " +
            "-fx-border-width: 1;"
        );
        
        Runnable updatePreview = () -> {
            String currentName = nameRef.get() != null && !nameRef.get().isBlank() ? nameRef.get() : "Nombre de subcategoría";
            String currentIcon = iconRef.get() != null ? iconRef.get() : "fas-tag";
            String parentColor = colorRef.get();
            
            FontIcon icon = new FontIcon(currentIcon);
            icon.setIconSize(24);
            icon.setIconColor(Color.web(parentColor));
            
            Circle iconBg = new Circle(28);
            iconBg.setFill(Color.web(parentColor, 0.15));
            
            StackPane iconContainer = new StackPane(iconBg, icon);
            iconContainer.setMinSize(56, 56);
            iconContainer.setMaxSize(56, 56);
            
            Label nameLabel = new Label(currentName);
            nameLabel.setStyle("-fx-font-weight: 700; -fx-font-size: 17px; -fx-text-fill: #1E293B;");
            
            Label parentLabel = new Label("Dentro de " + parent.name());
            parentLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #94A3B8; -fx-font-weight: 500;");
            
            previewBox.getChildren().setAll(iconContainer, nameLabel, parentLabel);
        };
        updatePreview.run();
        
        Label previewLabel = new Label("Vista previa");
        previewLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: #475569;");
        VBox previewSection = new VBox(8, previewLabel, previewBox);
        
        // Campo nombre
        Label nameLabel = SideDrawer.fieldLabel("Nombre de la subcategoría", "fas-tag");
        TextField nameField = new TextField(nameRef.get());
        nameField.setPromptText("Ej: Gasolina, Uber, Medicamentos...");
        nameField.setStyle(
            "-fx-background-color: " + (isDark ? "rgba(255,255,255,0.05)" : "#F8FAFC") + "; " +
            "-fx-background-radius: 12; " +
            "-fx-border-radius: 12; " +
            "-fx-border-color: " + (isDark ? "rgba(255,255,255,0.08)" : "#E2E8F0") + "; " +
            "-fx-border-width: 1.5; " +
            "-fx-padding: 14 18; " +
            "-fx-font-size: 15px;"
        );
        nameField.textProperty().addListener((obs, old, val) -> {
            nameRef.set(val);
            iconRef.set(getSmartIconForName(val));
            updatePreview.run();
        });
        
        // Selectores
        Node iconSelector = buildIconSelector(iconRef, iconRef.get(), isDark);
        
        // Error
        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: #EF4444; -fx-font-size: 13px;");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        
        // Botones
        Button btnSave = SideDrawer.buildPrimaryButton(existing != null ? "Guardar cambios" : "Crear subcategoría", "fas-check");
        btnSave.setStyle(
            "-fx-background-color: #1E6DFF; " +
            "-fx-text-fill: white; " +
            "-fx-font-weight: 700; " +
            "-fx-font-size: 15px; " +
            "-fx-padding: 16; " +
            "-fx-background-radius: 12; " +
            "-fx-cursor: hand;"
        );
        
        btnSave.setOnAction(e -> {
            errorLabel.setVisible(false);
            errorLabel.setManaged(false);
            
            String name = nameField.getText() == null ? "" : nameField.getText().trim();
            if (name.isBlank()) {
                errorLabel.setText("⚠ El nombre es obligatorio");
                errorLabel.setVisible(true);
                errorLabel.setManaged(true);
                nameField.setStyle(
                    "-fx-background-color: #FEF2F2; " +
                    "-fx-background-radius: 12; " +
                    "-fx-border-radius: 12; " +
                    "-fx-border-color: #EF4444; " +
                    "-fx-border-width: 2; " +
                    "-fx-padding: 14 18; " +
                    "-fx-font-size: 15px;"
                );
                return;
            }
            
            // Guardado real en base de datos
            String userUid = session.uid();
            // Las subcategorías heredan el kind del padre
            String parentKind = parent.kind();
            
            new Thread(() -> {
                try {
                    String selectedIcon = iconRef.get();
                    if (existing != null) {
                        // Actualizar subcategoría existente (nombre e icono)
                        categoryRepo.update(userUid, existing.id(), name, selectedIcon);
                    } else {
                        // Crear nueva subcategoría con parent_id
                        categoryRepo.create(userUid, name, parent.id(), parentKind, selectedIcon);
                    }
                    
                    Platform.runLater(() -> {
                        drawer.hide();
                        onSuccess.run();
                    });
                } catch (SQLException ex) {
                    Platform.runLater(() -> {
                        errorLabel.setText("⚠ Error al guardar: " + ex.getMessage());
                        errorLabel.setVisible(true);
                        errorLabel.setManaged(true);
                    });
                }
            }).start();
        });
        
        Button btnCancel = drawer.buildCancelButton("Cancelar");
        btnCancel.setStyle(
            "-fx-background-color: transparent; " +
            "-fx-text-fill: #64748B; " +
            "-fx-font-weight: 600; " +
            "-fx-font-size: 15px; " +
            "-fx-padding: 16; " +
            "-fx-background-radius: 12; " +
            "-fx-cursor: hand;"
        );
        
        VBox footer = new VBox(10, errorLabel, btnSave, btnCancel);
        footer.setPadding(new Insets(12, 0, 0, 0));
        
        VBox body = new VBox(12, 
            headerRow, 
            subtitle,
            previewSection,
            new VBox(4, nameLabel, nameField),
            iconSelector,
            footer
        );
        body.getStyleClass().add("drawer-body");
        body.setPadding(new Insets(16, 20, 16, 20));
        
        return body;
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // CONFIRMACIÓN DE ELIMINACIÓN MODERNA
    // ═══════════════════════════════════════════════════════════════════════
    
    private static void showDeleteCategoryConfirmation(
            SideDrawer drawer,
            AuthSession session,
            CategoryRepository categoryRepo,
            CategoryRepository.Category category,
            Runnable onSuccess,
            boolean isDark
    ) {
        String userUid = session.uid();
        
        // Header
        Label title = new Label("¿Eliminar categoría?");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: 800; -fx-text-fill: #0F172A;");
        
        Label subtitle = new Label("Esta acción no se puede deshacer");
        subtitle.setStyle("-fx-font-size: 14px; -fx-text-fill: #64748B;");
        
        Button closeBtn = drawer.buildCloseButton();
        HBox headerRow = new HBox(title, new Region(), closeBtn);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(headerRow.getChildren().get(1), Priority.ALWAYS);
        
        // Icono de advertencia
        FontIcon warningIcon = new FontIcon("fas-exclamation-triangle");
        warningIcon.setIconSize(48);
        warningIcon.setIconColor(Color.web("#EF4444"));
        
        Circle warningBg = new Circle(40);
        warningBg.setFill(Color.web("#EF4444", 0.1));
        
        StackPane warningContainer = new StackPane(warningBg, warningIcon);
        warningContainer.setMinSize(80, 80);
        warningContainer.setMaxSize(80, 80);
        
        // Info de la categoría
        Label categoryName = new Label("\"" + category.name() + "\"");
        categoryName.setStyle("-fx-font-size: 18px; -fx-font-weight: 700; -fx-text-fill: #1E293B;");
        
        Label warningText = new Label("Se eliminará esta categoría y todas sus subcategorías. Las transacciones asociadas quedarán sin categoría.");
        warningText.setStyle("-fx-font-size: 14px; -fx-text-fill: #64748B; -fx-wrap-text: true; -fx-text-alignment: center;");
        warningText.setWrapText(true);
        warningText.setMaxWidth(320);
        
        VBox infoBox = new VBox(16, warningContainer, categoryName, warningText);
        infoBox.setAlignment(Pos.CENTER);
        infoBox.setPadding(new Insets(20, 0, 20, 0));
        
        // Error label
        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: #EF4444; -fx-font-size: 13px;");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        
        // Botones
        Button btnDelete = new Button("Sí, eliminar");
        btnDelete.setStyle(
            "-fx-background-color: #EF4444; " +
            "-fx-text-fill: white; " +
            "-fx-font-weight: 700; " +
            "-fx-font-size: 15px; " +
            "-fx-padding: 14; " +
            "-fx-background-radius: 12; " +
            "-fx-cursor: hand; " +
            "-fx-effect: dropshadow(gaussian, rgba(239,68,68,0.3), 8, 0, 0, 2);"
        );
        
        btnDelete.setOnAction(e -> {
            new Thread(() -> {
                try {
                    categoryRepo.delete(userUid, category.id());
                    Platform.runLater(() -> {
                        drawer.hide();
                        onSuccess.run();
                    });
                } catch (SQLException ex) {
                    Platform.runLater(() -> {
                        errorLabel.setText("⚠ Error al eliminar: " + ex.getMessage());
                        errorLabel.setVisible(true);
                        errorLabel.setManaged(true);
                    });
                }
            }).start();
        });
        
        Button btnCancel = new Button("Cancelar");
        btnCancel.setStyle(
            "-fx-background-color: transparent; " +
            "-fx-text-fill: #64748B; " +
            "-fx-font-weight: 600; " +
            "-fx-font-size: 15px; " +
            "-fx-padding: 14; " +
            "-fx-background-radius: 12; " +
            "-fx-cursor: hand; " +
            "-fx-border-color: #E2E8F0; " +
            "-fx-border-width: 1.5; " +
            "-fx-border-radius: 12;"
        );
        btnCancel.setOnAction(e -> drawer.hide());
        
        VBox footer = new VBox(12, errorLabel, btnDelete, btnCancel);
        
        VBox body = new VBox(16, headerRow, subtitle, infoBox, footer);
        body.getStyleClass().add("drawer-body");
        body.setPadding(new Insets(20, 24, 20, 24));
        
        drawer.register("delete-confirmation", isDark, body);
        drawer.show("delete-confirmation");
    }
    
    private static void showDeleteSubcategoryConfirmation(
            SideDrawer drawer,
            AuthSession session,
            CategoryRepository categoryRepo,
            CategoryRepository.Category subcategory,
            CategoryRepository.Category parent,
            Runnable onSuccess,
            boolean isDark
    ) {
        String userUid = session.uid();
        
        // Header
        Label title = new Label("¿Eliminar subcategoría?");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: 800; -fx-text-fill: #0F172A;");
        
        Label subtitle = new Label("Esta acción no se puede deshacer");
        subtitle.setStyle("-fx-font-size: 14px; -fx-text-fill: #64748B;");
        
        Button closeBtn = drawer.buildCloseButton();
        HBox headerRow = new HBox(title, new Region(), closeBtn);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(headerRow.getChildren().get(1), Priority.ALWAYS);
        
        // Icono de advertencia
        FontIcon warningIcon = new FontIcon("fas-exclamation-triangle");
        warningIcon.setIconSize(48);
        warningIcon.setIconColor(Color.web("#EF4444"));
        
        Circle warningBg = new Circle(40);
        warningBg.setFill(Color.web("#EF4444", 0.1));
        
        StackPane warningContainer = new StackPane(warningBg, warningIcon);
        warningContainer.setMinSize(80, 80);
        warningContainer.setMaxSize(80, 80);
        
        // Info de la subcategoría
        Label subcategoryName = new Label("\"" + subcategory.name() + "\"");
        subcategoryName.setStyle("-fx-font-size: 18px; -fx-font-weight: 700; -fx-text-fill: #1E293B;");
        
        Label parentInfo = new Label("Dentro de: " + parent.name());
        parentInfo.setStyle("-fx-font-size: 13px; -fx-text-fill: #94A3B8;");
        
        Label warningText = new Label("Se eliminará esta subcategoría. Las transacciones asociadas quedarán sin categoría.");
        warningText.setStyle("-fx-font-size: 14px; -fx-text-fill: #64748B; -fx-wrap-text: true; -fx-text-alignment: center;");
        warningText.setWrapText(true);
        warningText.setMaxWidth(320);
        
        VBox infoBox = new VBox(12, warningContainer, subcategoryName, parentInfo, warningText);
        infoBox.setAlignment(Pos.CENTER);
        infoBox.setPadding(new Insets(20, 0, 20, 0));
        
        // Error label
        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: #EF4444; -fx-font-size: 13px;");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        
        // Botones
        Button btnDelete = new Button("Sí, eliminar");
        btnDelete.setStyle(
            "-fx-background-color: #EF4444; " +
            "-fx-text-fill: white; " +
            "-fx-font-weight: 700; " +
            "-fx-font-size: 15px; " +
            "-fx-padding: 14; " +
            "-fx-background-radius: 12; " +
            "-fx-cursor: hand; " +
            "-fx-effect: dropshadow(gaussian, rgba(239,68,68,0.3), 8, 0, 0, 2);"
        );
        
        btnDelete.setOnAction(e -> {
            btnDelete.setDisable(true);
            new Thread(() -> {
                try {
                    long txCount = categoryRepo.countTransactions(userUid, subcategory.id());
                    if (txCount > 0) {
                        Platform.runLater(() -> {
                            btnDelete.setDisable(false);
                            errorLabel.setText("⚠ No se puede eliminar: esta subcategoría tiene " + txCount +
                                " transacción" + (txCount == 1 ? "" : "es") + " asociada" + (txCount == 1 ? "" : "s") +
                                ". Reasigna o elimina las transacciones primero.");
                            errorLabel.setVisible(true);
                            errorLabel.setManaged(true);
                        });
                        return;
                    }
                    categoryRepo.deleteSubcategory(userUid, subcategory.id());
                    Platform.runLater(() -> {
                        drawer.hide();
                        onSuccess.run();
                    });
                } catch (SQLException ex) {
                    Platform.runLater(() -> {
                        btnDelete.setDisable(false);
                        errorLabel.setText("⚠ Error al eliminar: " + ex.getMessage());
                        errorLabel.setVisible(true);
                        errorLabel.setManaged(true);
                    });
                }
            }).start();
        });
        
        Button btnCancel = new Button("Cancelar");
        btnCancel.setStyle(
            "-fx-background-color: transparent; " +
            "-fx-text-fill: #64748B; " +
            "-fx-font-weight: 600; " +
            "-fx-font-size: 15px; " +
            "-fx-padding: 14; " +
            "-fx-background-radius: 12; " +
            "-fx-cursor: hand; " +
            "-fx-border-color: #E2E8F0; " +
            "-fx-border-width: 1.5; " +
            "-fx-border-radius: 12;"
        );
        btnCancel.setOnAction(e -> drawer.hide());
        
        VBox footer = new VBox(12, errorLabel, btnDelete, btnCancel);
        
        VBox body = new VBox(16, headerRow, subtitle, infoBox, footer);
        body.getStyleClass().add("drawer-body");
        body.setPadding(new Insets(20, 24, 20, 24));
        
        drawer.register("delete-subcategory-confirmation", isDark, body);
        drawer.show("delete-subcategory-confirmation");
    }
}
