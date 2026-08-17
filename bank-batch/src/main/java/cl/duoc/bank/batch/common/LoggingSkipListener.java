package cl.duoc.bank.batch.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.stereotype.Component;

/**
 * Deja constancia en el log de CADA fila saltada y del motivo.
 *
 * Sin esto, el skip seria silencioso: el Job diria COMPLETED y nadie sabria
 * que se descartaron filas ni cuales. Para una migracion bancaria eso no sirve
 * como evidencia.
 */
@Slf4j
@Component
public class LoggingSkipListener implements SkipListener<Object, Object> {

    @Override
    public void onSkipInRead(Throwable t) {
        if (t instanceof FlatFileParseException ffpe) {
            log.warn("[SKIP-LECTURA] linea {} no se pudo parsear | contenido='{}' | causa={}",
                    ffpe.getLineNumber(), ffpe.getInput(), causaRaiz(t));
        } else {
            log.warn("[SKIP-LECTURA] causa={}", causaRaiz(t));
        }
    }

    @Override
    public void onSkipInProcess(Object item, Throwable t) {
        log.warn("[SKIP-PROCESO] registro={} | motivo={}", item, causaRaiz(t));
    }

    @Override
    public void onSkipInWrite(Object item, Throwable t) {
        log.warn("[SKIP-ESCRITURA] registro={} | motivo={}", item, causaRaiz(t));
    }

    private String causaRaiz(Throwable t) {
        Throwable actual = t;
        while (actual.getCause() != null && actual.getCause() != actual) {
            actual = actual.getCause();
        }
        return actual.getClass().getSimpleName() + ": " + actual.getMessage();
    }
}
