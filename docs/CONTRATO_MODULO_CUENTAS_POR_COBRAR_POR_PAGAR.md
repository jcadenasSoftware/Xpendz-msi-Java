# Xpendz — Contrato Funcional
## Módulo de Cuentas por Cobrar y Cuentas por Pagar

**Versión:** 1.0  
**Estado:** Contrato aprobado para implementación  
**Plataformas:** Xpendz Desktop (Java) / Xpendz Android

---

## 1. Propósito

El módulo permitirá registrar y controlar obligaciones financieras personales que existen fuera de las cuentas financieras reales de Xpendz:

- dinero que terceros deben al usuario;
- dinero que el usuario debe a terceros;
- saldos pendientes;
- cobros y pagos parciales o totales;
- fechas de vencimiento;
- obligaciones vencidas;
- trazabilidad entre obligaciones y movimientos financieros reales.

El módulo **NO sustituye al módulo de Préstamos**.

---

## 2. Regla fundamental

> **Crear, editar o cancelar una cuenta por cobrar o por pagar NO genera ninguna transacción financiera y NO modifica el saldo de ninguna cuenta de Xpendz.**

Una obligación representa únicamente un compromiso financiero.

El dinero solamente entra o sale de una cuenta financiera cuando se registra un **cobro o pago real** asociado a esa obligación.

### Ejemplo

Se registra:

> Pedro debe $500.000.

Al crear la obligación:

- no se crea una Transaction;
- no aumenta ningún saldo;
- no disminuye ningún saldo;
- no se modifica ninguna cuenta financiera.

Posteriormente Pedro paga $200.000. En ese momento:

- se registra el cobro;
- se genera la Transaction financiera correspondiente;
- se relaciona el cobro con esa Transaction;
- el saldo pendiente pasa a $300.000.

Esta regla es obligatoria para Desktop, Android y sincronización.

---

## 3. Diferencia con Préstamos

Una cuenta por cobrar/pagar **NO es un préstamo**.

### Préstamo

Un préstamo implica una entrega o recepción de dinero.

Ejemplo:

> Joel presta $1.000.000 a Pedro.

El movimiento financiero forma parte del nacimiento del préstamo.

### Cuenta por cobrar

Representa una obligación de pago que no nace necesariamente de un préstamo.

Ejemplo:

> Pedro compra un producto por $300.000 y queda debiendo a Joel.

Crear la cuenta por cobrar no mueve dinero.

Posteriormente:

> Pedro paga $100.000.

Ese pago sí genera el movimiento financiero.

### Cuenta por pagar

Ejemplo:

> Joel adquiere un servicio por $500.000 y queda pendiente de pago.

Crear la cuenta por pagar no modifica ningún saldo.

Cuando Joel realiza el pago, se genera el movimiento financiero correspondiente.

---

## 4. Entidad principal

El módulo utilizará una entidad única denominada **Obligación**.

Tipos:

```text
POR_COBRAR
POR_PAGAR
```

No deben existir dos modelos funcionales completamente diferentes para cobrar y pagar.

Conceptualmente:

```text
Obligación
├── Información general
├── Tipo
├── Valor original
├── Total cobrado/pagado
├── Saldo pendiente
├── Fechas
├── Estado
└── Cobros/Pagos asociados
```

---

## 5. Datos de una obligación

### Identificación

- ID único.
- Tipo: `POR_COBRAR` / `POR_PAGAR`.
- Título o concepto.
- Persona o entidad relacionada.
- Descripción/notas.

### Valores

- Valor original.
- Total cobrado/pagado.
- Saldo pendiente.

El saldo pendiente es un valor derivado y **NO debe depender de una edición manual**.

### Fechas

- Fecha de creación.
- Fecha de vencimiento, opcional.
- Fecha del último cobro/pago.

### Organización

- Categoría, opcional.
- Referencia, opcional.

---

## 6. Persona o entidad relacionada

La primera versión **NO tendrá un módulo independiente de contactos**.

La obligación almacenará directamente el nombre de la persona o entidad relacionada.

Ejemplos:

- Juan Pérez.
- María González.
- Administración Edificio ABC.
- Empresa XYZ.

---

## 7. Cálculo del saldo

El saldo pendiente debe derivarse de los movimientos asociados:

```text
Saldo pendiente = Valor original - Total de pagos/cobros aplicados
```

