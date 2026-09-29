# AGENTS

## Verificación
- Ejecutar tests Desktop: `mvn test`
- Ejecutar tests de schema/persistencia de Fase 2A: `mvn -Dtest=SchemaConvergenceAuditTest,ObligationRepositoryTest test`

## Persistencia local
- El schema SQLite se inicializa en `src/main/java/com/myfinaces/db/AppSchema.java`.
- Los repositorios de persistencia siguen el patrón `pending_sync` para filas locales pendientes de publicación.
