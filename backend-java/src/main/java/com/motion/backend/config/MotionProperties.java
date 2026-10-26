package com.motion.backend.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Environment-driven configuration surface.
 *
 * <p>Values are bound as strings so malformed optional environment variables can fall back to
 * safe defaults instead of aborting startup. No secret values are logged or exposed by this
 * object.</p>
 */
@ConfigurationProperties(prefix = "motion")
public class MotionProperties {

    private static final Logger log = LoggerFactory.getLogger(MotionProperties.class);

    private String env = "development";
    private String baseUrl = "http://localhost:8080";
    private Session session = new Session();
    private Cors cors = new Cors();
    private Proxy proxy = new Proxy();
    private RateLimit rateLimit = new RateLimit();
    private Email email = new Email();
    private Verification verification = new Verification();
    private Storage storage = new Storage();
    private String staticDir = "./web";
    private Encryption encryption = new Encryption();
    private Cleanup cleanup = new Cleanup();
    private Upload upload = new Upload();
    private Quota quota = new Quota();
    private Jobs jobs = new Jobs();

    @PostConstruct
    void validate() {
        if (sessionIdleTtl().compareTo(sessionTtl()) > 0) {
            log.warn("SESSION_IDLE_TTL exceeds SESSION_TTL; using application 2h/24h defaults");
            session.idleTtl = "2h";
            session.ttl = "24h";
        }
        if (maxGlobalConcurrentJobs() < 1) {
            jobs.maxGlobalConcurrentJobs = "3";
        }
        if (maxConcurrentJobsPerUser() < 1) {
            jobs.maxConcurrentJobsPerUser = "2";
        }
        if (workerCount() < 1) {
            jobs.workerCount = Integer.toString(maxGlobalConcurrentJobs());
        }
        if (queueCapacity() < 1) {
            jobs.queueCapacity = "100";
        }
        if (maxQueuedJobs() < 0) {
            jobs.maxQueuedJobs = "200";
        }
        if (maxActiveUploadsPerUser() < 1) {
            quota.maxActiveUploadsPerUser = "3";
        }
    }

    public String env() {
        return env == null || env.isBlank() ? "development" : env.trim();
    }

    public boolean production() {
        return "production".equalsIgnoreCase(env());
    }

    public String baseUrl() {
        return baseUrl == null || baseUrl.isBlank() ? "http://localhost:8080" : baseUrl.trim();
    }

    public Duration sessionTtl() {
        return ConfigParsing.durationValue(session.ttl, Duration.ofHours(24));
    }

    public Duration sessionIdleTtl() {
        return ConfigParsing.durationValue(session.idleTtl, Duration.ofHours(2));
    }

    public boolean secureCookies() {
        boolean fallback = production();
        return ConfigParsing.boolValue(session.secureCookies, fallback);
    }

    public List<String> trustedOrigins() {
        return ConfigParsing.listValue(cors.trustedOrigins);
    }

    public List<String> trustedProxies() {
        return ConfigParsing.listValue(proxy.trustedProxies);
    }

    public int rateLimitLogin() {
        return ConfigParsing.intValue(rateLimit.login, 10);
    }

    public int rateLimitRegister() {
        return ConfigParsing.intValue(rateLimit.register, 5);
    }

    public int rateLimitReset() {
        return ConfigParsing.intValue(rateLimit.reset, 3);
    }

    public int rateLimitVerify() {
        return ConfigParsing.intValue(rateLimit.verify, 5);
    }

    /**
     * Enforced on the upload endpoint; parsed but inactive when set to 0.
     */
    public int rateLimitUpload() {
        return ConfigParsing.intValue(rateLimit.upload, 30);
    }

    public String smtpHost() {
        return nullToEmpty(email.smtpHost).trim();
    }

    public boolean smtpConfigured() {
        return !smtpHost().isEmpty();
    }

    public int smtpPort() {
        return ConfigParsing.intValue(email.smtpPort, 587);
    }

    public String smtpUser() {
        return nullToEmpty(email.smtpUser);
    }

    public String smtpPass() {
        return nullToEmpty(email.smtpPass);
    }

    public String smtpFrom() {
        return nullToEmpty(email.smtpFrom);
    }

    public boolean devEmailOutbox() {
        boolean fallback = !production();
        return ConfigParsing.boolValue(email.devOutbox, fallback);
    }

    public boolean requireEmailVerification() {
        return ConfigParsing.boolValue(verification.required, false);
    }

    public Duration emailVerificationTtl() {
        return ConfigParsing.durationValue(verification.ttl, Duration.ofHours(24));
    }

