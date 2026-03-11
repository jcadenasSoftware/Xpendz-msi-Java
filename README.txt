MIS FINANZAS - README

Descripción
Mis Finanzas es una aplicación de escritorio para el control y organización de finanzas personales. Permite administrar cuentas, registrar transacciones, visualizar resúmenes y analizar tu evolución financiera desde una interfaz moderna (JavaFX) con soporte de tema claro/oscuro.

Características principales
- Gestión de cuentas: creación/edición/eliminación y visualización de saldos.
- Registro de movimientos: ingresos y egresos con historial.
- Transferencias entre cuentas.
- Resúmenes y gráficos para análisis.
- Interfaz moderna con tema claro/oscuro.
- Instalador MSI para Windows con soporte de actualización (upgrade).

Requisitos (usuario final)
- Windows 10/11.
- No se requiere Java instalado: el instalador incluye un runtime.

Instalación (Windows)
1) Descarga el archivo MSI (ej.: dist\MisFinanzas-3.0.1.msi).
2) Ejecuta el MSI y sigue el asistente.
3) Se creará un acceso directo en el escritorio y en el menú inicio.

Actualización (upgrade)
- Para actualizar desde una versión anterior, ejecuta el MSI de la nueva versión.
- Importante: no desinstales manualmente si deseas mantener el flujo de actualización estándar.

Desinstalación
- Panel de control -> Programas -> Agregar o quitar programas -> MisFinanzas -> Desinstalar.

Ejecución
- Usa el acceso directo creado por el instalador.

Desarrollo / Compilación (para desarrolladores)
Stack
- Java 21
- JavaFX 21.x
- Maven

Compilar en NetBeans
1) Abrir el proyecto.
2) Ejecutar: Clean and Build.

Generar MSI (Windows)
Este repositorio incluye un script de PowerShell para generar el MSI usando jpackage.

1) Compila primero (recomendado): Clean and Build (NetBeans) o Maven package.
2) Desde la carpeta del proyecto:
   powershell -ExecutionPolicy Bypass -File .\build-msi.ps1 -SkipBuild

Salida:
- dist\MisFinanzas-<version>.msi

Notas del instalador (importante)
- El script build-msi.ps1 se alinea con la versión del pom.xml.
- El UUID de upgrade de Windows (UpgradeCode) se mantiene estable en el archivo:
  .win-upgrade-uuid
  Esto es esencial para que el MSI actualice correctamente versiones anteriores.

Modo diagnóstico (opcional)
Si necesitas ver errores en consola al ejecutar la app instalada:
- Generar MSI con consola:
  powershell -ExecutionPolicy Bypass -File .\build-msi.ps1 -SkipBuild -WinConsole -AppVersionOverride 3.0.1.1

Solución de problemas
- "Ya está instalada otra versión": Windows Installer no permite reinstalar la MISMA versión.
  Usa una versión superior o genera una versión de diagnóstico con -AppVersionOverride.

Notas de versión (3.0.1)
- Refactor interno del Dashboard para mejorar modularidad y mantenimiento.
- Corrección del Resumen Mensual: columna TOTAL visible y PROMEDIO calculado solo con meses anteriores.

Licencia
- Definir según tu preferencia (MIT, Apache-2.0, etc.).
