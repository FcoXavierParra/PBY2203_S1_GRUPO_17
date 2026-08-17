package cl.duoc.bank.batch.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.stereotype.Component;

/**
 * Imprime el resumen de conteos al terminar cada Step.
 *
 * Este bloque es la evidencia que se pide para la entrega: muestra de un
 * vistazo cuantas filas se leyeron, cuantas se filtraron por regla de negocio,
 * cuantas se saltaron por dato defectuoso y cuantas llegaron a la base.
 */
@Slf4j
@Component
public class ConteoStepListener implements StepExecutionListener {

    @Override
    public void beforeStep(StepExecution stepExecution) {
        log.info(">>> Inicia Step '{}' (job '{}')",
                stepExecution.getStepName(),
                stepExecution.getJobExecution().getJobInstance().getJobName());
    }

    @Override
    public ExitStatus afterStep(StepExecution se) {
        long saltados = se.getReadSkipCount() + se.getProcessSkipCount() + se.getWriteSkipCount();

        log.info("=================================================================");
        log.info(" RESUMEN Step '{}' -> {}", se.getStepName(), se.getStatus());
        log.info("   leidos               : {}", se.getReadCount());
        log.info("   filtrados (negocio)  : {}", se.getFilterCount());
        log.info("   escritos             : {}", se.getWriteCount());
        log.info("   saltados (total)     : {}", saltados);
        log.info("     - en lectura       : {}", se.getReadSkipCount());
        log.info("     - en procesamiento : {}", se.getProcessSkipCount());
        log.info("     - en escritura     : {}", se.getWriteSkipCount());
        log.info("   commits / rollbacks  : {} / {}", se.getCommitCount(), se.getRollbackCount());
        log.info("=================================================================");

        return se.getExitStatus();
    }
}
