package com.tradeengine.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.Arrays;

@Component
public class DataSourceStartupLogger {

    private static final Logger log = LoggerFactory.getLogger(DataSourceStartupLogger.class);

    private final DataSource dataSource;
    private final Environment env;

    public DataSourceStartupLogger(DataSource dataSource, Environment env) {
        this.dataSource = dataSource;
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logDatasource() {
        String url = env.getProperty("spring.datasource.url");
        String user = env.getProperty("spring.datasource.username");
        String[] profiles = env.getActiveProfiles();
        log.info("============================================================");
        log.info(" Active Spring profiles : {}", profiles.length == 0 ? "[default]" : Arrays.toString(profiles));
        log.info(" Resolved JDBC URL      : {}", url);
        log.info(" DB user                : {}", user);
        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            log.info(" Connected to           : {} {}", md.getDatabaseProductName(), md.getDatabaseProductVersion());
            log.info(" Connection URL (live)  : {}", md.getURL());
        } catch (Exception e) {
            log.error(" DB connection check FAILED: {}", e.getMessage());
        }
        log.info("============================================================");
    }
}
