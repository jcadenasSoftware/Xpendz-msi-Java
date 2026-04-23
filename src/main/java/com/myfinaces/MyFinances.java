/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 */

package com.myfinaces;

import com.myfinaces.auth.AuthSession;
import com.myfinaces.auth.FirebaseAuthService;
import com.myfinaces.config.AppConfig;
import com.myfinaces.db.AppSchema;
import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.GoalRepository;
import com.myfinaces.db.BudgetRepository;
import com.myfinaces.db.LoanPaymentRepository;
import com.myfinaces.db.LoanRepository;
import com.myfinaces.db.TransferRepository;
import com.myfinaces.db.TransactionRepository;
import com.myfinaces.db.UserRepository;
import com.myfinaces.db.SessionRepository;
import com.myfinaces.db.SqliteDatabase;
import com.myfinaces.ui.DashboardView;
import com.myfinaces.ui.LoginView;
import javafx.application.Application;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

public class MyFinances extends Application {

    private static String appTitle() {
        return AppVersion.withVersion("Xpendz");
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        AppConfig config = AppConfig.loadDefault();
        SqliteDatabase db = SqliteDatabase.defaultDatabase();

        AppSchema.init(db);
        SessionRepository sessionRepo = new SessionRepository(db);
        sessionRepo.init();

        UserRepository userRepo = new UserRepository(db);
        AccountRepository accountRepo = new AccountRepository(db);
        CategoryRepository categoryRepo = new CategoryRepository(db);
        GoalRepository goalRepo = new GoalRepository(db);
        TransactionRepository txRepo = new TransactionRepository(db);
        TransferRepository transferRepo = new TransferRepository(db);
        LoanRepository loanRepo = new LoanRepository(db);
        LoanPaymentRepository loanPaymentRepo = new LoanPaymentRepository(db);
        BudgetRepository budgetRepo = new BudgetRepository(db);

        FirebaseAuthService authService = new FirebaseAuthService(config.firebaseApiKey());
        String googleClientId = config.googleOAuthClientId();
        String googleClientSecret = config.googleOAuthClientSecret();

        Scene scene = new Scene(new javafx.scene.layout.VBox());

        BooleanProperty darkTheme = new SimpleBooleanProperty(false);
        applyTheme(scene, darkTheme.get());
        darkTheme.addListener((obs, oldV, newV) -> applyTheme(scene, Boolean.TRUE.equals(newV)));

        primaryStage.setTitle(appTitle());
        try {
            var iconStream = MyFinances.class.getResourceAsStream("/images/xpendz.png");
            if (iconStream == null) {
                iconStream = MyFinances.class.getResourceAsStream("/images/logo.png");
            }
            if (iconStream != null) {
                primaryStage.getIcons().add(new Image(iconStream));
            }
        } catch (Exception ignored) {
        }
        primaryStage.setScene(scene);

        // Pantalla inicial
        showLogin(scene, authService, googleClientId, googleClientSecret, sessionRepo, userRepo, accountRepo, categoryRepo, goalRepo, txRepo, transferRepo, loanRepo, loanPaymentRepo, budgetRepo, darkTheme);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }

    private static void showLogin(Scene scene, FirebaseAuthService authService, String googleClientId, String googleClientSecret, SessionRepository sessionRepo, UserRepository userRepo, AccountRepository accountRepo, CategoryRepository categoryRepo, GoalRepository goalRepo, TransactionRepository txRepo, TransferRepository transferRepo, LoanRepository loanRepo, LoanPaymentRepository loanPaymentRepo, BudgetRepository budgetRepo, BooleanProperty darkTheme) {
        if (scene.getWindow() instanceof Stage stage) {
            stage.setMaximized(false);
            stage.setResizable(false);
        }
        var root = LoginView.create(authService, googleClientId, googleClientSecret, sessionRepo, session -> {
            try {
                userRepo.upsert(session.uid(), session.email());
            } catch (Exception ignored) {
            }
            showDashboard(scene, authService, googleClientId, googleClientSecret, sessionRepo, userRepo, accountRepo, categoryRepo, goalRepo, txRepo, transferRepo, loanRepo, loanPaymentRepo, budgetRepo, darkTheme, session);
        });
        scene.setRoot(root);

        if (scene.getWindow() instanceof Stage stage) {
            stage.sizeToScene();
            stage.centerOnScreen();
        }
    }

    private static void showDashboard(Scene scene, FirebaseAuthService authService, String googleClientId, String googleClientSecret, SessionRepository sessionRepo, UserRepository userRepo, AccountRepository accountRepo, CategoryRepository categoryRepo, GoalRepository goalRepo, TransactionRepository txRepo, TransferRepository transferRepo, LoanRepository loanRepo, LoanPaymentRepository loanPaymentRepo, BudgetRepository budgetRepo, BooleanProperty darkTheme, AuthSession session) {
        if (scene.getWindow() instanceof Stage stage) {
            stage.setFullScreen(false);
            stage.setResizable(true);
            stage.setMaximized(true);
            stage.setOnCloseRequest(ev -> ev.consume());
        }
        scene.setRoot(DashboardView.create(session, () -> {
            try {
                sessionRepo.clear();
            } catch (Exception ignored) {
                // Si falla limpiar sesión, igual dejamos salir.
            }
            showLogin(scene, authService, googleClientId, googleClientSecret, sessionRepo, userRepo, accountRepo, categoryRepo, goalRepo, txRepo, transferRepo, loanRepo, loanPaymentRepo, budgetRepo, darkTheme);
        }, accountRepo, categoryRepo, goalRepo, txRepo, transferRepo, loanRepo, loanPaymentRepo, budgetRepo, darkTheme));
    }

    private static void applyTheme(Scene scene, boolean dark) {
        String css = dark ? "/styles/dark.css" : "/styles/light.css";
        String url = MyFinances.class.getResource(css) == null ? null : MyFinances.class.getResource(css).toExternalForm();
        scene.getStylesheets().clear();
        if (url != null) {
            scene.getStylesheets().add(url);
        }
    }
}
