# Xpendz — Contexto Arquitectónico del Proyecto

> Usa este archivo al inicio de una nueva sesión de pair-programming para dar contexto inmediato al modelo, sin necesidad de re-explorar todo el código.

## 1. Visión General

- **Tipo:** Aplicación de escritorio JavaFX (JDK 17+) con Maven.
- **Nombre:** Xpendz (anteriormente "Mis Finanzas").
- **Arquitectura:** Monolito desktop con capas claramente separadas.
- **Persistencia:** SQLite local (`~/.myfinances/myfinances.db`).
- **Auth:** Firebase Authentication (email/password + Google OAuth PKCE).
- **Sync:** Firestore REST API para sincronización de datos en la nube.

## 2. Estructura de Paquetes

```
com.myfinaces
├── auth           → AuthSession, FirebaseAuthService, GooglePkceAuthService, GoogleDeviceAuthService
├── config         → AppConfig (lee app.properties)
├── db             → SqliteDatabase, AppSchema, Repositorios (CRUD SQL nativo)
├── sync           → FirestoreSyncService, DeviceId
└── ui             → LoginView, DashboardView, Dialogs, Panes, Formatters
```

### 2.1 Capa `auth`
- **`AuthSession`** — `record` con uid, email, displayName, idToken, refreshToken, expiresAtEpochSec.
- **`FirebaseAuthService`** — HTTP client para signUp, signIn, refresh contra Firebase Auth REST API.
- **`GooglePkceAuthService`** — Flujo OAuth2 PKCE con servidor HTTP local para callback. Devuelve `GoogleTokens`.
- **`GoogleDeviceAuthService`** — `@Deprecated`. Flujo device code legacy. No se usa pero existe en código.

### 2.2 Capa `config`
- **`AppConfig`** — Carga `app.properties` (firebaseApiKey, firebaseProjectId, googleOAuthClientId, googleOAuthClientSecret). Lanza si falta algo.

### 2.3 Capa `db` (Data Access)
- **`SqliteDatabase`** — Abre `Connection` JDBC a SQLite y setea `PRAGMA foreign_keys = ON`.
- **`AppSchema`** — DDL: crea/actualiza tablas (`users`, `accounts`, `categories`, `transactions`, `transfers`, `budgets`, `goals`, `loans`, `loan_payments`, `outbox`). Maneja migraciones simples (`columnExists`).
- **Repositorios** — Cada uno recibe `SqliteDatabase` en constructor. Usan `try (Connection c = db.openConnection(); PreparedStatement ps = ...)` directamente. **NO hay ORM ni JPA.**
  - `AccountRepository`, `CategoryRepository`, `TransactionRepository`, `TransferRepository`
  - `GoalRepository`, `LoanRepository`, `LoanPaymentRepository`, `BudgetRepository`
  - `SessionRepository`, `UserRepository`
- **Records internos** — Cada repo define records locales para filas DTO (ej. `TransactionRepository.TransactionRow`, `TransferRepository.TransferRow`, `BudgetRepository.BudgetProgress`).
- **`TransactionKind`** — `enum` que clasifica ingresos, gastos, préstamos, etc., y determina si afectan cash-in o cash-out.

### 2.4 Capa `sync`
- **`FirestoreSyncService`** — HTTP client para push/pull de documentos a Firestore. Requiere `AuthSession.idToken`.
- **`DeviceId`** — Genera/leé un ID único persistente por máquina (`~/.myfinances/device-id.txt`).

### 2.5 Capa `ui` (Presentation)
- **Vistas estáticas (factory methods)** — Ninguna clase extiende `Node`. Todos usan métodos `public static` que devuelven `Parent` o `Node`.
  - `LoginView.create(...)` → `Parent`
  - `DashboardView.create(...)` → `Parent` (recibe todos los repos + sync + config)
- **Paneles auxiliares estáticos** — `DashboardSidebarPane`, `DashboardHeaderPane`, `DashboardBalancesPane`, `DashboardSyncCoordinator`.
- **Formatters** — `DashboardFormatters` (moneda, decimales, parseo).
- **Dialogs** — Todos son clases `final` con métodos `public static` para mostrar modales:
  - `DashboardTransactionsDialog`, `DashboardTransfersDialog`, `DashboardCategoriesDialog`
  - `DashboardBudgetDialog`, `DashboardGoalsDialog`, `DashboardLoansDialog`
  - `DashboardChartsDialog`, `DashboardSummaryDialog`
  - `DashboardAccountsFeature` (crear/editar cuentas)
