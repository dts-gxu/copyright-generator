package com.copyright;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.copyright.mapper")
public class CopyrightGeneratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(CopyrightGeneratorApplication.class, args);
    }
}
