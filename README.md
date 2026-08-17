# bank-batch — PBY2203 Desarrollo Backend III · Experiencia 1, Semana 1

Migración de tres procesos batch del sistema legacy del **Banco XYZ** a **Spring Batch**.

> **Estado: listo para entrega.**
> Los tres Jobs se ejecutan correctamente y persisten en
> **Oracle Autonomous Database (Oracle Cloud)**.
> La evidencia de las corridas está en [`evidencias/`](evidencias/).

## Base de datos utilizada

El proyecto persiste en **Oracle Autonomous Database 19c**, alojada en Oracle Cloud.
El enunciado permite elegir entre PostgreSQL, MySQL u Oracle.

| | |
|---|---|
| Motor | Oracle Database 19c Enterprise Edition (Autonomous Database) |
| Infraestructura | Oracle Cloud Infrastructure |
| Conexión | JDBC thin con wallet (mTLS), driver `ojdbc11` |
| Perfil de Spring | `oracle` → [`application-oracle.properties`](bank-batch/src/main/resources/application-oracle.properties) |

Tablas creadas por la aplicación: las 6 de metadata de Spring Batch (`BATCH_*`) más
las 5 de negocio (`TRANSACCION`, `RESUMEN_DIARIO`, `CUENTA`, `MOVIMIENTO_ANUAL`,
`ESTADO_CUENTA_ANUAL`).

El repositorio incluye además dos perfiles alternativos —PostgreSQL vía Docker y H2
para desarrollo local— pero **la entrega se ejecutó y evidenció contra Oracle**.

## Resultados de la ejecución

Los tres Jobs terminan en `COMPLETED`. Salida completa de consola en
[`evidencias/`](evidencias/).

| Job | Leídas | Filtradas | Saltadas | Escritas | Manejo de datos sucios |
|---|---|---|---|---|---|
| 1 · `transaccionesJob` | 10 | 2 | 0 | 8 + 7 resúmenes | filtra montos ≤ 0 (id 3 y 4); marca duplicado (id 8) y monto sobre umbral (id 9) |
| 2 · `interesesJob` | 8 | 0 | 1 | 7 | salta `hipoteca` (cuenta 105); observa saldo 0, duplicado y edad límite |
| 3 · `anualesJob` | 9 | 0 | 0 | 9 + 8 estados | marca monto cero (cuenta 107); respeta signos negativos |

## Objetivo

El banco tenía tres procesos escritos como scripts sueltos sobre archivos CSV. Este
proyecto los reescribe como Jobs de Spring Batch, cada uno con sus Steps de
lectura → procesamiento → escritura, persistiendo el resultado en una base de datos
relacional y —sobre todo— **haciéndose cargo de que los datos de origen vienen sucios**.

Los tres procesos:

| Job | Nombre del bean | Qué hace |
|---|---|---|
| 1 | `transaccionesJob` | Reporte de transacciones diarias: detecta anomalías y genera un resumen por fecha |
| 2 | `interesesJob` | Cálculo de intereses mensuales sobre cuentas de ahorro y préstamo, y actualización del saldo |
| 3 | `anualesJob` | Estados de cuenta anuales: compila el año por cuenta y genera un informe para auditoría |

## Stack

| Componente | Versión |
|---|---|
| Java | 17 |
| Spring Boot | 3.5.3 |
| Spring Batch | 5.2.x (la del starter de Boot 3.5) |
| Spring Data JPA | starter |
| **Oracle Database** | **19c Autonomous — motor usado en la entrega** |
| Driver Oracle | `ojdbc11` + `oraclepki` (wallet) |
| PostgreSQL | 16 en contenedor — alternativa documentada |
| H2 | solo para desarrollo local |
| Lombok | gestionado por el parent |

Todos los montos se manejan con `BigDecimal` y redondeo `HALF_UP` a 2 decimales.
En un sistema bancario `double` acumula error de representación, así que no se usa.

## Estructura

