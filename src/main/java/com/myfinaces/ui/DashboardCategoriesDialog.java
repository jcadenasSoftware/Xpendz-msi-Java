package com.myfinaces.ui;

import com.myfinaces.auth.AuthSession;
import com.myfinaces.config.AppConfig;
import com.myfinaces.db.CategoryRepository;
import com.myfinaces.sync.FirestoreSyncService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class DashboardCategoriesDialog {

    private DashboardCategoriesDialog() {
    }

    public static void showCategoriesDialog(AuthSession session, CategoryRepository categoryRepo, boolean darkTheme) {
        String userUid = session.uid();
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Categorías");
        UiDialogs.applyAppTheme(dialog, darkTheme);
        ButtonType closeBtn = new ButtonType("Cerrar", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(closeBtn);

        Label headerTitle = new Label("Categorías");
        headerTitle.getStyleClass().add("app-title");
        Label headerDesc = new Label("Crea, edita o elimina categorías y subcategorías para organizar tus movimientos.");
        headerDesc.getStyleClass().add("text-secondary");
        headerDesc.setWrapText(true);
        VBox header = new VBox(4, headerTitle, headerDesc);
        header.getStyleClass().add("dialog-header");
        header.setAlignment(Pos.CENTER);
        header.setMaxWidth(Double.MAX_VALUE);
        dialog.getDialogPane().setHeader(header);

        Label error = new Label();
        error.getStyleClass().add("error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        ListView<CategoryRepository.Category> roots = new ListView<>();
        ListView<CategoryRepository.Category> children = new ListView<>();
        roots.getStyleClass().add("categories-list");
        children.getStyleClass().add("categories-list");
        roots.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        children.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

        roots.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            {
                getStyleClass().add("account-name");
            }

            @Override
            protected void updateItem(CategoryRepository.Category item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        });
        children.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            {
                getStyleClass().add("account-name");
            }

            @Override
            protected void updateItem(CategoryRepository.Category item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        });

        Runnable clearError = () -> {
            error.setText("");
            error.setVisible(false);
            error.setManaged(false);
        };

        Runnable showError = () -> {
            error.setVisible(true);
            error.setManaged(true);
        };

        AtomicReference<Runnable> refreshRootsRef = new AtomicReference<>();
        AtomicReference<Runnable> refreshChildrenRef = new AtomicReference<>();

        Runnable pullCategories = () -> {
            try {
                AppConfig cfg = AppConfig.loadDefault();
                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                List<CategoryRepository.Category> remote = sync.pullCategories(session);

                Set<String> remoteIds = new HashSet<>();
                for (CategoryRepository.Category c : remote) {
                    remoteIds.add(c.id());
                }

                List<CategoryRepository.Category> rootRemote = new ArrayList<>();
                List<CategoryRepository.Category> childRemote = new ArrayList<>();
                for (CategoryRepository.Category c : remote) {
                    if (c.parentId() == null || c.parentId().isBlank()) {
                        rootRemote.add(c);
                    } else {
                        childRemote.add(c);
                    }
                }

                for (CategoryRepository.Category c : rootRemote) {
                    categoryRepo.upsertFromRemote(userUid, c);
                }

                for (int pass = 0; pass < 5; pass++) {
                    boolean progressed = false;
                    for (CategoryRepository.Category c : childRemote) {
                        if (c.parentId() == null || c.parentId().isBlank()) {
                            continue;
                        }
                        if (categoryRepo.getById(userUid, c.parentId()) == null) {
                            continue;
                        }
                        categoryRepo.upsertFromRemote(userUid, c);
                        progressed = true;
                    }
                    if (!progressed) {
                        break;
                    }
                }

                List<CategoryRepository.Category> localAll = categoryRepo.listAll(userUid);
                List<String> toDeleteChildren = new ArrayList<>();
                List<String> toDeleteRoots = new ArrayList<>();
                for (CategoryRepository.Category c : localAll) {
                    if (remoteIds.contains(c.id())) {
                        continue;
                    }
                    if (c.parentId() == null || c.parentId().isBlank()) {
                        toDeleteRoots.add(c.id());
                    } else {
                        toDeleteChildren.add(c.id());
                    }
                }

                for (String id : toDeleteChildren) {
                    try {
                        categoryRepo.delete(userUid, id);
                    } catch (Exception ignored) {
                    }
                }
                for (String id : toDeleteRoots) {
                    try {
                        categoryRepo.delete(userUid, id);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        };

        Runnable doRefreshNow = () -> {
            new Thread(() -> {
                pullCategories.run();
                Platform.runLater(() -> {
                    Runnable rr = refreshRootsRef.get();
                    if (rr != null) {
                        rr.run();
                    }
                    Runnable rc = refreshChildrenRef.get();
                    if (rc != null) {
                        rc.run();
                    }
                });
            }).start();
        };

        dialog.getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, ev -> {
            if (ev.getCode() == KeyCode.F5) {
                doRefreshNow.run();
                ev.consume();
            }
        });

        Runnable refreshRoots = () -> {
            try {
                String selectedId = roots.getSelectionModel().getSelectedItem() == null
                    ? null
                    : roots.getSelectionModel().getSelectedItem().id();
                roots.getItems().setAll(categoryRepo.listRoots(userUid));
                if (selectedId != null) {
                    for (CategoryRepository.Category c : roots.getItems()) {
                        if (selectedId.equals(c.id())) {
                            roots.getSelectionModel().select(c);
                            break;
                        }
                    }
                }
            } catch (Exception ex) {
                roots.getItems().clear();
                error.setText(ex.getMessage() == null ? "No se pudieron cargar las categorías." : ex.getMessage());
                showError.run();
            }
        };

        refreshRootsRef.set(refreshRoots);

        Runnable refreshChildren = () -> {
            CategoryRepository.Category selected = roots.getSelectionModel().getSelectedItem();
            if (selected == null) {
                children.getItems().clear();
                return;
            }
            try {
                String selectedId = children.getSelectionModel().getSelectedItem() == null
                    ? null
                    : children.getSelectionModel().getSelectedItem().id();
                children.getItems().setAll(categoryRepo.listChildren(userUid, selected.id()));
                if (selectedId != null) {
                    for (CategoryRepository.Category c : children.getItems()) {
                        if (selectedId.equals(c.id())) {
                            children.getSelectionModel().select(c);
                            break;
                        }
                    }
                }
            } catch (Exception ex) {
                children.getItems().clear();
                error.setText(ex.getMessage() == null ? "No se pudieron cargar las subcategorías." : ex.getMessage());
                showError.run();
            }
        };

        refreshChildrenRef.set(refreshChildren);

        roots.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            children.getSelectionModel().clearSelection();
            refreshChildren.run();
        });

        java.util.function.Function<Exception, String> friendlyDeleteError = ex -> {
            String m = ex == null ? null : ex.getMessage();
            if (m == null || m.isBlank()) {
                return "No se puede eliminar porque tiene saldo o movimientos asociados. Deja el saldo en cero y vuelve a intentar.";
            }
            String u = m.toUpperCase(java.util.Locale.ROOT);
            if (u.contains("SQLITE_CONSTRAINT") || u.contains("CONSTRAINT") || u.contains("FOREIGN") || u.contains("TRIGGER")) {
                return "No se puede eliminar porque tiene saldo o movimientos asociados. Deja el saldo en cero y vuelve a intentar.";
            }
            return m;
        };

        refreshRoots.run();

        Button newRoot = new Button("Nueva categoría");
        newRoot.getStyleClass().add("btn-primary");
        newRoot.setOnAction(e -> {
            clearError.run();
            TextInputDialog d = new TextInputDialog();
            d.setTitle("Nueva categoría");
            d.setHeaderText(null);
            d.setGraphic(null);
            d.setContentText("Nombre");
            UiDialogs.applyAppTheme(d, darkTheme);
            d.getDialogPane().setMinWidth(560);
            d.getDialogPane().setPrefWidth(560);
            d.showAndWait().ifPresent(name -> {
                String n = name == null ? "" : name.trim();
                if (n.isBlank()) {
                    return;
                }
                try {
                    CategoryRepository.Category created = categoryRepo.create(userUid, n, null);
                    try {
                        AppConfig cfg = AppConfig.loadDefault();
                        FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                        sync.syncCategory(session, created);
                    } catch (Exception ignored) {
                    }
                    refreshRoots.run();
                    roots.getSelectionModel().select(created);
                } catch (Exception ignored) {
                    error.setText(ignored.getMessage() == null ? "No se pudo crear la categoría." : ignored.getMessage());
                    showError.run();
                }
            });
        });

        Button editRoot = new Button("Editar");
        editRoot.getStyleClass().add("btn-secondary");
        editRoot.disableProperty().bind(roots.getSelectionModel().selectedItemProperty().isNull());
        editRoot.setOnAction(e -> {
            clearError.run();
            CategoryRepository.Category selected = roots.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            TextInputDialog d = new TextInputDialog(selected.name());
            d.setTitle("Editar categoría");
            d.setHeaderText(null);
            d.setGraphic(null);
            d.setContentText("Nombre");
            UiDialogs.applyAppTheme(d, darkTheme);
            d.getDialogPane().setMinWidth(560);
            d.getDialogPane().setPrefWidth(560);
            d.showAndWait().ifPresent(name -> {
                String n = name == null ? "" : name.trim();
                if (n.isBlank()) {
                    return;
                }
                try {
                    categoryRepo.rename(userUid, selected.id(), n);
                    try {
                        CategoryRepository.Category updated = categoryRepo.getById(userUid, selected.id());
                        if (updated != null) {
                            AppConfig cfg = AppConfig.loadDefault();
                            FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                            sync.syncCategory(session, updated);
                        }
                    } catch (Exception ignored) {
                    }
                    refreshRoots.run();
                    for (CategoryRepository.Category c : roots.getItems()) {
                        if (selected.id().equals(c.id())) {
                            roots.getSelectionModel().select(c);
                            break;
                        }
                    }
                } catch (Exception ignored) {
                    error.setText(ignored.getMessage() == null ? "No se pudo editar la categoría." : ignored.getMessage());
                    showError.run();
                }
            });
        });

        Button deleteRoot = new Button("Eliminar");
        deleteRoot.getStyleClass().add("btn-danger");
        deleteRoot.disableProperty().bind(roots.getSelectionModel().selectedItemProperty().isNull());
        deleteRoot.setOnAction(e -> {
            clearError.run();
            CategoryRepository.Category selected = roots.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            Dialog<ButtonType> confirm = new Dialog<>();
            confirm.setTitle("Eliminar");
            confirm.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
            confirm.setContentText("¿Eliminar la categoría '" + selected.name() + "' y sus subcategorías?");
            UiDialogs.applyAppTheme(confirm, darkTheme);
            confirm.showAndWait().ifPresent(btn -> {
                if (btn != ButtonType.OK) {
                    return;
                }
                try {
                    categoryRepo.delete(userUid, selected.id());
                    try {
                        AppConfig cfg = AppConfig.loadDefault();
                        FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                        sync.deleteCategory(session, selected.id());
                    } catch (Exception ignored) {
                    }
                    refreshRoots.run();
                    children.getItems().clear();
                } catch (Exception ignored) {
                    Alert alert = new Alert(AlertType.ERROR);
                    alert.setTitle("Error");
                    alert.setHeaderText("No se pudo eliminar la categoría.");
                    alert.setContentText(friendlyDeleteError.apply(ignored));
                    UiDialogs.applyAppTheme(alert, darkTheme);
                    alert.showAndWait();
                }
            });
        });

        Button newChild = new Button("Nueva subcategoría");
        newChild.getStyleClass().add("btn-primary");
        newChild.disableProperty().bind(roots.getSelectionModel().selectedItemProperty().isNull());
        newChild.setOnAction(e -> {
            clearError.run();
            CategoryRepository.Category parent = roots.getSelectionModel().getSelectedItem();
            if (parent == null) {
                return;
            }
            TextInputDialog d = new TextInputDialog();
            d.setTitle("Nueva subcategoría");
            d.setHeaderText(parent.name());
            d.setGraphic(null);
            d.setContentText("Nombre");
            UiDialogs.applyAppTheme(d, darkTheme);
            d.getDialogPane().setMinWidth(560);
            d.getDialogPane().setPrefWidth(560);
            d.showAndWait().ifPresent(name -> {
                String n = name == null ? "" : name.trim();
                if (n.isBlank()) {
                    return;
                }
                try {
                    CategoryRepository.Category created = categoryRepo.create(userUid, n, parent.id());
                    try {
                        AppConfig cfg = AppConfig.loadDefault();
                        FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                        sync.syncCategory(session, created);
                    } catch (Exception ignored) {
                    }
                    refreshChildren.run();
                    children.getSelectionModel().select(created);
                } catch (Exception ignored) {
                    error.setText(ignored.getMessage() == null ? "No se pudo crear la subcategoría." : ignored.getMessage());
                    showError.run();
                }
            });
        });

        Button editChild = new Button("Editar");
        editChild.getStyleClass().add("btn-secondary");
        editChild.disableProperty().bind(children.getSelectionModel().selectedItemProperty().isNull());
        editChild.setOnAction(e -> {
            clearError.run();
            CategoryRepository.Category selected = children.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            TextInputDialog d = new TextInputDialog(selected.name());
            d.setTitle("Editar subcategoría");
            d.setHeaderText(null);
            d.setGraphic(null);
            d.setContentText("Nombre");
            UiDialogs.applyAppTheme(d, darkTheme);
            d.getDialogPane().setMinWidth(560);
            d.getDialogPane().setPrefWidth(560);
            d.showAndWait().ifPresent(name -> {
                String n = name == null ? "" : name.trim();
                if (n.isBlank()) {
                    return;
                }
                try {
                    categoryRepo.rename(userUid, selected.id(), n);
                    try {
                        CategoryRepository.Category updated = categoryRepo.getById(userUid, selected.id());
                        if (updated != null) {
                            AppConfig cfg = AppConfig.loadDefault();
                            FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                            sync.syncCategory(session, updated);
                        }
                    } catch (Exception ignored) {
                    }
                    refreshChildren.run();
                } catch (Exception ignored) {
                    error.setText(ignored.getMessage() == null ? "No se pudo editar la subcategoría." : ignored.getMessage());
                    showError.run();
                }
            });
        });

        Button deleteChild = new Button("Eliminar");
        deleteChild.getStyleClass().add("btn-danger");
        deleteChild.disableProperty().bind(children.getSelectionModel().selectedItemProperty().isNull());
        deleteChild.setOnAction(e -> {
            clearError.run();
            CategoryRepository.Category selected = children.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            Dialog<ButtonType> confirm = new Dialog<>();
            confirm.setTitle("Eliminar");
            confirm.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
            confirm.setContentText("¿Eliminar la subcategoría '" + selected.name() + "'?");
            UiDialogs.applyAppTheme(confirm, darkTheme);
            confirm.showAndWait().ifPresent(btn -> {
                if (btn != ButtonType.OK) {
                    return;
                }
                try {
                    categoryRepo.delete(userUid, selected.id());
                    try {
                        AppConfig cfg = AppConfig.loadDefault();
                        FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                        sync.deleteCategory(session, selected.id());
                    } catch (Exception ignored) {
                    }
                    refreshChildren.run();
                } catch (Exception ignored) {
                    Alert alert = new Alert(AlertType.ERROR);
                    alert.setTitle("Error");
                    alert.setHeaderText("No se pudo eliminar la subcategoría.");
                    alert.setContentText(friendlyDeleteError.apply(ignored));
                    UiDialogs.applyAppTheme(alert, darkTheme);
                    alert.showAndWait();
                }
            });
        });

        VBox left = new VBox(10, newRoot, editRoot, deleteRoot, roots);
        VBox right = new VBox(10, newChild, editChild, deleteChild, children);
        left.setPrefWidth(260);
        right.setPrefWidth(260);
        VBox.setVgrow(roots, Priority.ALWAYS);
        VBox.setVgrow(children, Priority.ALWAYS);

        HBox lists = new HBox(14, left, right);
        lists.setAlignment(Pos.CENTER);

        VBox content = new VBox(10, lists, error);
        content.setPadding(new Insets(10));

        dialog.getDialogPane().setContent(content);

        var existingOnShown = dialog.getOnShown();
        dialog.setOnShown(ev -> {
            if (existingOnShown != null) {
                existingOnShown.handle(ev);
            }
            var n = dialog.getDialogPane().lookupButton(closeBtn);
            if (n instanceof Button b) {
                b.getStyleClass().add("btn-secondary");
            }
        });

        dialog.showAndWait();
    }
}
