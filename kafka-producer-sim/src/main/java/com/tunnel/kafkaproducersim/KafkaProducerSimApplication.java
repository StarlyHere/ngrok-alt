package com.tunnel.kafkaproducersim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class KafkaProducerSimApplication {

    public static void main(String[] args) {
        SpringApplication.run(KafkaProducerSimApplication.class, args);
    }
}
