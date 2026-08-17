package cl.duoc.bank.batch.intereses;

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

/**
 * Job 2 - Calculo de intereses mensuales.
 *
 * Un solo Step de chunk: CSV -> calculo -> tabla cuenta.
 *
 * La escritura es transaccional por chunk: JpaItemWriter hace merge, o sea
 * inserta la cuenta si no existe y actualiza saldo_final si ya estaba. Si un
 * chunk falla, ese chunk completo se revierte y no queda un saldo a medio
 * actualizar.
 */
@Configuration
public class InteresesJobConfig {

    private static final String ARCHIVO = "data/intereses.csv";
    private static final int TAMANO_CHUNK = 5;

    // ---------------------------------------------------------------- READER

    @Bean
    public FlatFileItemReader<CuentaCsv> cuentaReader() {
        return new FlatFileItemReaderBuilder<CuentaCsv>()
                .name("cuentaReader")
                .resource(new ClassPathResource(ARCHIVO))
                .encoding("UTF-8")
                .linesToSkip(1)                       // cabecera cuenta_id,nombre,saldo,edad,tipo
                .delimited()
                .delimiter(",")
                .names("cuenta_id", "nombre", "saldo", "edad", "tipo")
                .fieldSetMapper(fs -> new CuentaCsv(
                        fs.readLong("cuenta_id"),
                        fs.readString("nombre").trim(),
                        new BigDecimal(fs.readString("saldo").trim()),
                        fs.readInt("edad"),
                        fs.readString("tipo")))
                .build();
    }

    // ------------------------------------------------------------- PROCESSOR

    /**
     * Tasas mensuales. Son placeholders configurables por properties; hay que
     * confirmarlas con el facilitador antes de la entrega final.
     *   ahorro   0,5 % mensual -> 0.005
     *   prestamo 1,5 % mensual -> 0.015
     */
    @Bean
    @StepScope
    public InteresProcessor interesProcessor(
            @Value("${bank.intereses.tasa-ahorro:0.005}") BigDecimal tasaAhorro,
            @Value("${bank.intereses.tasa-prestamo:0.015}") BigDecimal tasaPrestamo) {
        return new InteresProcessor(tasaAhorro, tasaPrestamo);
    }

    // ---------------------------------------------------------------- WRITER

    @Bean
    public JpaItemWriter<Cuenta> cuentaWriter(EntityManagerFactory emf) {
        JpaItemWriter<Cuenta> writer = new JpaItemWriter<>();
        writer.setEntityManagerFactory(emf);
        return writer;
    }

    // ------------------------------------------------------------ STEP y JOB

    @Bean
    public Step calcularInteresesStep(JobRepository jobRepository,
                                      PlatformTransactionManager transactionManager,
                                      FlatFileItemReader<CuentaCsv> cuentaReader,
                                      InteresProcessor interesProcessor,
                                      JpaItemWriter<Cuenta> cuentaWriter,
                                      BankSkipPolicy skipPolicy,
                                      LoggingSkipListener skipListener,
                                      ConteoStepListener conteoListener) {

        return new StepBuilder("calcularInteresesStep", jobRepository)
                .<CuentaCsv, Cuenta>chunk(TAMANO_CHUNK, transactionManager)
                .reader(cuentaReader)
                .processor(interesProcessor)
                .writer(cuentaWriter)
                .faultTolerant()
                .skipPolicy(skipPolicy)
                .retryLimit(3)
                .retry(TransientDataAccessException.class)
                .listener(skipListener)
                .listener(conteoListener)
                .build();
    }

    @Bean
    public Job interesesJob(JobRepository jobRepository, Step calcularInteresesStep) {
        return new JobBuilder("interesesJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(calcularInteresesStep)
                .build();
    }
}
