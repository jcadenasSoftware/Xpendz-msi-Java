package com.myfinaces.service;

import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.GoalRepository;
import com.myfinaces.db.TransferRepository;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Servicio centralizado para operaciones de metas de ahorro.
 * Encapsula la lógica de negocio y coordina GoalRepository, AccountRepository y TransferRepository.
 */
public final class GoalService {

    private final GoalRepository goalRepo;
    private final AccountRepository accountRepo;
    private final TransferRepository transferRepo;

    public GoalService(GoalRepository goalRepo, AccountRepository accountRepo, TransferRepository transferRepo) {
        this.goalRepo = Objects.requireNonNull(goalRepo, "goalRepo");
        this.accountRepo = Objects.requireNonNull(accountRepo, "accountRepo");
        this.transferRepo = Objects.requireNonNull(transferRepo, "transferRepo");
    }

    // ── Consultas ─────────────────────────────────────────────────

    /**
     * Obtiene todas las metas activas de un usuario.
     */
    public List<GoalRepository.Goal> obtenerMetas(String userUid) throws SQLException {
        return goalRepo.listByUser(userUid).stream()
            .filter(g -> GoalRepository.STATUS_OPEN.equals(g.status()))
            .toList();
    }

    /**
     * Obtiene una meta específica por ID.
     */
    public GoalRepository.Goal obtenerMeta(String userUid, String goalId) throws SQLException {
        return goalRepo.getByIdOrNull(userUid, goalId);
    }