- **`UiDialogs`** — Utilidades transversales: aplicar tema oscuro/claro, asignar ícono de app a `Dialog`/`Alert`.

## 3. Patrones y Convenciones

- **UI estilo procedural/funcional:** No se usan controllers FXML ni MVC clásico. Se construye todo con lambdas y métodos estáticos.
- **Records para DTOs:** Casi todos los objetos de transferencia son Java Records.
- **Repos con SQL crudo:** Cada repo maneja sus propios `PreparedStatement` y `ResultSet`. Sin abstracción compartida (intencional por ahora).
- **Diálogos modales con `Dialog<ButtonType>`:** Todos los diálogos usan JavaFX `Dialog`. El CSS del tema (claro/oscuro) se inyecta vía `UiDialogs.applyAppTheme(dialog, darkTheme)`.
- **Temas CSS:** Dos archivos grandes (`light.css`, `dark.css`). La app detecta tema y carga el correspondiente. CSS inyectado también a popups internos de JavaFX (ej. `DatePicker`).

## 4. Dependencias Clave entre Capas

```
MyFinances (entry point)
  → AppConfig
  → SqliteDatabase → AppSchema
  → *Repository (todos reciben SqliteDatabase)
  → FirebaseAuthService
  → LoginView
  → DashboardView

DashboardView
  → DashboardSidebarPane / HeaderPane / BalancesPane
  → DashboardSyncCoordinator → FirestoreSyncService
  → Todos los *Dialog y *Feature
  → Todos los Repositorios

FirestoreSyncService
  → *Repository (para leer/escribir datos locales)
  → AuthSession (para token)
  → AppConfig (para projectId)

LoginView
  → FirebaseAuthService / GooglePkceAuthService
  → SessionRepository
```

## 5. Redundancias Intencionales (NO refactorizar aún)

- **Labels de tipo de cuenta** (`accountTypeLabel`): existe en `DashboardAccountsFeature`, `DashboardTransactionsDialog`, `DashboardTransfersDialog`.
- **Formato de label de cuenta** (`formatAccountLabel`): existe en `DashboardTransactionsDialog` y `DashboardTransfersDialog`.
- **Boilerplate de `Dialog` setup:** ~15 líneas repetidas al inicio de casi todos los dialogs.
- **CSS injection del `DatePicker` popup:** lógica larga replicada donde se usa `DatePicker`.
- **`GoogleDeviceAuthService`:** marcado `@Deprecated`. No eliminar hasta confirmar que no se necesitará.
- **`SessionRepository.init()`:** crea su propia tabla. DDL fragmentado entre `AppSchema` e `SessionRepository`.

> **Regla del proyecto:** No crear abstracciones genéricas (`AbstractRepository`, builders, utils) hasta que la app esté completa y se confirmen exactamente los puntos de repetición estables.

## 6. Decisiones de Diseño que Debes Respetar

1. **No usar FXML ni MVC.** Toda la UI es código Java con métodos estáticos factory.
2. **No usar JPA/Hibernate.** SQL crudo intencional para control total del esquema SQLite.
3. **Métodos estáticos para UI.** No extender clases de JavaFX; devolver `Parent` desde métodos `static`.
4. **Tema oscuro/claro.** La app mantiene estado `darkTheme` boolean y recarga CSS global + popups.
5. **Sincronización manual.** No hay sync automático en background; el usuario dispara sync explícitamente desde el dashboard.

## 7. Archivos Clave

| Archivo | Propósito |
|---------|-----------|
| `src/main/java/com/myfinaces/MyFinances.java` | `Application.start()` — bootstrap de DB, repos, auth, routing Login→Dashboard |
| `src/main/java/com/myfinaces/config/AppConfig.java` | Config runtime desde `app.properties` |
| `src/main/java/com/myfinaces/db/AppSchema.java` | DDL SQLite y migraciones |
| `src/main/java/com/myfinaces/db/SqliteDatabase.java` | Conexión JDBC |
| `src/main/resources/styles/light.css` / `dark.css` | Temas completos de la aplicación |
| `docs/diagrams/class-diagram.puml` | Diagrama de clases actualizado |
| `docs/ARCHITECTURE.md` | **Este archivo** |

---

*Actualizado: 2026-04-21*
