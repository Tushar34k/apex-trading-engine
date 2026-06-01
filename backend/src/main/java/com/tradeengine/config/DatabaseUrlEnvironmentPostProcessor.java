package com.tradeengine.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Translates a Render / Heroku style DATABASE_URL
 *   postgres://user:password@host:5432/dbname
 *   postgresql://user:password@host:5432/dbname
 * into Spring Boot's spring.datasource.{url,username,password} properties.
 *
 * Also accepts a fully-formed JDBC URL (jdbc:postgresql://...) and passes it through.
 *
 * Registered via META-INF/spring.factories so it runs BEFORE the DataSource
 * autoconfiguration tries to read the properties.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String SOURCE_NAME = "renderDatabaseUrl";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        String raw = firstNonBlank(
                env.getProperty("DATABASE_URL"),
                env.getProperty("RENDER_DATABASE_URL"),
                env.getProperty("DB_URL")
        );
        if (raw == null || raw.isBlank()) {
            log("No DATABASE_URL / DB_URL found in environment — falling back to application.yml defaults.");
            return;
        }

        Map<String, Object> props = new HashMap<>();
        try {
            if (raw.startsWith("jdbc:")) {
                // Already JDBC — let Spring use it directly.
                props.put("spring.datasource.url", raw);
                log("Using pre-formatted JDBC URL from env (host=" + extractHostFromJdbc(raw) + ")");
            } else if (raw.startsWith("postgres://") || raw.startsWith("postgresql://")) {
                URI uri = URI.create(raw);
                String host = uri.getHost();
                int port = uri.getPort() == -1 ? 5432 : uri.getPort();
                String db = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
                String userInfo = uri.getUserInfo();
                String user = null, pass = null;
                if (userInfo != null && userInfo.contains(":")) {
                    int idx = userInfo.indexOf(':');
                    user = userInfo.substring(0, idx);
                    pass = userInfo.substring(idx + 1);
                } else if (userInfo != null) {
                    user = userInfo;
                }

                if (host == null || host.isBlank()) {
                    log("ERROR: DATABASE_URL has no host component: " + maskUrl(raw));
                    return;
                }

                // Render's INTERNAL hostnames are bare (no dots). They only resolve
                // inside Render's private network. Warn loudly so the user knows
                // they pasted the wrong value if running outside that network.
                if (!host.contains(".")) {
                    log("WARNING: database host '" + host + "' has no domain suffix. "
                            + "If you are NOT on Render's private network, use the "
                            + "EXTERNAL connection string (host ends with .render.com or similar).");
                }

                String jdbc = "jdbc:postgresql://" + host + ":" + port + "/" + db;
                props.put("spring.datasource.url", jdbc);
                if (user != null) props.put("spring.datasource.username", user);
                if (pass != null) props.put("spring.datasource.password", pass);
                props.put("spring.datasource.driver-class-name", "org.postgresql.Driver");

                log("Parsed DATABASE_URL → host=" + host + " port=" + port + " db=" + db
                        + " user=" + (user == null ? "<none>" : user));
            } else {
                log("ERROR: DATABASE_URL is not a recognised format (expected jdbc:postgresql://… "
                        + "or postgres://user:pass@host/db). Value starts with: "
                        + raw.substring(0, Math.min(12, raw.length())) + "…");
                return;
            }
        } catch (Exception e) {
            log("ERROR parsing DATABASE_URL: " + e.getMessage());
            return;
        }

        // Allow explicit overrides if user set them too
        if (env.getProperty("DB_USER") != null) props.put("spring.datasource.username", env.getProperty("DB_USER"));
        if (env.getProperty("DB_PASSWORD") != null) props.put("spring.datasource.password", env.getProperty("DB_PASSWORD"));

        env.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, props));
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static String extractHostFromJdbc(String jdbc) {
        try {
            String stripped = jdbc.replaceFirst("^jdbc:", "");
            return URI.create(stripped).getHost();
        } catch (Exception e) {
            return "?";
        }
    }

    private static String maskUrl(String url) {
        return url.replaceAll(":([^/@:]+)@", ":****@");
    }

    private static void log(String msg) {
        // Logger isn't initialised yet at this stage — use stdout.
        System.out.println("[DatabaseUrlEnvironmentPostProcessor] " + msg);
    }
}