El proyecto Maven vive en `bank-batch/`; los CSV originales entregados por el curso
están en `bank_legacy_data-main/`.

```
.
├── README.md                              este archivo
├── bank_legacy_data-main/                 CSV originales (semanas 1, 2 y 3)
└── bank-batch/                            <- el proyecto Maven
    ├── docker-compose.yml                 PostgreSQL 16
    ├── pom.xml
    └── src/main/
        ├── java/cl/duoc/bank/batch/
        │   ├── BankBatchApplication.java
        │   ├── config/
        │   │   └── HelloWorldJobConfig.java   Job de humo, valida que Batch levanta
        │   ├── common/                        piezas compartidas por los 3 Jobs
        │   │   ├── BankSkipPolicy.java        qué excepciones se saltan y cuáles no
        │   │   ├── LoggingSkipListener.java   deja en el log cada fila saltada y el motivo
        │   │   ├── ConteoStepListener.java    imprime leídos/filtrados/escritos/saltados
        │   │   └── RegistroInvalidoException.java
        │   ├── transacciones/                 Job 1
        │   │   ├── TransaccionCsv.java        DTO de la fila cruda
        │   │   ├── Transaccion.java           entidad JPA
        │   │   ├── ResumenDiario.java         entidad JPA (salida agregada)
        │   │   ├── TransaccionProcessor.java  reglas de negocio
        │   │   ├── ResumenDiarioTasklet.java  Step 2, agregación
        │   │   ├── *Repository.java
        │   │   └── TransaccionesJobConfig.java
        │   ├── intereses/                     Job 2 (misma forma)
        │   └── anuales/                       Job 3 (misma forma)
        └── resources/
            ├── application.properties         perfil por defecto: PostgreSQL
            ├── application-h2.properties      perfil de respaldo
            └── data/                          CSV de origen (semana_1)
                ├── transacciones.csv
                ├── intereses.csv
                └── cuentas_anuales.csv
```

Cada Job vive en su propio paquete con su reader, processor, writer y config. Lo
compartido —manejo de errores y conteos— está en `common/`.

---

## Los tres Jobs en detalle

### Job 1 — `transaccionesJob`

Origen: `transacciones.csv` (`id,fecha,monto,tipo`, tipo ∈ {debito, credito}).

| Step | Tipo | Qué hace |
|---|---|---|
| `leerTransaccionesStep` | chunk (5) | CSV → validación → tabla `transaccion` |
| `resumenDiarioStep` | tasklet | agrupa por fecha → tabla `resumen_diario` |

Reglas del `TransaccionProcessor`:

| Situación en los datos | Decisión | Efecto |
|---|---|---|
| `tipo` fuera de {debito, credito} | `RegistroInvalidoException` | fila **saltada** y logueada |
| fecha o monto nulos | `RegistroInvalidoException` | fila **saltada** |
| monto ≤ 0 (id 3 = −200, id 4 = 0) | devolver `null` | fila **filtrada**, no es una transacción |
| duplicado por fecha+monto+tipo (id 6 e id 8) | marcar | se persiste con `anomalia = true` |
| monto > umbral configurable (id 9 = 3000) | marcar | se persiste con `anomalia = true` |

El duplicado **se conserva marcado** en vez de descartarse: el dato existe en el
sistema legacy y hay que poder auditarlo. Borrarlo silenciosamente sería peor que
señalarlo.

### Job 2 — `interesesJob`

Origen: `intereses.csv` (`cuenta_id,nombre,saldo,edad,tipo`, tipo ∈ {ahorro, prestamo}).

Un solo Step de chunk: CSV → cálculo → tabla `cuenta`. La escritura usa
`JpaItemWriter` con `merge`, o sea inserta la cuenta si no existe y **actualiza
`saldo_final`** si ya estaba. Cada chunk es una transacción: si falla, se revierte
completo y no queda un saldo a medio actualizar.

