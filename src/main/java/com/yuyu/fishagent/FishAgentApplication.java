package com.yuyu.fishagent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.yuyu.fishagent.mapper")
public class FishAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(FishAgentApplication.class, args);
    }

}
