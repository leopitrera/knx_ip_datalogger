# KNX IP Datalogger (Java)

Aplicación Java para Raspberry Pi diseñada para monitorizar y registrar telegramas KNX a través de KNXnet/IP tunneling (utilizando la librería Calimero). Los datos se guardan en formato CSV para su posterior análisis.

## 🚀 Características

- **Monitorización en tiempo real**: Captura todos los telegramas del bus KNX.
- **Registro detallado en CSV**:
  - Timestamp (fecha y hora precisa)
  - Dirección física de origen y destino
  - Dirección de Grupo (GA)
  - Valor decodificado (DPT/alias)
  - Payload ASDU en formato hexadecimal
- **Filtrado avanzado**: Soporta reglas de filtrado exactas y wildcards para direcciones de grupo (`main/middle/#`).
- **Ligero y robusto**: Optimizado para funcionar 24/7 en Raspberry Pi.

## 📋 Requisitos

- Java 11 o superior.
- Una interfaz KNX/IP o router KNX/IP que soporte tunneling.
- Librería Calimero (incluida vía Gradle).

## 🔧 Instalación y Ejecución

```bash
# Clonar el repositorio
git clone https://github.com/leopitrera/knx_ip_datalogger.git
cd knx_ip_datalogger

# Ejecutar usando el script proporcionado
./run.sh
```

O usando Gradle:

```bash
./gradlew run
```

## ⚙️ Configuración

Los parámetros de conexión (IP del router, dirección física, etc.) se configuran en el código fuente o mediante archivos de propiedades (ver `src/main/java`).

## 📊 Formato de Salida

El archivo `events.csv` tendrá una estructura similar a esta:

```csv
Date,Time,Source,Destination,Type,Value,HexData
2026-02-11,00:30:15,1.1.5,0/0/1,DPT 1.001,on,01
2026-02-11,00:30:22,1.1.10,1/2/45,DPT 9.001,22.5,070A
```

## 📝 Licencia

Este proyecto está bajo licencia MIT. Ver archivo [LICENSE](LICENSE) para más detalles.