| Situación en los datos | Decisión |
|---|---|
| `tipo = hipoteca` (cuenta 105) | **saltada** y logueada como mal clasificada |
| duplicado aparente (cuentas 101 y 106, "John Doe") | se conserva, con `observacion` |
| saldo 0 (cuenta 104) | interés 0; no es error, es aritmética |
| edad ≥ 80 (cuenta 108) | se marca para revisión, sin alterar el cálculo |

Sobre `hipoteca`: no se reclasifica a la fuerza porque no hay forma de saber si
debería pagar la tasa de ahorro o la de préstamo. Inventar esa tasa sería peor que
omitir la fila y dejar constancia.

Cálculo: `interés = saldo × tasa`, `saldo_final = saldo + interés`. En ambos tipos el
saldo crece — en ahorro porque el banco paga, en préstamo porque la deuda devenga.

> ⚠️ **Las tasas son placeholders**: ahorro 0,5 %/mes y préstamo 1,5 %/mes.
> Hay que confirmarlas con el facilitador. Están en `application.properties`
> (`bank.intereses.tasa-ahorro`, `bank.intereses.tasa-prestamo`), no hardcodeadas.

### Job 3 — `anualesJob`

Origen: `cuentas_anuales.csv` (`cuenta_id,fecha,transaccion,monto,descripcion`,
transaccion ∈ {deposito, retiro, compra}).

| Step | Tipo | Qué hace |
|---|---|---|
| `limpiarAnualesStep` | tasklet | vacía las tablas anuales antes de recargar |
| `leerMovimientosAnualesStep` | chunk (5) | CSV → validación → tabla `movimiento_anual` |
| `estadoCuentaAnualStep` | tasklet | agrupa por cuenta+año → `estado_cuenta_anual` + informe |

Aquí el criterio es distinto a los otros dos Jobs: casi nada se descarta, porque un
estado de cuenta anual tiene que reflejar **todo** lo que pasó en la cuenta.

| Situación en los datos | Decisión |
|---|---|
| tipo de transacción desconocido | **saltada**: sin saber si suma o resta no se puede clasificar |
| monto 0 (cuenta 107, depósito navideño) | se conserva con `monto_cero = true`; no altera totales |
| montos negativos en retiro/compra | **legítimos**, se respeta el signo |
| signo incoherente con el tipo | se conserva el original y se deja `observacion` |

Los negativos no se "corrigen" a positivo: hacerlo inflaría el saldo neto. Por eso
`total_retiros` y `total_compras` quedan negativos y `saldo_neto` es literalmente la
suma de los tres totales.

El paso de limpieza existe porque `movimiento_anual` no tiene llave natural (el CSV
no trae `id`), así que el writer siempre inserta. Sin limpiar, cada re-ejecución
duplicaría los movimientos.

Además de la tabla, genera un informe de texto en `reportes/estados_cuenta_anuales.txt`
(ruta configurable en `bank.anuales.archivo-informe`).

---

## Manejo de errores

Tres mecanismos, y la diferencia entre ellos importa:

**1. Filtrado** — el processor devuelve `null`. Es una decisión de negocio normal,
no un error. Cuenta en `filterCount`. Ejemplo: una transacción de monto 0.

**2. Skip** — el processor lanza `RegistroInvalidoException`, o el reader lanza
`FlatFileParseException`. Es un dato defectuoso. Cuenta en `skipCount`.

`BankSkipPolicy` decide caso a caso:

- errores de parseo o de regla de negocio → se saltan, hasta **10 por Step**
- cualquier otra excepción (fallo de conexión, bug) → **no** se salta, el Step falla

El límite importa: si el archivo viene mal de origen y casi todas las filas fallan, es
preferible que el Job termine en `FAILED` a que termine `COMPLETED` habiendo escrito
casi nada.

`LoggingSkipListener` registra cada fila saltada con su motivo. Sin eso el skip sería
silencioso y el `COMPLETED` no significaría nada.

**3. Retry** — `.retryLimit(3).retry(TransientDataAccessException.class)`. Un deadlock
o timeout de la base se reintenta antes de darse por perdido.

