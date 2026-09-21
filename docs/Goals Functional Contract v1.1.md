# CONTRATO FUNCIONAL DEL MÓDULO METAS — v1.1 (Propuesta Final)

**Estado:** Propuesto para aprobación.

Este documento define el comportamiento oficial del módulo Metas para todas las plataformas de Xpendz.
Una vez aprobado, Desktop, Android y futuras plataformas deberán implementar exactamente esta semántica.

---

## 0. Principios normativos

- Una meta representa un objetivo financiero del usuario.
- Cada meta posee una relación 1:1 con una cuenta financiera de tipo SAVINGS.
- El dinero de la meta es siempre el saldo real de esa cuenta.
- El progreso de una meta es un dato derivado: `progreso = saldo / objetivo`. Nunca debe persistirse.
- El estado persistido de una meta será únicamente: `OPEN`, `CLOSED`. No se introducirán estados adicionales.
- `DELETED` no es un estado. Eliminar significa la desaparición física del registro.

## 1. Principio de Arquitectura

Las reglas de negocio pertenecen al dominio.
La sincronización únicamente transporta estados y resuelve conflictos de datos.
La sincronización no debe tomar decisiones funcionales como:

- archivar metas;
- considerar metas cumplidas;
- modificar reglas de negocio.

Las decisiones funcionales pertenecen exclusivamente al dominio de Metas.

## 2. Ciclo de vida

```
Crear Meta
      │
      ▼
+-------------+
|    OPEN     |
+-------------+
      │
      ├───────────────► Depositar
      │
      ├───────────────► Retirar
      │
      ├───────────────► Editar
      │
      └───────────────► Cumplir objetivo
                             │
                             ▼
                       (estado derivado)
                        Meta alcanzada
                             │
                             ▼
                      Sigue siendo OPEN
                             │
                  Usuario decide archivar
                             │
                             ▼
                    +-------------+
                    |   CLOSED    |
                    +-------------+
                             │
                             ▼
                          Reabrir
                             │
                             ▼
                           OPEN
```

## 3. Estados

### OPEN
Representa una meta activa.
Permite:
- depósitos;
- retiros;
- edición;
- sincronización;
- visibilidad en Dashboard.

### CLOSED
Representa una meta archivada.
Nunca significa:
- meta cumplida;
- meta cancelada;
- meta eliminada.

Una meta CLOSED:
- conserva todo su historial;
- conserva su cuenta;
- conserva todas sus transferencias;
- no acepta nuevas operaciones hasta ser reabierta.

## 4. Meta cumplida

Una meta se considera cumplida cuando: `saldo >= objetivo`.

Este es un estado completamente derivado.
No modifica:
- status;
- Firestore;
- SQLite;
- sincronización.

Únicamente habilita elementos visuales.

## 5. Felicitación

Cuando el usuario alcance por primera vez el objetivo, mostrar:

🎉 ¡Felicitaciones! Has alcanzado tu meta.

Esta felicitación debe mostrarse una sola vez por cada cumplimiento.
No debe aparecer cada vez que se abra la aplicación.
Después de mostrarse, la meta únicamente conservará un indicador visual de "Meta alcanzada".

## 6. Depósitos

Permitidos siempre que la meta esté OPEN.
Se permite:
- depósito parcial;
- depósito total;
- superar el objetivo.

Superar el objetivo no modifica el estado.

## 7. Retiros

Permitidos siempre que la meta esté OPEN.
Se permite:
- retiro parcial;
- retiro total.

Si el retiro reduce el progreso por debajo del objetivo: la meta continúa siendo OPEN. Simplemente deja de estar cumplida.

## 8. Eliminación

Regla única.

| Condición | Resultado |
|---|---|
| saldo > 0 | No permitido |
| saldo = 0 y sin historial | Eliminación física |
| saldo = 0 y con historial | Archivar (CLOSED) |

Esto preserva la integridad financiera.

## 9. Archivo

Archivar significa: `OPEN → CLOSED`.
La decisión siempre proviene del usuario. Nunca del sincronizador.

## 10. Reapertura

Permitida: `CLOSED → OPEN`.
No requiere cambios de esquema.
Debe conservar:
- historial;
- transferencias;
- depósitos;
- saldo;
- cuenta asociada.

## 11. Historial financiero

El historial financiero de una meta es inmutable.
Cambiar `OPEN → CLOSED → OPEN` nunca modifica:
- depósitos;
- retiros;
- movimientos;
- transferencias;
- reportes;
- balances históricos.

El estado únicamente afecta la operatividad y la visibilidad.

## 12. Visibilidad

| Vista | Muestra |
|---|---|
| Dashboard | únicamente `OPEN` |
| Lista principal | `OPEN` |
| Archivadas | sección independiente que muestra `CLOSED` con opción "Reabrir" |
| Reportes | las metas `CLOSED` no aparecen como metas activas; su historial financiero permanece disponible |

## 13. Sincronización

La sincronización conserva exactamente el modelo actual.

Documento Firestore:
`id`, `userUid`, `name`, `currency`, `targetCents`, `targetDateEpochSec`, `accountId`, `status`, `createdAtEpochSec`, `updatedAtEpochSec`, `updatedBy`. Sin cambios.

Reglas:
- **Push:** inmediato.
- **Pull:** aplica únicamente cambios más recientes.
- **Empates:** conservar local.
- **Estados desconocidos:** registrar en log; tratar como `OPEN` para mantener compatibilidad.

### Restricción importante

El sincronizador nunca deberá:
- archivar automáticamente una meta;
- interpretar una meta como cumplida;
- modificar reglas funcionales.

Si detecta inconsistencias deberá:
- registrarlas;
- dejarlas disponibles para resolución por el dominio.

## 14. Compatibilidad

No requiere:
- nuevas tablas;
- nuevos campos;
- cambios de esquema SQLite;
- cambios del modelo Firestore.

Desktop y Android deberán converger hacia este contrato utilizando la infraestructura existente.

El dominio canónico (`myfinances.domain.goals.*`) permanece fuera del alcance de esta estabilización y no será integrado mientras no exista una migración explícitamente planificada.

## 15. Principios de evolución

Este contrato adopta las siguientes reglas permanentes para el módulo Metas:

- Una única semántica para todos los estados.
- Una única fuente de verdad para las reglas de negocio.
- La sincronización no implementa lógica funcional.
- El historial financiero nunca se altera por cambios de estado.
- Toda nueva plataforma (Desktop, Android, Web u otra) deberá implementar este contrato sin reinterpretar su significado.

---

*Aprobado como especificación oficial — Xpendz Goals Module.*
