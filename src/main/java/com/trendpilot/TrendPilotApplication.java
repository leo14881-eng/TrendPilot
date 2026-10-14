package com.trendpilot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ApplicationListener;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TrendPilotApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(TrendPilotApplication.class);
        app.addListeners(ensureDataDir());
        app.run(args);
    }

    /** 首次运行时 data 目录还不存在，SQLite 无法建库，所以在数据源创建前先建目录。 */
    static ApplicationListener<ApplicationEnvironmentPreparedEvent> ensureDataDir() {
        return event -> {
            String dir = event.getEnvironment().getProperty("trendpilot.data-dir", "./data");
            try {
                Files.createDirectories(Path.of(dir));
            } catch (IOException e) {
                throw new UncheckedIOException("无法创建数据目录 " + dir, e);
            }
        };
    }
}
