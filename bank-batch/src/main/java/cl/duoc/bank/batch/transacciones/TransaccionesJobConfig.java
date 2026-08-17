package cl.duoc.bank.batch.transacciones;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Job 1 - Reporte de transacciones diarias.
 *
 * Dos Steps encadenados:
 *   1. leerTransaccionesStep : CSV -> validacion -> tabla transaccion  (chunk)
 *   2. resumenDiarioStep     : tabla transaccion -> tabla resumen_diario (tasklet)
 *
 * Si el primero falla, el segundo no corre: agregar sobre datos incompletos
 * daria un resumen mentiroso.
 */
@Configuration
public class TransaccionesJobConfig {

    private static final String ARCHIVO = "data/transacciones.csv";
    private static final int TAMANO_CHUNK = 5;

    // ---------------------------------------------------------------- READER

    /**
     * El mapeo se hace a mano en vez de con BeanWrapperFieldSetMapper para que
     * la conversion de fecha y monto sea explicita. Si una linea trae basura,
     * la excepcion sube envuelta en FlatFileParseException y el SkipPolicy la
     * reconoce como dato sucio.
     */
    @Bean
    public FlatFileItemReader<TransaccionCsv> transaccionReader() {
        return new FlatFileItemReaderBuilder<TransaccionCsv>()
                .name("transaccionReader")
                .resource(new ClassPathResource(ARCHIVO))
                .encoding("UTF-8")
                .linesToSkip(1)                       // cabecera id,fecha,monto,tipo
                .delimited()
                .delimiter(",")
                .names("id", "fecha", "monto", "tipo")
                .fieldSetMapper(fs -> new TransaccionCsv(
                        fs.readLong("id"),
                        LocalDate.parse(fs.readString("fecha").trim()),
                        new BigDecimal(fs.readString("monto").trim()),
                        fs.readString("tipo")))
                .build();
    }

    // ------------------------------------------------------------- PROCESSOR

    @Bean
    @StepScope
    public TransaccionProcessor transaccionProcessor(
            @Value("${bank.transacciones.umbral-anomalia:2500}") BigDecimal umbralAnomalia) {
        return new TransaccionProcessor(umbralAnomalia);
    }

    // ---------------------------------------------------------------- WRITER

    @Bean
    public JpaItemWriter<Transaccion> transaccionWriter(EntityManagerFactory emf) {
        JpaItemWriter<Transaccion> writer = new JpaItemWriter<>();
        writer.setEntityManagerFactory(emf);
        return writer;
    }

    // ----------------------------------------------------------------- STEPS

    @Bean
    public Step leerTransaccionesStep(JobRepository jobRepository,
                                      PlatformTransactionManager transactionManager,
                                      FlatFileItemReader<TransaccionCsv> transaccionReader,
                                      TransaccionProcessor transaccionProcessor,
                                      JpaItemWriter<Transaccion> transaccionWriter,
                                      BankSkipPolicy skipPolicy,
                                      LoggingSkipListener skipListener,
                                      ConteoStepListener conteoListener) {

        return new StepBuilder("leerTransaccionesStep", jobRepository)
                .<TransaccionCsv, Transaccion>chunk(TAMANO_CHUNK, transactionManager)
                .reader(transaccionReader)
                .processor(transaccionProcessor)
                .writer(transaccionWriter)
                .faultTolerant()
                // Skip: filas defectuosas no botan el Job.
                .skipPolicy(skipPolicy)
                // Retry: un fallo transitorio de BD (deadlock, timeout) se reintenta
                // antes de darlo por perdido.
                .retryLimit(3)
                .retry(TransientDataAccessException.class)
                .listener(skipListener)
                .listener(conteoListener)
                .build();
    }

    @Bean
    public Step resumenDiarioStep(JobRepository jobRepository,
                                  PlatformTransactionManager transactionManager,
                                  ResumenDiarioTasklet resumenDiarioTasklet,
                                  ConteoStepListener conteoListener) {

        return new StepBuilder("resumenDiarioStep", jobRepository)
                .tasklet((Tasklet) resumenDiarioTasklet, transactionManager)
                .listener(conteoListener)
                .build();
    }

    // ------------------------------------------------------------------- JOB

    @Bean
    public Job transaccionesJob(JobRepository jobRepository,
                                Step leerTransaccionesStep,
                                Step resumenDiarioStep) {

        return new JobBuilder("transaccionesJob", jobRepository)
                // Permite re-ejecutar el Job sin cambiar los parametros a mano:
                // el incrementer agrega un run.id distinto en cada corrida.
                .incrementer(new RunIdIncrementer())
                .start(leerTransaccionesStep)
                .next(resumenDiarioStep)
                .build();
    }
}
