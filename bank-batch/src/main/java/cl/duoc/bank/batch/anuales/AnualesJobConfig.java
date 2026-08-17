package cl.duoc.bank.batch.anuales;

import cl.duoc.bank.batch.common.BankSkipPolicy;
import cl.duoc.bank.batch.common.ConteoStepListener;
import cl.duoc.bank.batch.common.LoggingSkipListener;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.database.JpaItemWriter;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Job 3 - Generacion de estados de cuenta anuales.
 *
 * Tres Steps encadenados:
 *   1. limpiarAnualesStep   : vacia las tablas anuales (tasklet)
 *   2. leerMovimientosStep  : CSV -> validacion -> movimiento_anual (chunk)
 *   3. estadoCuentaAnualStep: agrupa por cuenta+ano -> estado_cuenta_anual
 *                             + informe de texto (tasklet)
 */
@Configuration
public class AnualesJobConfig {

    private static final String ARCHIVO = "data/cuentas_anuales.csv";
    private static final int TAMANO_CHUNK = 5;

    // ---------------------------------------------------------------- READER

    /**
     * encoding UTF-8 es obligatorio aqui: el archivo trae "Ingreso navideno"
     * con enie y "Ingreso de fin de ano" con tilde. Con el encoding por
     * defecto de la JVM esas descripciones se persisten corruptas.
     */
    @Bean
    public FlatFileItemReader<MovimientoAnualCsv> movimientoAnualReader() {
        return new FlatFileItemReaderBuilder<MovimientoAnualCsv>()
                .name("movimientoAnualReader")
                .resource(new ClassPathResource(ARCHIVO))
                .encoding("UTF-8")
                .linesToSkip(1)   // cabecera cuenta_id,fecha,transaccion,monto,descripcion
                .delimited()
                .delimiter(",")
                .names("cuenta_id", "fecha", "transaccion", "monto", "descripcion")
                .fieldSetMapper(fs -> new MovimientoAnualCsv(
                        fs.readLong("cuenta_id"),
                        LocalDate.parse(fs.readString("fecha").trim()),
                        fs.readString("transaccion"),
                        new BigDecimal(fs.readString("monto").trim()),
                        fs.readString("descripcion")))
                .build();
    }

    // ------------------------------------------------------------- PROCESSOR

    @Bean
    @StepScope
    public MovimientoAnualProcessor movimientoAnualProcessor() {
        return new MovimientoAnualProcessor();
    }

    // ---------------------------------------------------------------- WRITER

    @Bean
    public JpaItemWriter<MovimientoAnual> movimientoAnualWriter(EntityManagerFactory emf) {
        JpaItemWriter<MovimientoAnual> writer = new JpaItemWriter<>();
        writer.setEntityManagerFactory(emf);
        // persist en vez de merge: las filas son nuevas y no tienen id todavia,
        // asi se evita el SELECT previo que haria merge por cada movimiento.
        writer.setUsePersist(true);
        return writer;
    }

    // ----------------------------------------------------------------- STEPS

    @Bean
    public Step limpiarAnualesStep(JobRepository jobRepository,
                                   PlatformTransactionManager transactionManager,
                                   LimpiarAnualesTasklet limpiarAnualesTasklet,
                                   ConteoStepListener conteoListener) {

        return new StepBuilder("limpiarAnualesStep", jobRepository)
                .tasklet((Tasklet) limpiarAnualesTasklet, transactionManager)
                .listener(conteoListener)
                .build();
    }

    @Bean
    public Step leerMovimientosAnualesStep(JobRepository jobRepository,
                                           PlatformTransactionManager transactionManager,
                                           FlatFileItemReader<MovimientoAnualCsv> movimientoAnualReader,
                                           MovimientoAnualProcessor movimientoAnualProcessor,
                                           JpaItemWriter<MovimientoAnual> movimientoAnualWriter,
                                           BankSkipPolicy skipPolicy,
                                           LoggingSkipListener skipListener,
                                           ConteoStepListener conteoListener) {

        return new StepBuilder("leerMovimientosAnualesStep", jobRepository)
                .<MovimientoAnualCsv, MovimientoAnual>chunk(TAMANO_CHUNK, transactionManager)
                .reader(movimientoAnualReader)
                .processor(movimientoAnualProcessor)
                .writer(movimientoAnualWriter)
                .faultTolerant()
                .skipPolicy(skipPolicy)
                .retryLimit(3)
                .retry(TransientDataAccessException.class)
                .listener(skipListener)
                .listener(conteoListener)
                .build();
    }

    @Bean
    public Step estadoCuentaAnualStep(JobRepository jobRepository,
                                      PlatformTransactionManager transactionManager,
                                      EstadoCuentaAnualTasklet estadoCuentaAnualTasklet,
                                      ConteoStepListener conteoListener) {

        return new StepBuilder("estadoCuentaAnualStep", jobRepository)
                .tasklet((Tasklet) estadoCuentaAnualTasklet, transactionManager)
                .listener(conteoListener)
                .build();
    }

    // ------------------------------------------------------------------- JOB

    @Bean
    public Job anualesJob(JobRepository jobRepository,
                          Step limpiarAnualesStep,
                          Step leerMovimientosAnualesStep,
                          Step estadoCuentaAnualStep) {

        return new JobBuilder("anualesJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(limpiarAnualesStep)
                .next(leerMovimientosAnualesStep)
                .next(estadoCuentaAnualStep)
                .build();
    }
}
