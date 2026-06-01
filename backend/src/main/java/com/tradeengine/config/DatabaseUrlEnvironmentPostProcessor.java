package com.tradeengine.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs BEFORE Spring's DataSource autoconfiguration. Two responsibilities:
 *
 *  1. Auto-activate the "render" Spring profile when running on Render
 *     (Render sets RENDER=true automatically). This guarantees
 *     application-render.yml is loaded even if the user forgets to set
 *     SPRING_PROFILES_ACTIVE.
 *
 *  2. Resolve the database URL from any of the common env-var spellings
 *     (DATABASE_URL, SPRING_DATASOURCE_URL, RENDER_DATABASE_URL, DB_URL),
 *     accept either jdbc:postgresql://… or postgres(ql)://user:pass@host/db,
 *     and translate it into spring.datasource.{url,username,password}.
 *
 * Loud diagnostics are printed (passwords masked) so a failed Render deploy
 * tells you immediately which env var was used and whether the hostname
 * looks like Render's INTERNAL form (bare "dpg-xxxx-a", no domain suffix)
 * which only resolves from a service in the SAME Render region.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String SOURCE_NAME = "renderDatabaseUrl";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {

        // ─── 1. Auto-activate "render" profile when on Render ──────────────────
        // Render sets RENDER=true on every service. We only add the profile if
        // no profile is already explicitly set, so local dev is untouched.
        String renderFlag = env.getProperty("RENDER");
        String activeProfiles = env.getProperty("spring.profiles.active");
        boolean onRender = "true".equalsIgnoreCase(renderFlag) || env.getProperty("RENDER_SERVICE_ID") != null;
        if (onRender && (activeProfiles == null || activeProfiles.isBlank())) {
            env.addActiveProfile("render");
            log("Detected Render environment → activated Spring profile 'render'");
        }

        // ─── 2. Resolve & parse the database URL ───────────────────────────────
        // Order matters: DATABASE_URL is Render's canonical name; we honour
        // SPRING_DATASOURCE_URL second so an operator override always wins
        // over our fallbacks. We log WHICH var supplied the value.
        Map<String, String> candidates = new LinkedHashMap<>();
        candidates.put("DATABASE_URL", env.getProperty("DATABASE_URL"));
        candidates.put("SPRING_DATASOURCE_URL", env.getProperty("SPRING_DATASOURCE_URL"));
        candidates.put("RENDER_DATABASE_URL", env.getProperty("RENDER_DATABASE_URL"));
        candidates.put("DB_URL", env.getProperty("DB_URL"));

        String sourceVar = null;
        String raw = null;
        for (Map.Entry<String, String> e : candidates.entrySet()) {
            if (e.getValue() != null && !e.getValue().isBlank()) {
                sourceVar = e.getKey();
                raw = e.getValue();
                break;
            }
        }

        if (raw == null) {
            log("No DATABASE_URL / SPRING_DATASOURCE_URL / DB_URL detected — using application.yml defaults.");
            return;
        }

        log("Detected database URL from env var: " + sourceVar + "  (value: " + maskUrl(raw) + ")");

        Map<String, Object> props = new HashMap<>();
        String host;
        try {
            if (raw.startsWith("jdbc:")) {
                props.put("spring.datasource.url", raw);
                host = extractHostFromJdbc(raw);
                log("Using pre-formatted JDBC URL (host=" + host + ")");
            } else if (raw.startsWith("postgres://") || raw.startsWith("postgresql://")) {
                URI uri = URI.create(raw);
                host = uri.getHost();
                int port = uri.getPort() == -1 ? 5432 : uri.getPort();
                String db = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
                String userInfo = uri.getUserInfo();
                String user = null, pass = null;
                if (userInfo != null) {
                    int idx = userInfo.indexOf(':');
                    if (idx >= 0) {
                        user = userInfo.substring(0, idx);
                        pass = userInfo.substring(idx + 1);
                    } else {
                        user = userInfo;
                    }
                }
                if (host == null || host.isBlank()) {
                    log("ERROR: " + sourceVar + " has no host component. Aborting parse.");
                    return;
                }
                String jdbc = "jdbc:postgresql://" + host + ":" + port + "/" + db;
                props.put("spring.datasource.url", jdbc);
                if (user != null) props.put("spring.datasource.username", user);
                if (pass != null) props.put("spring.datasource.password", pass);
                props.put("spring.datasource.driver-class-name", "org.postgresql.Driver");
                log("Parsed " + sourceVar + " → host=" + host + " port=" + port + " db=" + db
                        + " user=" + (user == null ? "<none>" : user));
            } else {
                log("ERROR: " + sourceVar + " is not a recognised format. Expected jdbc:postgresql://… "
                        + "or postgres://user:pass@host/db. Got: "
                        + raw.substring(0, Math.min(16, raw.length())) + "…");
                return;
            }
        } catch (Exception e) {
            log("ERROR parsing " + sourceVar + ": " + e.getMessage());
            return;
        }

        // ─── 3. Critical diagnostics for the "bare hostname" failure mode ─────
        // Render's INTERNAL hostnames look like "dpg-xxxxxxxxxxxx-a" — no dots.
        // They only resolve via Render's private DNS, AND only from a service
        // running in the SAME REGION as the database. From anywhere else
        // (different region, build container, local machine) you get
        // UnknownHostException. The fix is to use the EXTERNAL URL which has
        // a region suffix like ".oregon-postgres.render.com".
        if (host != null && !host.contains(".")) {
            log("==========================================================");
            log(" WARNING: hostname '" + host + "' has NO domain suffix.");
            log(" This is Render's INTERNAL hostname. It only resolves when:");
            log("   (a) the service is on Render, AND");
            log("   (b) the service is in the SAME REGION as the database.");
            log(" If you see java.net.UnknownHostException at startup, copy");
            log(" the EXTERNAL Database URL from the Render dashboard");
            log(" (host ends with e.g. '.oregon-postgres.render.com') and");
            log(" set it as DATABASE_URL on the web service.");
            log("==========================================================");
        }

        // Explicit user/password overrides win.
        if (env.getProperty("SPRING_DATASOURCE_USERNAME") != null)
            props.put("spring.datasource.username", env.getProperty("SPRING_DATASOURCE_USERNAME"));
        if (env.getProperty("SPRING_DATASOURCE_PASSWORD") != null)
            props.put("spring.datasource.password", env.getProperty("SPRING_DATASOURCE_PASSWORD"));
        if (env.getProperty("DB_USER") != null)
            props.put("spring.datasource.username", env.getProperty("DB_USER"));
        if (env.getProperty("DB_PASSWORD") != null)
            props.put("spring.datasource.password", env.getProperty("DB_PASSWORD"));

        env.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, props));

        log("Effective spring.datasource.url = " + props.get("spring.datasource.url"));
        log("Effective active profiles      = " + String.join(",", env.getActiveProfiles()));
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
        // postgres://user:secret@host…  →  postgres://user:****@host…
        return url.replaceAll("://([^:/@]+):([^@]+)@", "://$1:****@");
    }

    private static void log(String msg) {
        // Logger not initialised at this stage — stdout shows in Render logs.
        System.out.println("[DatabaseUrlEnvironmentPostProcessor] " + msg);
    }
}
