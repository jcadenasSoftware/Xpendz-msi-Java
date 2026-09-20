# Xpendz Desktop

Aplicación de escritorio para Windows orientada al control y organización de finanzas personales, desarrollada con Java y JavaFX e integrada al ecosistema multiplataforma de **Xpendz**.

Xpendz Desktop permite administrar las finanzas personales desde el computador, manteniendo los datos almacenados localmente y sincronizados con Firestore para integrarse con las demás plataformas de Xpendz.

## Características

### Gestión financiera

- Gestión de cuentas y saldos.
- Soporte para múltiples monedas por cuenta.
- Registro y administración de ingresos y egresos.
- Historial de transacciones.
- Transferencias entre cuentas.
- Gestión de categorías y subcategorías.
- Presupuestos mensuales.
- Metas financieras.
- Gestión de préstamos y pagos asociados.

### Dashboard y análisis

- Dashboard financiero con información consolidada.
- Resúmenes financieros.
- Gráficos para análisis de ingresos y gastos.
- Análisis mensual por categorías.
- Totales y promedios históricos.
- Heatmap financiero.
- Indicadores y componentes de análisis financiero.

### Sincronización y datos

- Persistencia local mediante SQLite.
- Sincronización de información con Firebase Firestore.
- Autenticación de usuarios.
- Sincronización de cuentas, categorías, presupuestos, metas, préstamos y otros datos financieros.
- Arquitectura organizada mediante repositorios y servicios.

### Exportación y reportes

- Exportación de información a CSV.
- Generación de reportes PDF.
- Resúmenes financieros para análisis y consulta.

### Interfaz

- Interfaz gráfica desarrollada con JavaFX.
- Tema claro y oscuro.
- Componentes de interfaz reutilizables.
- Dashboard modular.
- Interfaz orientada a facilitar la consulta y análisis de información financiera.

## Tecnologías

- **Java 21**
- **JavaFX 21**
- **Maven**
- **SQLite**
- **Firebase Firestore**
- **Jackson**
- **Apache PDFBox**
- **Ikonli**

## Arquitectura

El proyecto mantiene una estructura modular separando diferentes responsabilidades de la aplicación.

Entre sus componentes se encuentran:

- Repositorios para acceso y persistencia de datos.
- Servicios para lógica de negocio y sincronización.
- Módulos de autenticación.
- Componentes de interfaz JavaFX.
- Servicios de generación de reportes.
- Coordinación de sincronización con Firestore.

Esta separación permite mantener y ampliar los diferentes módulos financieros sin concentrar toda la lógica en una única capa de aplicación.

## Instalación para usuarios

Xpendz Desktop se distribuye mediante un instalador **MSI para Windows**.

El instalador incluye el runtime necesario para ejecutar la aplicación, por lo que el usuario final no necesita instalar Java previamente.

### Requisitos

- Windows 10 o superior.
- No es necesario instalar Java manualmente.

### Instalación

1. Descargar el instalador MSI desde la sección **Releases**.
2. Ejecutar `Xpendz-1.0.01.msi`.
3. Seguir las instrucciones del instalador.
4. Utilizar el acceso directo creado en el escritorio o en el menú Inicio.

## Actualizaciones

El instalador utiliza el mecanismo de actualización de Windows Installer para permitir la instalación de nuevas versiones sobre versiones anteriores.

El proyecto mantiene un identificador de actualización estable para conservar el flujo de actualización entre versiones.

## Desarrollo

El proyecto utiliza Maven para la gestión y compilación.

### Requisitos de desarrollo

- JDK 21
- Apache Maven
- NetBeans u otro IDE compatible con Maven
- JavaFX 21

### Compilación

Desde el IDE:

1. Abrir el proyecto Maven.
2. Ejecutar **Clean and Build**.

También puede utilizarse Maven directamente:

```bash
mvn clean package