Ejemplo:

```text
Valor original:     $500.000
Cobro 1:            $100.000
Cobro 2:            $150.000
--------------------------------
Saldo pendiente:    $250.000
```

El usuario **NO debe introducir manualmente el saldo pendiente**.

---

## 8. Estados

### PENDIENTE

La obligación existe y no se ha registrado ningún pago/cobro.

### PARCIAL

Existe al menos un pago/cobro, pero todavía existe saldo pendiente.

### PAGADA

El saldo pendiente llegó a cero.

### VENCIDA

La fecha de vencimiento pasó y todavía existe saldo pendiente.

### CANCELADA

La obligación dejó de ser exigible sin haber sido necesariamente pagada.

---

## 9. Regla especial para VENCIDA

`VENCIDA` debe tratarse como una condición temporal derivada.

Una obligación pendiente o parcial se considera vencida cuando:

```text
fecha actual > fecha de vencimiento
AND
saldo pendiente > 0
```

Por tanto:

```text
PENDIENTE + vencida → mostrar como VENCIDA
PARCIAL + vencida   → mostrar como VENCIDA
```

El paso del tiempo **NO genera ningún movimiento financiero**.

---

## 10. Cobros y pagos

Una obligación puede tener cero, uno o múltiples movimientos asociados.

- Una `POR_COBRAR` tiene cobros.
- Una `POR_PAGAR` tiene pagos.

Cada pago/cobro debe conservar como mínimo:

- ID.
- ID de la obligación.
- Fecha.
- Valor.
- Cuenta financiera utilizada.
- ID de la Transaction asociada.
- Nota/referencia, si corresponde.

---

## 11. Materialización financiera

Esta es una regla crítica.

### Crear obligación

```text
Crear obligación
      ↓
NO Transaction
      ↓
NO cambio de saldo
```

### Registrar cobro

```text
Cuenta por cobrar
      ↓
Registrar cobro
      ↓
Crear Transaction
      ↓
Aumentar cuenta financiera
      ↓
Relacionar Transaction ↔ Cobro
      ↓
Reducir saldo pendiente
```

### Registrar pago

```text
Cuenta por pagar
      ↓
Registrar pago
      ↓
Crear Transaction
      ↓
Disminuir cuenta financiera
      ↓
Relacionar Transaction ↔ Pago
      ↓
Reducir saldo pendiente
```

La creación de la obligación nunca debe utilizar mecanismos de materialización de dinero.

---

## 12. Relación entre pago/cobro y Transaction

Cada pago/cobro que materialice dinero debe tener una relación trazable con su Transaction financiera.

```text
Obligación
    │
    ├── Pago/Cobro
    │       └── Transaction
    │
    ├── Pago/Cobro
    │       └── Transaction
    │
    └── ...
```

La relación debe permitir:

- identificar qué Transaction corresponde a cada pago/cobro;
- modificar correctamente el pago/cobro;
- eliminar correctamente el pago/cobro;
- reconstruir el saldo;
- mantener sincronización;
- evitar duplicación de movimientos.

**El sistema no debe fabricar Transactions para reconciliar artificialmente una obligación.**

---

## 13. Modificación de un pago/cobro

Si existe:

```text
Cobro: $200.000
```

y se corrige a:

```text
Cobro: $150.000
```

el sistema debe actualizar coherentemente:

- el pago/cobro;
- la Transaction relacionada;
- el saldo de la cuenta financiera;
- el saldo pendiente de la obligación;
- la información sincronizada.

NO debe crear una segunda Transaction de $150.000 dejando activa la Transaction original.

---

## 14. Eliminación de un pago/cobro

Si:

```text
Valor original: $500.000
Cobro:          $200.000
Saldo:          $300.000
```

y se elimina el cobro:

- debe eliminarse o revertirse correctamente la Transaction asociada, según las reglas existentes de Xpendz;
- debe desaparecer el pago/cobro;
- el saldo debe volver a $500.000;
- el estado debe recalcularse;
- no debe quedar Transaction huérfana;
- no debe quedar pago/cobro huérfano.

---

## 15. Sobrepagos

La primera versión **NO permitirá pagos/cobros superiores al saldo pendiente**.

No se implementarán en v1:

- saldos a favor;
- anticipos;
- créditos acumulados;
- pagos superiores al saldo;
- compensaciones automáticas.