`ConteoStepListener` imprime al final de cada Step el bloque de conteos que sirve de
evidencia:

```
=================================================================
 RESUMEN Step 'leerTransaccionesStep' -> COMPLETED
   leidos               : 10
   filtrados (negocio)  : 2
   escritos             : 8
   saltados (total)     : 0
     - en lectura       : 0
     - en procesamiento : 0
     - en escritura     : 0
   commits / rollbacks  : 3 / 0
=================================================================
```

---

## Requisitos previos

Ninguno viene con Windows:

1. **JDK 17 o superior** — `winget install EclipseAdoptium.Temurin.21.JDK`
2. **Maven 3.9+** — ⚠️ **no está en winget**. Descargar el binario de
   [maven.apache.org](https://maven.apache.org/download.cgi), descomprimirlo y
   agregar su carpeta `bin/` al `PATH`.
3. **Docker Desktop** — `winget install Docker.DockerDesktop`

Después de instalar hay que **abrir una terminal nueva** para que tome el `PATH`.
Verificar:

```powershell
java -version
mvn -v
docker compose version
```

## Cómo conectarse a la base de datos

### Oracle Autonomous Database — el motor de la entrega

1. En la consola de OCI, entrar a la Autonomous Database → **Database connection**
   → **Download wallet**. Pide una contraseña para el wallet; anotarla.
2. Descomprimir el ZIP en una carpeta **fuera del repositorio**, por ejemplo
   `C:\oracle\wallet`.
3. Abrir `tnsnames.ora` del wallet y copiar un nombre de servicio
   (algo como `mibase_low`).
4. Definir las variables de entorno y ejecutar:

```powershell
$env:ORACLE_JDBC_URL = "jdbc:oracle:thin:@mibase_low?TNS_ADMIN=C:/oracle/wallet"
$env:ORACLE_USER     = "ADMIN"
$env:ORACLE_PASSWORD = "..."

java -jar target/bank-batch-0.0.1-SNAPSHOT.jar --spring.profiles.active=oracle --spring.batch.job.name=transaccionesJob
```

> ⚠️ **El wallet nunca va al repositorio.** Contiene las llaves de conexión;
> publicarlo equivale a publicar la contraseña de la base. El `.gitignore` ya
> bloquea `wallet/`, `cwallet.sso`, `tnsnames.ora` y similares, y
> `application-oracle.properties` toma todo de variables de entorno
> justamente para no tener credenciales en el código.

Verificar los datos después de correr:

```sql
SELECT job_execution_id, status, exit_code FROM batch_job_execution ORDER BY 1;
SELECT * FROM resumen_diario ORDER BY fecha;
SELECT cuenta_id, tipo, saldo_inicial, interes_calculado, saldo_final FROM cuenta ORDER BY 1;
SELECT * FROM estado_cuenta_anual ORDER BY cuenta_id;
```

### Alternativa: PostgreSQL con Docker

El proyecto trae también un `docker-compose.yml` con PostgreSQL 16, por si se
prefiere correr todo local. Es el perfil por defecto (sin `--spring.profiles.active`).

```powershell
cd bank-batch
docker compose up -d
docker compose ps        # esperar hasta que aparezca (healthy)
```

| | |
|---|---|
| host | `localhost:5432` |
| base | `bankbatch` |
| usuario | `bankbatch` |
| password | `bankbatch` |

Apagar sin borrar datos: `docker compose down`.
Borrar también el volumen: `docker compose down -v`.

### Perfil `h2` (solo para desarrollo)

Existe además un perfil con H2 en archivo, útil para probar cambios rápido sin
levantar el contenedor:

```powershell
java -jar target/bank-batch-0.0.1-SNAPSHOT.jar --spring.profiles.active=h2 --spring.batch.job.name=transaccionesJob
```

**No es el motor de la entrega.** El enunciado pide elegir entre PostgreSQL, MySQL
u Oracle, y este proyecto usa PostgreSQL. H2 es una comodidad de desarrollo.

## Cómo ejecutar

Todos los comandos se corren desde la carpeta `bank-batch/`.

El proyecto tiene cuatro Jobs registrados, así que hay que **nombrar cuál correr**;
si no, Spring Boot falla por ambigüedad. Por eso `application.properties` fija
`spring.batch.job.name=helloWorldJob` como valor por defecto, y cada Job se lanza
sobreescribiendo esa propiedad.

```powershell
cd bank-batch
mvn clean package

# Job 1 — transacciones diarias
java -jar target/bank-batch-0.0.1-SNAPSHOT.jar --spring.batch.job.name=transaccionesJob

# Job 2 — intereses mensuales
java -jar target/bank-batch-0.0.1-SNAPSHOT.jar --spring.batch.job.name=interesesJob

# Job 3 — estados de cuenta anuales
java -jar target/bank-batch-0.0.1-SNAPSHOT.jar --spring.batch.job.name=anualesJob
```

O sin empaquetar, con el plugin de Maven:

```powershell
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.batch.job.name=transaccionesJob"
```

Los tres Jobs llevan `RunIdIncrementer`, así que se pueden re-ejecutar sin cambiar
parámetros a mano: cada corrida recibe un `run.id` distinto y no choca con
`JobInstanceAlreadyCompleteException`.

## Cómo verificar los resultados

Tablas de metadata de Spring Batch (las 6) y de negocio:

```powershell
docker exec -it bank-batch-postgres psql -U bankbatch -d bankbatch -c "\dt"
```

Estado de las ejecuciones:

```powershell
docker exec -it bank-batch-postgres psql -U bankbatch -d bankbatch -c "SELECT je.job_execution_id, ji.job_name, je.status, je.exit_code FROM batch_job_execution je JOIN batch_job_instance ji ON ji.job_instance_id = je.job_instance_id ORDER BY je.job_execution_id;"
```

Resultados de negocio:

```powershell
# Job 1
docker exec -it bank-batch-postgres psql -U bankbatch -d bankbatch -c "SELECT * FROM transaccion ORDER BY id;"
docker exec -it bank-batch-postgres psql -U bankbatch -d bankbatch -c "SELECT * FROM resumen_diario ORDER BY fecha;"

# Job 2
docker exec -it bank-batch-postgres psql -U bankbatch -d bankbatch -c "SELECT cuenta_id, nombre, tipo, saldo_inicial, interes_calculado, saldo_final, observacion FROM cuenta ORDER BY cuenta_id;"

# Job 3
docker exec -it bank-batch-postgres psql -U bankbatch -d bankbatch -c "SELECT * FROM estado_cuenta_anual ORDER BY cuenta_id;"
```

Y el informe de auditoría del Job 3 queda en `reportes/estados_cuenta_anuales.txt`.

## Notas de implementación

- **No se usa `@EnableBatchProcessing`.** En Spring Boot 3 esa anotación *desactiva*
  la autoconfiguración de Batch. Está omitida a propósito.
- **Encoding UTF-8 explícito en todos los readers.** `cuentas_anuales.csv` trae
  `Ingreso navideño` y `Ingreso de fin de año`; sin `.encoding("UTF-8")` esas
  descripciones se persisten corruptas. (En PowerShell 5.1 se ven mal al hacer
  `Get-Content` porque el visor asume ANSI — los archivos están bien.)
- **Mapeo de campos a mano** en vez de `BeanWrapperFieldSetMapper`: así la conversión
  de fecha y monto es explícita, y si una línea trae basura la excepción sube envuelta
  en `FlatFileParseException`, que el `SkipPolicy` reconoce como dato sucio.
- **Processors con `@StepScope`**: los de Job 1 y Job 2 guardan estado para detectar
  duplicados. Con `@StepScope` se crea una instancia nueva por ejecución y ese estado
  no se arrastra entre corridas.
