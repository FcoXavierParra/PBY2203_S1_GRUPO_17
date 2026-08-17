package cl.duoc.bank.batch.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.batch.core.step.skip.SkipPolicy;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.stereotype.Component;

/**
 * Politica de skip compartida por los tres Jobs.
 *
 * Decide, excepcion por excepcion, si una fila defectuosa se puede saltar o si
 * debe hacer fallar el Job completo. El criterio es:
 *
 *  - errores de PARSEO o de REGLA DE NEGOCIO -> se saltan, hasta el limite.
 *    Son datos sucios, que es justamente lo que el ejercicio pide manejar.
 *  - cualquier otra excepcion (fallo de conexion, bug, etc.) -> NO se salta.
 *    Tragarse esos errores esconderia un problema real de infraestructura.
 *
 * El limite evita el caso peligroso: si el archivo viene mal de origen y casi
 * todas las filas fallan, es preferible que el Job termine en FAILED a que
 * termine COMPLETED habiendo escrito casi nada.
 */
@Slf4j
@Component
public class BankSkipPolicy implements SkipPolicy {

    /** Maximo de filas defectuosas toleradas por Step. */
    public static final int LIMITE_SKIP = 10;

    @Override
    public boolean shouldSkip(Throwable t, long skipCount) throws SkipLimitExceededException {

        boolean esDatoSucio =
                t instanceof FlatFileParseException
                        || t instanceof RegistroInvalidoException
                        || t instanceof NumberFormatException
                        || t instanceof java.time.format.DateTimeParseException;

        if (!esDatoSucio) {
            log.error("Excepcion NO recuperable, el Step va a fallar: {}", t.toString());
            return false;
        }

        if (skipCount >= LIMITE_SKIP) {
            log.error("Se alcanzo el limite de {} filas saltadas. Se aborta el Step.", LIMITE_SKIP);
            return false;
        }

        return true;
    }
}