---

## 16. Cancelación

Cancelar una obligación **NO equivale a pagarla**.

Ejemplo:

```text
Valor original: $500.000
Pagado:         $0
Saldo:          $500.000
Estado:         CANCELADA
```

Cancelar:

- NO genera Transaction.
- NO modifica cuentas financieras.
- NO convierte el saldo en cero mediante una transacción.
- Conserva la trazabilidad histórica.

`PAGADA` y `CANCELADA` deben mantenerse como estados diferentes.

---

## 17. Vencimientos

Una obligación puede:

- no tener fecha de vencimiento;
- tener fecha de vencimiento.

Comportamiento:

```text
Hoy < vencimiento
→ Vigente

Hoy = vencimiento
→ Vence hoy

Hoy > vencimiento && saldo > 0
→ Vencida
```

El vencimiento no produce ningún movimiento financiero.

---

## 18. Categorías

Una obligación puede tener una categoría opcional.

Ejemplos por pagar:

- Arriendo.
- Servicios.
- Educación.
- Salud.
- Compras.

Ejemplos por cobrar:

- Trabajo.
- Venta.
- Servicio.
- Reembolso.

La categoría de la obligación **NO sustituye la categoría de una Transaction**.

Se mantiene la separación entre:

> Categoría de la obligación

y

> Categoría de la transacción financiera real.

---

## 19. Dashboard

El módulo debe proporcionar una vista resumen.

### Por cobrar

- Total pendiente por cobrar.
- Cantidad de obligaciones activas.

### Por pagar

- Total pendiente por pagar.
- Cantidad de obligaciones activas.

### Balance

```text
Total por cobrar - Total por pagar
```

También debe permitir identificar:

- obligaciones vencidas;
- obligaciones próximas a vencer;
- obligaciones pendientes;
- obligaciones parciales.

La presentación debe mantener el lenguaje visual de Xpendz.

---

## 20. Integración con Xpendz

El módulo debe integrarse correctamente con:

- Cuentas.
- Transacciones.
- Categorías.
- Préstamos.
- Presupuestos.
- Metas.
- Dashboard.
- Gráficos.
- Reportes.
- Backup/Restore.
- Sincronización Firebase.

Una obligación NO modifica un saldo financiero simplemente por existir.

El saldo financiero cambia únicamente cuando existe un movimiento financiero real.

---

## 21. Android y Desktop

El comportamiento funcional debe ser equivalente.

### Desktop Java

Debe reutilizar:

- diálogos existentes;
- componentes existentes;
- estilos existentes;
- patrones de navegación;
- validaciones existentes;
- patrones visuales de Xpendz.

### Android

Debe implementar equivalentes utilizando:

- Jetpack Compose;
- Material 3;
- componentes y patrones existentes de Xpendz;
- mismos conceptos;
- mismas validaciones;
- mismos estados;
- mismo comportamiento funcional.

Las interfaces no tienen que ser idénticas en píxeles. Deben ser coherentes con cada plataforma y transmitir el mismo modelo mental.

---

## 22. Sincronización

La información debe sincronizarse correctamente:

```text
Desktop
   ↕
Firebase
   ↕
Android
```

El modelo conceptual preferido es:

```text
users/{uid}/obligations
```

con:

```text
type = POR_COBRAR
type = POR_PAGAR
```

La estructura definitiva debe adaptarse a la arquitectura existente después de inspeccionar:

- Loans.
- Transactions.
- Accounts.
- Goals.
- Pending Sync.
- Firestore.

No se debe crear una arquitectura paralela innecesaria.

---

## 23. Consistencia y sincronización

La sincronización debe evitar:

- duplicar obligaciones;
- duplicar pagos/cobros;
- duplicar Transactions;
- crear Transactions fantasma;
- perder relaciones entre obligaciones y Transactions;
- alterar saldos por operaciones de sincronización;
- fabricar dinero para resolver inconsistencias.

La sincronización debe transportar el estado real existente.

**Nunca debe utilizarse la sincronización como mecanismo para inventar movimientos financieros.**

---

## 24. Reglas de negocio obligatorias

