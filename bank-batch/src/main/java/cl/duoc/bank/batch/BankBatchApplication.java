package cl.duoc.bank.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Punto de entrada. No se usa @EnableBatchProcessing: con Spring Boot 3 la
 * autoconfiguracion de Spring Batch ya registra el JobRepository, el
 * JobLauncher y el runner que dispara los Jobs al arrancar. Anotarlo
 * desactivaria esa autoconfiguracion.
 */
@SpringBootApplication
public class BankBatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankBatchApplication.class, args);
    }
}
