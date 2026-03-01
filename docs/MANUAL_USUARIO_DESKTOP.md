# Manual de Usuario — Mis Finanzas (Desktop / Windows)

## 1. Introducción
Mis Finanzas (Desktop) es una aplicación para el control de finanzas personales. Permite administrar cuentas, registrar transacciones, realizar transferencias, consultar resúmenes y gráficos.

## 2. Requisitos
- Windows 10/11.
- Instalador MSI (incluye runtime, no necesitas Java instalado).
- Conexión a internet para sincronización.

## 3. Instalación
1) Ejecuta el archivo `MisFinanzas-<version>.msi`.
2) Sigue el asistente de instalación.
3) Se creará acceso directo en el escritorio y menú inicio.

## 4. Inicio de sesión
- Abre la aplicación.
- Inicia sesión con tu cuenta.

## 5. Panel principal (Dashboard)
El Dashboard muestra:
- **Saldo total**.
- **Cuentas** con sus saldos.
- Accesos a módulos:
  - Transacciones
  - Transferencias
  - Categorías
  - Resumen
  - Gráficos

## 6. Cuentas
- Crear cuenta.
- Editar nombre.
- Eliminar (si está permitido según saldo/movimientos).

## 7. Transacciones
- Registrar ingresos y egresos.
- Editar transacciones.
- Consultar historial.

Recomendación:
- Verifica cuenta, categoría y monto.

## 8. Transferencias
- Registrar transferencias entre cuentas.
- Consultar historial.

Recomendación:
- Asegúrate de seleccionar cuenta origen y destino correctas.

## 9. Categorías
- Crear y organizar categorías.
- Clasificar transacciones para reportes y gráficos.

## 10. Resumen y Gráficos
- Consulta métricas y distribución por categorías.

## 11. Sincronización
La app sincroniza con la nube para mantener la información actualizada.

Buenas prácticas (plan gratuito con cuota):
- Evita refrescar repetidamente.
- Si notas que faltan datos, espera y vuelve a intentar más tarde.

## 12. Datos locales y respaldo
La app guarda una base local SQLite en:
- `%USERPROFILE%\.myfinances\myfinances.db`

Sugerencias:
- Para respaldo: copia el archivo `.db` a otra carpeta.
- Para resync limpio (solo si es necesario): cierra la app y renombra el `.db` para que se regenere con sincronización.