    public Path staticDir() {
        return Path.of(nonBlank(staticDir, "./web"));
    }

    public Path dataDir() {
        return Path.of(nonBlank(storage.dataDir, "data"));
    }

    public Path uploadDir() {
        return Path.of(nonBlank(storage.uploadDir, "uploads"));
    }

    public Path outputDir() {
        return Path.of(nonBlank(storage.outputDir, "outputs"));
    }

    public Path tempDir() {
        return storage.tempDir == null || storage.tempDir.isBlank()
                ? dataDir().resolve("tmp")
                : Path.of(storage.tempDir.trim());
    }

    public Path previewDir() {
        return storage.previewDir == null || storage.previewDir.isBlank()
                ? dataDir().resolve("previews")
                : Path.of(storage.previewDir.trim());
    }

    public String encryptionKey() {
        return nullToEmpty(encryption.key);
    }

    public Duration retention() {
        return ConfigParsing.durationValue(cleanup.retention, Duration.ofHours(24));
    }

    public Duration cleanupInterval() {
        return ConfigParsing.durationValue(cleanup.interval, Duration.ofHours(1));
    }

    public long maxUploadBytes() {
        return ConfigParsing.longValue(upload.maxBytes, 157_286_400L);
    }

    public int maxDurationSeconds() {
        return ConfigParsing.intValue(upload.maxDurationSeconds, 300);
    }

    public int maxWidth() {
        return ConfigParsing.intValue(upload.maxWidth, 1920);
    }

    public int maxHeight() {
        return ConfigParsing.intValue(upload.maxHeight, 1080);
    }

    public int maxActiveUploadsPerUser() {
        return ConfigParsing.intValue(quota.maxActiveUploadsPerUser, 3);
    }

    public int maxSourceFilesPerUser() {
        return ConfigParsing.intValue(quota.maxSourceFilesPerUser, 50);
    }

    public int maxOutputsPerUser() {
        return ConfigParsing.intValue(quota.maxOutputsPerUser, 200);
    }

    public long maxStoredBytesPerUser() {
        return ConfigParsing.longValue(quota.maxStoredBytesPerUser, 5L * 1024 * 1024 * 1024);
    }

    public long maxTotalStorageBytes() {
        return ConfigParsing.longValue(quota.maxTotalStorageBytes, 50L * 1024 * 1024 * 1024);
    }

    public long minFreeDiskBytes() {
        return ConfigParsing.longValue(quota.minFreeDiskBytes, 1024L * 1024 * 1024);
    }

    public int maxConcurrentJobsPerUser() {
        return ConfigParsing.intValue(jobs.maxConcurrentJobsPerUser, 2);
    }

    public int maxGlobalConcurrentJobs() {
        return ConfigParsing.intValue(jobs.maxGlobalConcurrentJobs, 3);
    }

    public int workerCount() {
        return ConfigParsing.intValue(jobs.workerCount, 3);
    }

    public int queueCapacity() {
        return ConfigParsing.intValue(jobs.queueCapacity, 100);
    }

    public int maxQueuedJobs() {
        return ConfigParsing.intValue(jobs.maxQueuedJobs, 200);
    }

    public Duration jobPollInterval() {
        return ConfigParsing.durationValue(jobs.pollInterval, Duration.ofSeconds(2));
    }

    public Duration jobProgressInterval() {
        return ConfigParsing.durationValue(jobs.progressInterval, Duration.ofMillis(500));
    }

    public Duration jobShutdownTimeout() {
        return ConfigParsing.durationValue(jobs.shutdownTimeout, Duration.ofSeconds(30));
    }

    /** Hard wall-clock limit for a single FFmpeg execution. */
    public Duration ffmpegTimeout() {
        return ConfigParsing.durationValue(jobs.ffmpegTimeout, Duration.ofHours(2));
    }