    /**
     * Calcula el saldo actual de una meta (lo ahorrado hasta ahora).
     */
    public long calcularSaldo(String userUid, String accountId) {
        try {
            if (userUid == null || accountId == null) {
                return 0L;
            }
            return accountRepo.computeBalanceCents(userUid, accountId);
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Calcula el porcentaje de progreso de una meta.
     */
    public int calcularPorcentaje(long saldoCents, long targetCents) {
        if (targetCents <= 0) {
            return 0;
        }
        return (int) Math.min(100, (saldoCents * 100) / targetCents);
    }

    /**
     * Determina el estado emocional de una meta basado en el porcentaje.
     */
    public EstadoMeta determinarEstado(int porcentaje) {
        if (porcentaje == 0) return EstadoMeta.NUEVA;
        if (porcentaje < 70) return EstadoMeta.EN_PROGRESO;
        if (porcentaje < 100) return EstadoMeta.CASI;
        return EstadoMeta.COMPLETADA;
    }

    /**
     * Genera un mensaje de insight basado en el progreso.
     */
    public String generarInsight(int porcentaje, long faltanCents) {
        if (porcentaje == 0) return "Comienza con $50.000 este mes";
        if (porcentaje < 30) return "Ahorra $100.000 más para llegar al 50%";
        if (porcentaje < 50) return "Ya superaste el inicio 💪";
        if (porcentaje < 70) return "Te faltan 3 aportes similares";
        if (porcentaje < 90) return "¡Ya casi! Un empujón final 🔥";
        if (porcentaje < 100) {
            long faltanMil = faltanCents / 100000;
            return faltanMil > 0 
                ? "Si ahorras $" + faltanMil + "0k más llegas este mes"
                : "Si ahorras $" + (faltanCents / 100) + " más llegas este mes";
        }
        return "¡Meta alcanzada! Celebra 🎉";
    }

    // ── Operaciones CRUD ────────────────────────────────────────

    /**
     * Crea una nueva meta con su cuenta de ahorros asociada.
     */
    public GoalRepository.Goal crearMeta(
            String userUid,
            String name,
            String currency,
            long targetCents,
            long targetDateEpochSec) throws SQLException {
        
        Objects.requireNonNull(userUid, "userUid");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(currency, "currency");
        
        if (targetCents < 0) {
            throw new IllegalArgumentException("targetCents debe ser positivo");
        }

        // Crear cuenta de ahorros asociada
        AccountRepository.Account savings = accountRepo.create(
            userUid, 
            "Meta: " + name.trim(), 
            "SAVINGS", 
            currency
        );

        // Crear la meta
        return goalRepo.create(
            userUid,
            name.trim(),
            currency,
            targetCents,
            targetDateEpochSec,
            savings.id()
        );
    }

    /**
     * Actualiza una meta existente.
     */
    public void actualizarMeta(
            String userUid,
            String goalId,
            String name,
            String currency,
            long targetCents,
            long targetDateEpochSec) throws SQLException {
        
        GoalRepository.Goal goal = goalRepo.getByIdOrNull(userUid, goalId);
        if (goal == null) {
            throw new IllegalArgumentException("Meta no encontrada");
        }

        goalRepo.update(
            userUid,
            goalId,
            name,
            currency,
            targetCents,
            targetDateEpochSec,
            goal.accountId(),
            goal.status()
        );
    }

    /**
     * Elimina una meta y opcionalmente su cuenta asociada.
     */
    public GoalDeletionOutcome eliminarMeta(String userUid, String goalId, boolean eliminarCuenta) throws SQLException {
        GoalRepository.Goal goal = goalRepo.getByIdOrNull(userUid, goalId);
        if (goal == null) {
            throw new IllegalArgumentException("Meta no encontrada");
        }

        long saldoActual = calcularSaldo(userUid, goal.accountId());
        if (saldoActual > 0L) {
            throw new IllegalStateException("goal_has_balance");
        }

        boolean hasHistory = tieneHistorial(userUid, goal.accountId());
        if (!hasHistory) {
            goalRepo.delete(userUid, goalId);
            if (eliminarCuenta) {
                try {
                    accountRepo.delete(userUid, goal.accountId());
                } catch (Exception e) {
                    // Si falla la eliminación de la cuenta, no es crítico
                    // La meta ya fue eliminada
                }
            }
            return GoalDeletionOutcome.DELETED;
        } else {
            goalRepo.archive(userUid, goalId);
            return GoalDeletionOutcome.ARCHIVED;
        }
    }

    public boolean tieneHistorial(String userUid, String accountId) {
        try {
            return accountRepo.hasMovements(userUid, accountId);
        } catch (Exception e) {
            return false;
        }
    }

    public enum GoalDeletionOutcome {
        DELETED,
        ARCHIVED
    }

    // ── Operaciones financieras ─────────────────────────────────

    /**
     * Deposita dinero en una meta desde una cuenta origen.
     */
    public void depositar(
            String userUid,
            String goalId,
            String fromAccountId,
            long amountCents,
            String note) throws SQLException {
        
        GoalRepository.Goal goal = goalRepo.getByIdOrNull(userUid, goalId);
        if (goal == null) {
            throw new IllegalArgumentException("Meta no encontrada");
        }

        if (amountCents <= 0) {
            throw new IllegalArgumentException("El monto debe ser positivo");
        }

        long now = Instant.now().getEpochSecond();

        // Crear transferencia: desde cuenta origen -> cuenta de la meta
        transferRepo.create(
            userUid,
            fromAccountId,
            goal.accountId(),
            amountCents,
            now,
            note != null ? note : "Depósito a meta: " + goal.name()
        );
    }

    /**
     * Retira dinero de una meta hacia una cuenta destino.
     */
    public void retirar(
            String userUid,
            String goalId,
            String toAccountId,
            long amountCents,
            String note) throws SQLException {
        
        GoalRepository.Goal goal = goalRepo.getByIdOrNull(userUid, goalId);
        if (goal == null) {
            throw new IllegalArgumentException("Meta no encontrada");
        }

        // Verificar que hay suficiente saldo
        long saldoActual = calcularSaldo(userUid, goal.accountId());
        if (amountCents > saldoActual) {
            throw new IllegalArgumentException("Saldo insuficiente en la meta");
        }

        if (amountCents <= 0) {
            throw new IllegalArgumentException("El monto debe ser positivo");
        }

        long now = Instant.now().getEpochSecond();

        // Crear transferencia: desde cuenta de la meta -> cuenta destino
        transferRepo.create(
            userUid,
            goal.accountId(),
            toAccountId,
            amountCents,
            now,
            note != null ? note : "Retiro de meta: " + goal.name()
        );
    }

    /**
     * Obtiene los movimientos (transferencias) relacionados con una meta.
     */
    public List<TransferRepository.TransferRow> obtenerMovimientos(String userUid, String accountId) throws SQLException {
        return transferRepo.listFiltered(userUid, accountId, null, null, 1000);
    }

    // ── Predicciones inteligentes ─────────────────────────────────

    /**
     * Calcula el promedio de aportes positivos (depósitos) a una meta.
     * Solo considera los últimos N movimientos (por defecto 5).
     * 
     * @param movimientos Lista de movimientos de la meta
     * @param cuentaMetaId ID de la cuenta de la meta (para identificar depósitos)
     * @param ultimosN Cantidad de movimientos a considerar
     * @return Promedio de depósitos en centavos, o 0 si no hay datos suficientes
     */
    public long calcularPromedioAporte(List<TransferRepository.TransferRow> movimientos, String cuentaMetaId, int ultimosN) {
        if (movimientos == null || movimientos.isEmpty()) {
            return 0L;
        }

        // Filtrar solo depósitos (movimientos hacia la cuenta de la meta)
        // y ordenar por fecha descendente
        List<TransferRepository.TransferRow> depositos = movimientos.stream()
            .filter(m -> cuentaMetaId.equals(m.toAccountId()))
            .sorted((a, b) -> Long.compare(b.occurredAtEpochSec(), a.occurredAtEpochSec()))
            .limit(ultimosN)
            .toList();

        if (depositos.isEmpty()) {
            return 0L;
        }

        long suma = depositos.stream().mapToLong(TransferRepository.TransferRow::amountCents).sum();
        return suma / depositos.size();
    }

    /**
     * Versión simplificada que usa los últimos 5 movimientos por defecto.
     */
    public long calcularPromedioAporte(List<TransferRepository.TransferRow> movimientos, String cuentaMetaId) {
        return calcularPromedioAporte(movimientos, cuentaMetaId, 5);
    }

    /**
     * Calcula el promedio mensual de aportes basado en el tiempo transcurrido.
     * Este método es más preciso porque considera el período entre depósitos.
     * 
     * @param movimientos Lista de movimientos de la meta
     * @param cuentaMetaId ID de la cuenta de la meta
     * @return Promedio mensual en centavos, o 0 si no hay datos suficientes
     */
    public long calcularPromedioMensual(List<TransferRepository.TransferRow> movimientos, String cuentaMetaId) {
        if (movimientos == null || movimientos.isEmpty()) {
            return 0L;
        }

        // Filtrar solo depósitos ordenados por fecha
        List<TransferRepository.TransferRow> depositos = movimientos.stream()
            .filter(m -> cuentaMetaId.equals(m.toAccountId()))
            .sorted((a, b) -> Long.compare(a.occurredAtEpochSec(), b.occurredAtEpochSec()))
            .toList();

        if (depositos.isEmpty()) {
            return 0L;
        }

        // Si hay solo un depósito, usar su monto como promedio
        if (depositos.size() == 1) {
            return depositos.get(0).amountCents();
        }

        // Calcular suma total de depósitos
        long sumaTotal = depositos.stream().mapToLong(TransferRepository.TransferRow::amountCents).sum();

        // Calcular meses transcurridos entre primer y último depósito
        long primerDepositoEpoch = depositos.get(0).occurredAtEpochSec();
        long ultimoDepositoEpoch = depositos.get(depositos.size() - 1).occurredAtEpochSec();
        
        // Convertir a meses (aproximado: 30 días = 2,592,000 segundos)
        long segundosTranscurridos = ultimoDepositoEpoch - primerDepositoEpoch;
        double mesesTranscurridos = segundosTranscurridos / 2592000.0;

        // Si todos los depósitos fueron en el mismo mes, asumir 1 mes
        if (mesesTranscurridos < 1) {
            mesesTranscurridos = 1;
        }

        // Calcular promedio mensual
        return (long) (sumaTotal / mesesTranscurridos);
    }

    /**
     * Estima la fecha de cumplimiento de una meta basada en el ritmo actual.
     * 
     * @param faltanCents Monto faltante para completar la meta
     * @param promedioAporte Promedio de aportes mensuales
     * @return Proyección con fecha estimada y meses restantes, o null si no hay datos
     */
    public ProyeccionMeta estimarCumplimiento(long faltanCents, long promedioAporte) {
        if (faltanCents <= 0) {
            return new ProyeccionMeta("Meta completada", 0, "¡Felicidades!");
        }
        
        if (promedioAporte <= 0) {
            return null; // No hay datos suficientes para proyectar
        }

        // Calcular meses estimados (aproximado: 30 días por mes)
        double mesesExactos = (double) faltanCents / promedioAporte;
        int mesesEstimados = (int) Math.ceil(mesesExactos);

        if (mesesEstimados == 0) {
            mesesEstimados = 1; // Al menos un mes más
        }

        // Calcular fecha estimada
        java.time.LocalDate fechaEstimada = java.time.LocalDate.now()
            .plusMonths(mesesEstimados);
        
        String nombreMes = fechaEstimada.format(
            java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.forLanguageTag("es-CO"))
        );
        
        // Mensaje según proximidad
        String mensaje;
        if (mesesEstimados == 1) {
            mensaje = "¡Este mes la completas!";
        } else if (mesesEstimados <= 3) {
            mensaje = "Muy pronto la tendrás";
        } else if (mesesEstimados <= 6) {
            mensaje = "En menos de medio año";
        } else {
            mensaje = "Sigue con constancia";
        }

        return new ProyeccionMeta(
            capitalizeFirst(nombreMes),
            mesesEstimados,
            mensaje
        );
    }

    /**
     * Estima cuántos aportes faltan para completar la meta.
     * 
     * @param faltanCents Monto faltante
     * @param promedioAporte Promedio de cada aporte
     * @return Texto descriptivo con el número de aportes estimados
     */
    public String estimarAportesRestantes(long faltanCents, long promedioAporte) {
        if (faltanCents <= 0) {
            return "Meta completada";
        }
        
        if (promedioAporte <= 0) {
            return null;
        }

        double aportesExactos = (double) faltanCents / promedioAporte;
        int aportesRedondeados = (int) Math.ceil(aportesExactos);

        if (aportesRedondeados == 1) {
            return "Te falta ~1 aporte más";
        } else if (aportesRedondeados <= 3) {
            return "Te faltan ~" + aportesRedondeados + " aportes";
        } else {
            // Para números grandes, redondear a decenas
            int aportesAprox = ((aportesRedondeados + 4) / 5) * 5;
            return "Te faltan ~" + aportesAprox + " aportes";
        }
    }

    /**
     * Calcula el monto adicional necesario para completar la meta en un periodo específico.
     * 
     * @param faltanCents Monto faltante
     * @param mesesObjetivo Meses en los que se quiere completar
     * @return Mensaje con el monto mensual requerido
     */
    public String calcularAporteRequerido(long faltanCents, int mesesObjetivo) {
        if (faltanCents <= 0) {
            return "Meta completada";
        }
        
        if (mesesObjetivo <= 0) {
            return null;
        }

        long montoMensual = faltanCents / mesesObjetivo;
        
        java.text.NumberFormat nf = java.text.NumberFormat.getCurrencyInstance(
            java.util.Locale.forLanguageTag("es-CO")
        );
        nf.setMaximumFractionDigits(0);
        String montoFormateado = nf.format(montoMensual / 100.0);

        if (mesesObjetivo == 1) {
            return "Aporta " + montoFormateado + " este mes para completarla";
        } else {
            return "Aporta ~" + montoFormateado + "/mes para lograrlo en " + mesesObjetivo + " meses";
        }
    }

    /**
     * Genera un insight contextual (Opción D) según el estado real de la meta:
     * - Atrasado vs fecha → cuánto necesita por mes
     * - Adelantado → cuándo la logra y si va antes de fecha
     * - Recién iniciada (< 10%) → primer hito motivacional
     * - Casi lista (> 80%) → falta exacta para cerrar
     * - Sin historial → sugerencia de primer aporte
     */
    public InsightContextual generarInsightContextual(
            int porcentaje,
            long faltanCents,
            long promedioAporteMensual,
            long targetDateEpochSec) {

        java.text.NumberFormat nf = java.text.NumberFormat.getCurrencyInstance(
            java.util.Locale.forLanguageTag("es-CO"));
        nf.setMaximumFractionDigits(0);

        // Meta completada
        if (porcentaje >= 100) {
            return new InsightContextual("🎉 ¡Meta alcanzada! Felicidades", "#10B981", "COMPLETADA");
        }

        // Sin historial de aportes
        if (promedioAporteMensual <= 0) {
            if (porcentaje == 0) {
                long sugerido = faltanCents / 12;
                return new InsightContextual(
                    "🚀 Comienza con " + nf.format(sugerido / 100.0) + "/mes para lograrlo en un año",
                    "#3B82F6", "SIN_HISTORIAL");
            }
            return new InsightContextual(
                "💡 Haz tu primer aporte para calcular tu ritmo", "#64748B", "SIN_HISTORIAL");
        }

        // Casi lista > 80%
        if (porcentaje >= 80) {
            return new InsightContextual(
                "🔥 ¡Solo faltan " + nf.format(faltanCents / 100.0) + " para completarla!",
                "#F59E0B", "CASI_LISTA");
        }

        // Recién iniciada < 10%
        if (porcentaje < 10) {
            long faltan25 = (long)(faltanCents / (1.0 - 0.25) * 0.25); // monto para llegar al 25%
            return new InsightContextual(
                "🎯 Primer hito: deposita " + nf.format(faltan25 / 100.0) + " más y llegas al 25%",
                "#3B82F6", "INICIO");
        }

        // Calcular meses hasta fecha objetivo
        long mesesHastaFecha = Long.MAX_VALUE;
        if (targetDateEpochSec > 0) {
            java.time.LocalDate hoy = java.time.LocalDate.now();
            java.time.LocalDate fechaObj = java.time.LocalDate.ofInstant(
                java.time.Instant.ofEpochSecond(targetDateEpochSec),
                java.time.ZoneId.systemDefault());
            long dias = java.time.temporal.ChronoUnit.DAYS.between(hoy, fechaObj);
            mesesHastaFecha = Math.max(0, (long) Math.ceil(dias / 30.0));
        }

        // Meses que tomaría al ritmo actual
        double mesesAlRitmoActual = (double) faltanCents / promedioAporteMensual;

        if (mesesHastaFecha < Long.MAX_VALUE) {
            if (mesesHastaFecha <= 0) {
                // Fecha ya pasó
                return new InsightContextual(
                    "⚠️ Fecha vencida — necesitas " + nf.format(faltanCents / 100.0) + " para cerrarla",
                    "#EF4444", "ATRASADO");
            }
            long montoNecesario = faltanCents / mesesHastaFecha;
            if (mesesAlRitmoActual > mesesHastaFecha * 1.15) {
                // Va atrasado (más de 15% sobre el tiempo disponible)
                return new InsightContextual(
                    "⚠️ Necesitas " + nf.format(montoNecesario / 100.0) + "/mes — quedan "
                        + mesesHastaFecha + (mesesHastaFecha == 1 ? " mes" : " meses"),
                    "#EF4444", "ATRASADO");
            } else if (mesesAlRitmoActual < mesesHastaFecha * 0.85) {
                // Va adelantado
                int mesesAntes = (int)(mesesHastaFecha - Math.ceil(mesesAlRitmoActual));
                return new InsightContextual(
                    "✅ Vas bien — lo logras " + mesesAntes + (mesesAntes == 1 ? " mes" : " meses")
                        + " antes de la fecha",
                    "#10B981", "ADELANTADO");
            } else {
                // Va en ritmo
                return new InsightContextual(
                    "📈 En ritmo — " + nf.format(montoNecesario / 100.0) + "/mes te lleva a tiempo",
                    "#3B82F6", "EN_RITMO");
            }
        }

        // Sin fecha límite: solo proyección al ritmo actual
        int meses = (int) Math.ceil(mesesAlRitmoActual);
        java.time.LocalDate fechaEst = java.time.LocalDate.now().plusMonths(meses);
        String mesNombre = fechaEst.format(
            java.time.format.DateTimeFormatter.ofPattern("MMM yyyy",
                java.util.Locale.forLanguageTag("es-CO")));
        return new InsightContextual(
            "📅 A tu ritmo lo logras en " + capitalizeFirst(mesNombre)
                + " — " + nf.format(promedioAporteMensual / 100.0) + "/mes",
            "#3B82F6", "PROYECCION");
    }

    public record InsightContextual(String texto, String color, String tipo) {}

    private static String capitalizeFirst(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    // ── Enums y records auxiliares ───────────────────────────────

    public enum EstadoMeta {
        NUEVA("Nueva", "💡", "#94A3B8"),
        EN_PROGRESO("En progreso", "🚀", "#3B82F6"),
        CASI("Casi", "🔥", "#F59E0B"),
        COMPLETADA("Completada", "🎉", "#10B981");

        public final String label;
        public final String emoji;
        public final String color;

        EstadoMeta(String label, String emoji, String color) {
            this.label = label;
            this.emoji = emoji;
            this.color = color;
        }
    }

    /**
     * DTO con proyección de cumplimiento de una meta.
     */
    public record ProyeccionMeta(
        String fechaEstimada,
        int mesesRestantes,
        String mensajeMotivacional
    ) {}

    /**
     * DTO con información completa de una meta para UI.
     */
    public record MetaInfo(
        GoalRepository.Goal goal,
        long saldoCents,
        long faltanCents,
        int porcentaje,
        EstadoMeta estado,
        String insight,
        String fechaFormateada,
        ProyeccionMeta proyeccion,
        long promedioAporte,
        String aportesRestantes
    ) {
        public long saved() { return saldoCents; }
        public long target() { return goal.targetCents(); }
        public long missing() { return faltanCents; }
        public int percent() { return porcentaje; }
        public String name() { return goal.name(); }
        public String description() { return "Meta de ahorro"; }
        public String icon() { return determinarIcono(goal.name()); }
        public String iconColor() { return estado.color; }
        public String date() { return fechaFormateada; }
        public ProyeccionMeta proyeccion() { return proyeccion; }
        public long promedioAporte() { return promedioAporte; }
        public String aportesRestantes() { return aportesRestantes; }

        private static String determinarIcono(String name) {
            return GoalService.determinarIcono(name);
        }
    }

    public static String determinarIcono(String name) {
        if (name == null || name.isBlank()) return "fas-piggy-bank";
        String n = name.toLowerCase();
        if (n.contains("emergencia") || n.contains("fondo")) return "fas-shield-alt";
        if (n.contains("viaje") || n.contains("vacaciones") || n.contains("pasaporte")) return "fas-plane";
        if (n.contains("laptop") || n.contains("computador") || n.contains("pc")) return "fas-laptop";
        if (n.contains("cámara") || n.contains("foto")) return "fas-camera";
        if (n.contains("casa") || n.contains("apartamento") || n.contains("hogar")) return "fas-home";
        if (n.contains("boda") || n.contains("matrimonio")) return "fas-heart";
        if (n.contains("curso") || n.contains("estudio") || n.contains("universidad")) return "fas-graduation-cap";
        if (n.contains("moto") || n.contains("carro") || n.contains("vehículo")) return "fas-motorcycle";
        if (n.contains("play") || n.contains("juego") || n.contains("consola")) return "fas-gamepad";
        return "fas-piggy-bank";
    }

    // ── Sistema de alertas inteligentes ─────────────────────────

    public enum TipoAlerta {
        ESTANCADA(1, "⚠️", "#F59E0B"),     // Mayor prioridad - amarillo
        CERCANA(2, "🔥", "#EF4444"),       // Alta prioridad - rojo/naranja
        BUEN_PROGRESO(3, "💪", "#10B981"), // Media prioridad - verde
        INFORMATIVA(4, "ℹ️", "#3B82F6"),   // Baja prioridad - azul
        SIN_ALERTA(5, "", "");             // Sin alerta

        public final int prioridad;
        public final String icono;
        public final String color;

        TipoAlerta(int prioridad, String icono, String color) {
            this.prioridad = prioridad;
            this.icono = icono;
            this.color = color;
        }
    }

    public record AlertaMeta(
        TipoAlerta tipo,
        String mensaje,
        String color
    ) {}

    /**
     * Calcula los días transcurridos desde el último depósito.
     * 
     * @param movimientos Lista de movimientos de la meta
     * @param cuentaMetaId ID de la cuenta de la meta
     * @return Días desde el último depósito, o -1 si no hay depósitos
     */
    public int diasSinMovimiento(List<TransferRepository.TransferRow> movimientos, String cuentaMetaId) {
        if (movimientos == null || movimientos.isEmpty()) {
            return -1;
        }

        // Buscar el depósito más reciente
        TransferRepository.TransferRow ultimoDeposito = movimientos.stream()
            .filter(m -> cuentaMetaId.equals(m.toAccountId()))
            .max((a, b) -> Long.compare(a.occurredAtEpochSec(), b.occurredAtEpochSec()))
            .orElse(null);

        if (ultimoDeposito == null) {
            return -1;
        }

        long ultimaFechaEpoch = ultimoDeposito.occurredAtEpochSec();
        long ahoraEpoch = java.time.Instant.now().getEpochSecond();
        
        return (int) ((ahoraEpoch - ultimaFechaEpoch) / (24 * 60 * 60));
    }

    /**
     * Detecta si una meta está estancada (sin movimientos recientes).
     * 
     * @param diasSinMovimiento Días desde el último movimiento
     * @param umbralDías Umbral para considerar estancada (default: 10)
     * @return true si está estancada
     */
    public boolean detectarMetaEstancada(int diasSinMovimiento, int umbralDias) {
        return diasSinMovimiento >= umbralDias;
    }

    /**
     * Genera la alerta más relevante para una meta según su estado y comportamiento.
     * Solo devuelve una alerta (la de mayor prioridad).
     * 
     * @param porcentaje Porcentaje completado de la meta
     * @param diasSinMovimiento Días desde el último depósito (-1 si no hay datos)
     * @param promedioAporte Promedio de aportes recientes
     * @return AlertaMeta o null si no hay alerta relevante
     */
    public AlertaMeta generarAlerta(int porcentaje, int diasSinMovimiento, long promedioAporte) {
        // Prioridad 1: Meta estancada
        if (diasSinMovimiento >= 10 && porcentaje < 100) {
            String mensaje;
            if (diasSinMovimiento >= 30) {
                mensaje = "Llevas " + diasSinMovimiento + " días sin avanzar en esta meta";
            } else if (diasSinMovimiento >= 20) {
                mensaje = "Llevas " + diasSinMovimiento + " días sin movimientos";
            } else {
                mensaje = "Llevas " + diasSinMovimiento + " días sin avanzar";
            }
            return new AlertaMeta(TipoAlerta.ESTANCADA, mensaje, TipoAlerta.ESTANCADA.color);
        }

        // Prioridad 2: Muy cerca de completar (90-99%)
        if (porcentaje >= 90 && porcentaje < 100) {
            return new AlertaMeta(TipoAlerta.CERCANA, "¡Estás muy cerca de lograrlo! 🔥", TipoAlerta.CERCANA.color);
        }

        // Prioridad 3: Buen progreso (50-89%)
        if (porcentaje >= 50 && porcentaje < 90) {
            if (promedioAporte > 0) {
                return new AlertaMeta(TipoAlerta.BUEN_PROGRESO, "¡Ya superaste el 50% 💪", TipoAlerta.BUEN_PROGRESO.color);
            }
        }

        // Prioridad 4: Meta nueva sin movimientos (informativa)
        if (porcentaje == 0 && diasSinMovimiento == -1) {
            return new AlertaMeta(TipoAlerta.INFORMATIVA, "Comienza con tu primer aporte", TipoAlerta.INFORMATIVA.color);
        }

        // Sin alerta relevante
        return null;
    }
}