| ID | Regla |
|---|---|
| R01 | Crear una obligación NO crea Transaction. |
| R02 | Editar una obligación NO crea Transaction. |
| R03 | Cancelar una obligación NO crea Transaction. |
| R04 | El saldo pendiente se deriva de los pagos/cobros. |
| R05 | Un pago/cobro real sí genera una Transaction. |
| R06 | Cada pago/cobro materializado debe poder identificarse con su Transaction. |
| R07 | Modificar un pago/cobro modifica coherentemente su Transaction. |
| R08 | Eliminar un pago/cobro resuelve correctamente su Transaction. |
| R09 | No se permiten sobrepagos en v1. |
| R10 | `PAGADA` y `CANCELADA` son estados diferentes. |
| R11 | `VENCIDA` depende de fecha y saldo; no constituye movimiento financiero. |
| R12 | Las categorías de obligación y Transaction son independientes. |
| R13 | Desktop y Android implementan las mismas reglas de negocio. |
| R14 | Firebase no debe fabricar movimientos financieros. |
| R15 | No se deben crear mecanismos de materialización desde eventos huérfanos. |
| R16 | Una obligación no debe convertirse implícitamente en un préstamo. |

---

## 25. Alcance excluido de v1

Queda fuera de esta versión:

- intereses;
- tasas de interés;
- recargos automáticos;
- cuotas automáticas;
- amortización;
- generación automática de obligaciones;
- crédito rotativo;
- sistema independiente de contactos;
- facturación;
- contabilidad empresarial;
- contabilidad de partida doble;
- saldos a favor;
- sobrepagos;
- cobros/pagos recurrentes automáticos;
- funciones avanzadas de financiación.

El objetivo es controlar **obligaciones personales y sus pagos reales**, no construir un sistema contable o de crédito.

---

## 26. Criterios de aceptación

El módulo no se considerará terminado hasta demostrar mediante pruebas que:

1. Crear una cuenta por cobrar no cambia ningún saldo.
2. Crear una cuenta por pagar no cambia ningún saldo.
3. Editar una obligación no crea movimientos.
4. Cancelar una obligación no crea movimientos.
5. Un cobro parcial actualiza correctamente saldo y Transaction.
6. Un pago parcial actualiza correctamente saldo y Transaction.
7. Un cobro total deja saldo cero y estado `PAGADA`.
8. Un pago total deja saldo cero y estado `PAGADA`.
9. Una obligación vencida se identifica correctamente sin crear movimientos.
10. No se permiten sobrepagos.
11. Modificar un pago modifica correctamente la Transaction asociada.
12. Eliminar un pago resuelve correctamente la Transaction asociada.
13. No quedan Transactions huérfanas.
14. No quedan pagos/cobros huérfanos.
15. Desktop y Android muestran el mismo estado funcional.
16. Desktop → Firebase → Android conserva correctamente las obligaciones.
17. Android → Firebase → Desktop conserva correctamente las obligaciones.
18. No aparecen Transactions duplicadas después de sincronizaciones repetidas.
19. No aparecen obligaciones duplicadas después de sincronizaciones repetidas.
20. Backup/restore conserva obligaciones, pagos/cobros y relaciones.
21. Los reportes interpretan correctamente las obligaciones y sus movimientos reales.
22. La implementación no modifica ni rompe el comportamiento existente de Préstamos.

---

## 27. Regla para decisiones no contempladas

Este documento constituye el **contrato funcional del módulo**.

Si durante el desarrollo aparece una situación no contemplada:

1. No se debe improvisar una regla financiera.
2. No se debe crear una Transaction para solucionar el problema.
3. No se debe asumir que una cuenta por cobrar/pagar funciona como un préstamo.
4. Debe identificarse el conflicto.
5. Debe proponerse una regla explícita.
6. La regla debe aprobarse antes de convertirse en comportamiento permanente.

Prioridad:

```text
Consistencia del dominio
        ↓
Integridad financiera
        ↓
Sincronización correcta
        ↓
Tests
        ↓
UI
```

---

## 28. Principio rector

> **Xpendz registra obligaciones sin mover dinero y registra movimientos financieros únicamente cuando el dinero realmente se mueve.**

Esta distinción debe mantenerse en:

- Modelo de datos.
- Servicios.
- Repositorios.
- Transacciones.
- Persistencia.
- Firebase.
- Sincronización.
- Desktop.
- Android.
- Tests.
- Reportes.
- Backup/Restore.

**Una obligación no es dinero.  
Un pago/cobro es el evento que materializa el movimiento financiero.**
