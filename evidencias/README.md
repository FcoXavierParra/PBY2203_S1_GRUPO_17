# Evidencia de ejecución

PBY2203 Desarrollo Backend III · Experiencia 1, Semana 1

Salidas de consola de la ejecución de **cada uno de los tres Jobs** y de los
resultados generados, según pide la entrega.

**Motor:** Oracle Autonomous Database 19c (Oracle Cloud).
**Los tres Jobs terminan en `COMPLETED`.**

| Archivo | Contenido |
|---|---|
| `01_Job1_transacciones_diarias.txt` | Consola completa del Job 1 |
| `02_Job2_intereses_mensuales.txt` | Consola completa del Job 2 |
| `03_Job3_estados_cuenta_anuales.txt` | Consola completa del Job 3 |
| `04_Datos_persistidos_en_Oracle.txt` | Consulta a Oracle: tablas creadas, ejecuciones y datos de los tres Jobs |
| `05_Informe_generado_Job3.txt` | Informe de auditoría que produce el Job 3 |

---

## Job 1 — Reporte de transacciones diarias

**10 leídas · 2 filtradas · 0 saltadas · 8 escritas + 7 resúmenes diarios**

Datos sucios manejados:

```
[FILTRADA] transaccion id=3 descartada por monto no positivo (-200)
[FILTRADA] transaccion id=4 descartada por monto no positivo (0)
[ANOMALIA] transaccion id=8 -> posible duplicado de (2024-01-05|700|debito)
[ANOMALIA] transaccion id=9 -> monto 3000 supera el umbral 2500
```

Conteos y cierre:

```
 RESUMEN Step 'leerTransaccionesStep' -> COMPLETED
   leidos               : 10
   filtrados (negocio)  : 2
   escritos             : 8
   saltados (total)     : 0

Job: [SimpleJob: [name=transaccionesJob]] ... status: [COMPLETED]
```

Salida generada — tabla `RESUMEN_DIARIO`:

```
2024-01-05 | debitos=1400 | creditos=0 | transacciones=2 | anomalias=1
2024-01-07 | debitos=3000 | creditos=0 | transacciones=1 | anomalias=1
```

---

## Job 2 — Cálculo de intereses mensuales

**8 leídas · 1 saltada · 7 escritas**

Dato mal clasificado, saltado por el `SkipPolicy` y registrado con su motivo:

```
[SKIP-PROCESO] registro=CuentaCsv(cuentaId=105, nombre=Charlie Green, saldo=7000,
edad=35, tipo=hipoteca) | motivo=RegistroInvalidoException: tipo mal clasificado
'hipoteca' en la cuenta 105 (esperado: ahorro o prestamo). No se calcula interes.
```

Otras observaciones registradas:

```
[OBSERVACION] cuenta 104 -> saldo inicial en cero, interes 0
[OBSERVACION] cuenta 106 -> posible duplicado de la cuenta 101 (John Doe|5000.00|30|ahorro)
[OBSERVACION] cuenta 108 -> edad 80 alcanza el limite de revision (80)
```

Cálculo y actualización del saldo:

```
cuenta 101 (ahorro)   saldo  5000.00 x tasa 0.005 = interes  25.00 -> saldo final  5025.00
cuenta 102 (prestamo) saldo  8000.00 x tasa 0.015 = interes 120.00 -> saldo final  8120.00
cuenta 103 (prestamo) saldo 12000.00 x tasa 0.015 = interes 180.00 -> saldo final 12180.00
cuenta 108 (ahorro)   saldo 10000.00 x tasa 0.005 = interes  50.00 -> saldo final 10050.00
```

Nota sobre `commits / rollbacks : 2 / 1`: el rollback es esperado. Al saltar la
cuenta 105, Spring Batch revierte el chunk y lo reprocesa fila por fila para
aislar la defectuosa. Es el mecanismo de tolerancia a fallos funcionando.

---

## Job 3 — Estados de cuenta anuales

**9 leídas · 0 saltadas · 9 escritas + 8 estados de cuenta**

```
[OBSERVACION] cuenta 107 2024-12-25 deposito -> movimiento de monto cero, sin efecto en el saldo
```

Salida generada — tabla `ESTADO_CUENTA_ANUAL`, respetando los signos negativos:

```
cuenta 101 (2024) | depositos=1000 | retiros=-500 | compras=0    | neto=500  | movs=2 | en cero=0
cuenta 104 (2024) | depositos=0    | retiros=0    | compras=-100 | neto=-100 | movs=1 | en cero=0
cuenta 107 (2024) | depositos=0    | retiros=0    | compras=0    | neto=0    | movs=1 | en cero=1
```

Además genera el informe de auditoría en archivo (`05_Informe_generado_Job3.txt`).

---

## Persistencia verificada en Oracle

Consulta directa a la base después de las tres ejecuciones
(`04_Datos_persistidos_en_Oracle.txt`):

```
Conectado a: Oracle Database 19c Enterprise Edition Release 19.0.0.0.0

===== Tablas creadas =====
BATCH_JOB_EXECUTION            BATCH_JOB_EXECUTION_CONTEXT
BATCH_JOB_EXECUTION_PARAMS     BATCH_JOB_INSTANCE
BATCH_STEP_EXECUTION           BATCH_STEP_EXECUTION_CONTEXT
CUENTA                         ESTADO_CUENTA_ANUAL
MOVIMIENTO_ANUAL               RESUMEN_DIARIO
TRANSACCION

===== Ejecuciones de Jobs =====
transaccionesJob      COMPLETED     COMPLETED
interesesJob          COMPLETED     COMPLETED
anualesJob            COMPLETED     COMPLETED
```

Las 6 tablas de metadata de Spring Batch y las 5 de negocio quedaron creadas, y
las tres ejecuciones registradas en `BATCH_JOB_EXECUTION` con estado `COMPLETED`.

En `CUENTA` hay 7 filas: la cuenta 105 no aparece, porque fue correctamente
saltada por venir mal clasificada.

## Cobertura de la pauta

| Criterio | Dónde se evidencia |
|---|---|
| Organiza la estructura del proyecto | un paquete por Job + `common/` compartido |
| Configura los componentes de Spring Batch | Jobs, Steps, Readers, Processors, Writers, Tasklets, Listeners |
| Integra y configura los componentes necesarios | archivos 01 a 03: los Jobs levantan y encadenan sus Steps |
| Ejecuta los Jobs y Steps | archivos 01 a 03: los tres en `COMPLETED` |
| Transforma y maneja los errores en los datos | `[FILTRADA]`, `[SKIP-PROCESO]`, `[ANOMALIA]`, `[OBSERVACION]` |
| Genera la salida y persiste los datos | archivos 04 y 05 |
