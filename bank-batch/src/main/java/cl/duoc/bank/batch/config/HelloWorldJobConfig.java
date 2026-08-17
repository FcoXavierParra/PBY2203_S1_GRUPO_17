package cl.duoc.bank.batch.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Job de humo. Su unico proposito es verificar que:
 *  - el contexto de Spring Batch levanta contra PostgreSQL,
 *  - las tablas BATCH_* de metadata se crean, y
 *  - la ejecucion queda registrada con estado COMPLETED.
 *
 * Los tres Jobs de la experiencia (transacciones, intereses, estados anuales)
 * se implementan despues, en paquetes propios.
 */
@Slf4j
@Configuration
public class HelloWorldJobConfig {

    @Bean
    public Tasklet helloWorldTasklet() {
        return (contribution, chunkContext) -> {
            String jobName = chunkContext.getStepContext()
                    .getStepExecution()
                    .getJobExecution()
                    .getJobInstance()
                    .getJobName();

            log.info("==========================================");
            log.info(" Hello World desde Spring Batch");
            log.info(" Job          : {}", jobName);
            log.info(" Step         : {}", chunkContext.getStepContext().getStepName());
            log.info(" JobExecution : {}", chunkContext.getStepContext().getStepExecution()
                    .getJobExecutionId());
            log.info("==========================================");

            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    public Step helloWorldStep(JobRepository jobRepository,
                               PlatformTransactionManager transactionManager,
                               Tasklet helloWorldTasklet) {
        return new StepBuilder("helloWorldStep", jobRepository)
                .tasklet(helloWorldTasklet, transactionManager)
                .build();
    }

    @Bean
    public Job helloWorldJob(JobRepository jobRepository, Step helloWorldStep) {
        return new JobBuilder("helloWorldJob", jobRepository)
                .start(helloWorldStep)
                .build();
    }
}