    public boolean retentionDisabled() {
        return cleanup.retention != null && "0".equals(cleanup.retention.trim());
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public String getEnv() {
        return env;
    }

    public void setEnv(String env) {
        this.env = env;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Session getSession() {
        return session;
    }

    public void setSession(Session session) {
        this.session = session;
    }

    public Cors getCors() {
        return cors;
    }

    public void setCors(Cors cors) {
        this.cors = cors;
    }

    public Proxy getProxy() {
        return proxy;
    }

    public void setProxy(Proxy proxy) {
        this.proxy = proxy;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public Email getEmail() {
        return email;
    }

    public void setEmail(Email email) {
        this.email = email;
    }

    public Verification getVerification() {
        return verification;
    }

    public void setVerification(Verification verification) {
        this.verification = verification;
    }

    public Storage getStorage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    public String getStaticDir() {
        return staticDir;
    }

    public void setStaticDir(String staticDir) {
        this.staticDir = staticDir;
    }

    public Encryption getEncryption() {
        return encryption;
    }

    public void setEncryption(Encryption encryption) {
        this.encryption = encryption;
    }

    public Cleanup getCleanup() {
        return cleanup;
    }

    public void setCleanup(Cleanup cleanup) {
        this.cleanup = cleanup;
    }

    public Upload getUpload() {
        return upload;
    }

    public void setUpload(Upload upload) {
        this.upload = upload;
    }

    public Quota getQuota() {
        return quota;
    }

    public void setQuota(Quota quota) {
        this.quota = quota;
    }

    public Jobs getJobs() {
        return jobs;
    }

    public void setJobs(Jobs jobs) {
        this.jobs = jobs;
    }

    public static class Session {
        private String ttl = "24h";
        private String idleTtl = "2h";
        private String secureCookies = "";

        public String getTtl() { return ttl; }
        public void setTtl(String ttl) { this.ttl = ttl; }
        public String getIdleTtl() { return idleTtl; }
        public void setIdleTtl(String idleTtl) { this.idleTtl = idleTtl; }
        public String getSecureCookies() { return secureCookies; }
        public void setSecureCookies(String secureCookies) { this.secureCookies = secureCookies; }
    }

    public static class Cors {
        private String trustedOrigins = "";

        public String getTrustedOrigins() { return trustedOrigins; }
        public void setTrustedOrigins(String trustedOrigins) { this.trustedOrigins = trustedOrigins; }
    }

    public static class Proxy {
        private String trustedProxies = "";

        public String getTrustedProxies() { return trustedProxies; }
        public void setTrustedProxies(String trustedProxies) { this.trustedProxies = trustedProxies; }
    }

    public static class RateLimit {
        private String login = "10";
        private String register = "5";
        private String reset = "3";
        private String verify = "5";
        private String upload = "30";

        public String getLogin() { return login; }
        public void setLogin(String login) { this.login = login; }
        public String getRegister() { return register; }
        public void setRegister(String register) { this.register = register; }
        public String getReset() { return reset; }
        public void setReset(String reset) { this.reset = reset; }
        public String getVerify() { return verify; }
        public void setVerify(String verify) { this.verify = verify; }
        public String getUpload() { return upload; }
        public void setUpload(String upload) { this.upload = upload; }
    }

    public static class Email {
        private String smtpHost = "";
        private String smtpPort = "587";
        private String smtpUser = "";
        private String smtpPass = "";
        private String smtpFrom = "";
        private String devOutbox = "true";

        public String getSmtpHost() { return smtpHost; }
        public void setSmtpHost(String smtpHost) { this.smtpHost = smtpHost; }
        public String getSmtpPort() { return smtpPort; }
        public void setSmtpPort(String smtpPort) { this.smtpPort = smtpPort; }
        public String getSmtpUser() { return smtpUser; }
        public void setSmtpUser(String smtpUser) { this.smtpUser = smtpUser; }
        public String getSmtpPass() { return smtpPass; }
        public void setSmtpPass(String smtpPass) { this.smtpPass = smtpPass; }
        public String getSmtpFrom() { return smtpFrom; }
        public void setSmtpFrom(String smtpFrom) { this.smtpFrom = smtpFrom; }
        public String getDevOutbox() { return devOutbox; }
        public void setDevOutbox(String devOutbox) { this.devOutbox = devOutbox; }
    }

    public static class Verification {
        private String required = "false";
        private String ttl = "24h";

        public String getRequired() { return required; }
        public void setRequired(String required) { this.required = required; }
        public String getTtl() { return ttl; }
        public void setTtl(String ttl) { this.ttl = ttl; }
    }

    public static class Storage {
        private String dataDir = "data";
        private String uploadDir = "uploads";
        private String outputDir = "outputs";
        private String tempDir = "";
        private String previewDir = "";

        public String getDataDir() { return dataDir; }
        public void setDataDir(String dataDir) { this.dataDir = dataDir; }
        public String getUploadDir() { return uploadDir; }
        public void setUploadDir(String uploadDir) { this.uploadDir = uploadDir; }
        public String getOutputDir() { return outputDir; }
        public void setOutputDir(String outputDir) { this.outputDir = outputDir; }
        public String getTempDir() { return tempDir; }
        public void setTempDir(String tempDir) { this.tempDir = tempDir; }
        public String getPreviewDir() { return previewDir; }
        public void setPreviewDir(String previewDir) { this.previewDir = previewDir; }
    }

    public static class Encryption {
        private String key = "";

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
    }

    public static class Cleanup {
        private String retention = "24h";
        private String interval = "1h";

        public String getRetention() { return retention; }
        public void setRetention(String retention) { this.retention = retention; }
        public String getInterval() { return interval; }
        public void setInterval(String interval) { this.interval = interval; }
    }

    public static class Upload {
        private String maxBytes = "157286400";
        private String maxDurationSeconds = "300";
        private String maxWidth = "1920";
        private String maxHeight = "1080";

        public String getMaxBytes() { return maxBytes; }
        public void setMaxBytes(String maxBytes) { this.maxBytes = maxBytes; }
        public String getMaxDurationSeconds() { return maxDurationSeconds; }
        public void setMaxDurationSeconds(String maxDurationSeconds) { this.maxDurationSeconds = maxDurationSeconds; }
        public String getMaxWidth() { return maxWidth; }
        public void setMaxWidth(String maxWidth) { this.maxWidth = maxWidth; }
        public String getMaxHeight() { return maxHeight; }
        public void setMaxHeight(String maxHeight) { this.maxHeight = maxHeight; }
    }

    public static class Quota {
        private String maxActiveUploadsPerUser = "3";
        private String maxSourceFilesPerUser = "50";
        private String maxOutputsPerUser = "200";
        private String maxStoredBytesPerUser = "5368709120";
        private String maxTotalStorageBytes = "53687091200";
        private String minFreeDiskBytes = "1073741824";

        public String getMaxActiveUploadsPerUser() { return maxActiveUploadsPerUser; }
        public void setMaxActiveUploadsPerUser(String value) { this.maxActiveUploadsPerUser = value; }
        public String getMaxSourceFilesPerUser() { return maxSourceFilesPerUser; }
        public void setMaxSourceFilesPerUser(String value) { this.maxSourceFilesPerUser = value; }
        public String getMaxOutputsPerUser() { return maxOutputsPerUser; }
        public void setMaxOutputsPerUser(String value) { this.maxOutputsPerUser = value; }
        public String getMaxStoredBytesPerUser() { return maxStoredBytesPerUser; }
        public void setMaxStoredBytesPerUser(String value) { this.maxStoredBytesPerUser = value; }
        public String getMaxTotalStorageBytes() { return maxTotalStorageBytes; }
        public void setMaxTotalStorageBytes(String value) { this.maxTotalStorageBytes = value; }
        public String getMinFreeDiskBytes() { return minFreeDiskBytes; }
        public void setMinFreeDiskBytes(String value) { this.minFreeDiskBytes = value; }
    }

    public static class Jobs {
        private String maxConcurrentJobsPerUser = "2";
        private String maxGlobalConcurrentJobs = "3";
        private String workerCount = "3";
        private String queueCapacity = "100";
        private String maxQueuedJobs = "200";
        private String pollInterval = "2s";
        private String progressInterval = "500ms";
        private String shutdownTimeout = "30s";
        private String ffmpegTimeout = "2h";

        public String getMaxConcurrentJobsPerUser() { return maxConcurrentJobsPerUser; }
        public void setMaxConcurrentJobsPerUser(String value) { this.maxConcurrentJobsPerUser = value; }
        public String getMaxGlobalConcurrentJobs() { return maxGlobalConcurrentJobs; }
        public void setMaxGlobalConcurrentJobs(String value) { this.maxGlobalConcurrentJobs = value; }
        public String getWorkerCount() { return workerCount; }
        public void setWorkerCount(String value) { this.workerCount = value; }
        public String getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(String value) { this.queueCapacity = value; }
        public String getMaxQueuedJobs() { return maxQueuedJobs; }
        public void setMaxQueuedJobs(String value) { this.maxQueuedJobs = value; }
        public String getPollInterval() { return pollInterval; }
        public void setPollInterval(String value) { this.pollInterval = value; }
        public String getProgressInterval() { return progressInterval; }
        public void setProgressInterval(String value) { this.progressInterval = value; }
        public String getShutdownTimeout() { return shutdownTimeout; }
        public void setShutdownTimeout(String value) { this.shutdownTimeout = value; }
        public String getFfmpegTimeout() { return ffmpegTimeout; }
        public void setFfmpegTimeout(String value) { this.ffmpegTimeout = value; }
    }
}
