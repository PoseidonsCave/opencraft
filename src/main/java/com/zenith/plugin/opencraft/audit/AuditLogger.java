package com.zenith.plugin.opencraft.audit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import com.google.gson.Gson;
import com.zenith.plugin.opencraft.OpenCraftConfig;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

public final class AuditLogger {

    private static final String AUDIT_LOGGER_NAME = "com.zenith.plugin.opencraft.audit.AuditTrail";

    private final OpenCraftConfig config;
    private final ComponentLogger logger;
    private final Gson            gson;
    private final Logger          auditLog;

    public AuditLogger(final OpenCraftConfig config, final ComponentLogger logger) {
        this.config   = config;
        this.logger   = logger;
        this.gson     = new Gson();
        this.auditLog = configureAppender();
    }

    public void log(final AuditEvent event) {
        if (!config.auditLogEnabled) return;
        if (auditLog == null) return;
        try {
            auditLog.info(gson.toJson(event));
        } catch (final Exception e) {
            logger.warn("[OpenCraft] Audit log write failed: {}", e.getMessage());
        }
    }

        public void pruneOldEntries() {
    }

    private Logger configureAppender() {
        try {
            final Path logPath = Path.of(config.auditLogPath);
            final Path parent  = logPath.getParent();
            if (parent != null) Files.createDirectories(parent);

            final org.slf4j.ILoggerFactory factory = LoggerFactory.getILoggerFactory();
            if (!(factory instanceof LoggerContext context)) {
                logger.warn("[OpenCraft] SLF4J binding is not logback-classic; audit log will be disabled.");
                return null;
            }

            final Logger target = context.getLogger(AUDIT_LOGGER_NAME);
            if (target.iteratorForAppenders().hasNext()) {
                return target;
            }

            final PatternLayoutEncoder encoder = new PatternLayoutEncoder();
            encoder.setContext(context);
            encoder.setPattern("%msg%n");
            encoder.setCharset(java.nio.charset.StandardCharsets.UTF_8);
            encoder.start();

            final RollingFileAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new RollingFileAppender<>();
            appender.setContext(context);
            appender.setName("opencraft-audit");
            appender.setFile(logPath.toString());
            appender.setEncoder(encoder);
            appender.setAppend(true);

            final SizeAndTimeBasedRollingPolicy<ch.qos.logback.classic.spi.ILoggingEvent> policy =
                new SizeAndTimeBasedRollingPolicy<>();
            policy.setContext(context);
            policy.setParent(appender);
            policy.setFileNamePattern(logPath.toString() + ".%d{yyyy-MM-dd}.%i.gz");
            policy.setMaxFileSize(ch.qos.logback.core.util.FileSize.valueOf("10MB"));
            policy.setMaxHistory(Math.max(1, config.auditRetentionDays));
            policy.setTotalSizeCap(ch.qos.logback.core.util.FileSize.valueOf("500MB"));
            policy.start();

            appender.setRollingPolicy(policy);
            appender.setTriggeringPolicy(policy);
            appender.start();

            target.setAdditive(false);
            target.setLevel(ch.qos.logback.classic.Level.INFO);
            target.addAppender(appender);
            return target;
        } catch (final Exception e) {
            logger.warn("[OpenCraft] Failed to configure audit appender: {}", e.getMessage());
            return null;
        }
    }
}
