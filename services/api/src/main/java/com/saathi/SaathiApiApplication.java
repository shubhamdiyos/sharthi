package com.saathi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SaathiApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SaathiApiApplication.class, args);
    }
}
