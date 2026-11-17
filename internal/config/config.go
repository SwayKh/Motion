package config

import (
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// Config holds all environment-driven application configuration.
type Config struct {
	Env               string
	Port              string
	SessionTTL        time.Duration
	SessionIdleTTL    time.Duration
	SecureCookies     bool
	TrustedOrigins    []string
	TrustedProxies    []string
	RateLimitLogin    int
	RateLimitRegister int
	RateLimitReset    int
	RateLimitVerify   int
	RateLimitUpload   int
	SMTPHost          string
	SMTPPort          int
	SMTPUser          string
	SMTPPass          string
	SMTPFrom          string
	BaseURL           string

	// Storage locations (relative to the process working directory unless absolute).
	DataDir    string
	UploadDir  string
	OutputDir  string
	TempDir    string
	PreviewDir string

	// Encryption at rest.
	EncryptionKey string

	// Retention / cleanup.
	Retention       time.Duration
	CleanupInterval time.Duration

	// Upload validation.
	MaxUploadBytes     int64
	MaxDurationSeconds float64
	MaxWidth           int
	MaxHeight          int

	// Quotas.
	MaxActiveUploadsPerUser  int
	MaxSourceFilesPerUser    int
	MaxOutputsPerUser        int
	MaxStoredBytesPerUser    int64
	MaxConcurrentJobsPerUser int
	MaxGlobalConcurrentJobs  int
	MaxTotalStorageBytes     int64
	MinFreeDiskBytes         int64

	// Email verification.
	RequireEmailVerification bool
	EmailVerificationTTL     time.Duration
	DevEmailOutbox           bool
}

// Load reads configuration from the environment, applies defaults and validates.
func Load() *Config {
	cfg := &Config{
		Env:               getEnv("APP_ENV", "development"),
		Port:              getEnv("PORT", "8080"),
		SessionTTL:        getDuration("SESSION_TTL", 24*time.Hour),
		SessionIdleTTL:    getDuration("SESSION_IDLE_TTL", 2*time.Hour),
		SecureCookies:     getBool("SECURE_COOKIES", strings.EqualFold(getEnv("APP_ENV", "development"), "production")),
		TrustedOrigins:    normalizeOrigins(split(getEnv("TRUSTED_ORIGINS", ""))),
		TrustedProxies:    split(getEnv("TRUSTED_PROXIES", "")),
		RateLimitLogin:    getInt("RATE_LIMIT_LOGIN", 10),
		RateLimitRegister: getInt("RATE_LIMIT_REGISTER", 5),
		RateLimitReset:    getInt("RATE_LIMIT_RESET", 3),
		RateLimitVerify:   getInt("RATE_LIMIT_VERIFY", 5),
		RateLimitUpload:   getInt("RATE_LIMIT_UPLOAD", 30),
		SMTPHost:          getEnv("SMTP_HOST", ""),
		SMTPPort:          getInt("SMTP_PORT", 587),
		SMTPUser:          getEnv("SMTP_USER", ""),
		SMTPPass:          getEnv("SMTP_PASS", ""),
		SMTPFrom:          getEnv("SMTP_FROM", ""),
		BaseURL:           getEnv("BASE_URL", "http://localhost:8080"),

		DataDir:       getEnv("MOTION_DATA_DIR", "data"),
		UploadDir:     getEnv("MOTION_UPLOAD_DIR", "uploads"),
		OutputDir:     getEnv("MOTION_OUTPUT_DIR", "outputs"),
		TempDir:       getEnv("MOTION_TEMP_DIR", ""),
		PreviewDir:    getEnv("MOTION_PREVIEW_DIR", ""),
		EncryptionKey: strings.TrimSpace(getEnv("MOTION_ENCRYPTION_KEY", "")),

		Retention:       getDuration("RETENTION", 24*time.Hour),
		CleanupInterval: getDuration("CLEANUP_INTERVAL", time.Hour),

		MaxUploadBytes:     getInt64("MAX_UPLOAD_BYTES", 150<<20),
		MaxDurationSeconds: getFloat("MAX_DURATION_SECONDS", 300),
		MaxWidth:           getInt("MAX_WIDTH", 1920),
		MaxHeight:          getInt("MAX_HEIGHT", 1080),

		MaxActiveUploadsPerUser:  getInt("MAX_ACTIVE_UPLOADS_PER_USER", 3),
		MaxSourceFilesPerUser:    getInt("MAX_SOURCE_FILES_PER_USER", 50),
		MaxOutputsPerUser:        getInt("MAX_OUTPUTS_PER_USER", 200),
		MaxStoredBytesPerUser:    getInt64("MAX_STORED_BYTES_PER_USER", 5<<30),
		MaxConcurrentJobsPerUser: getInt("MAX_CONCURRENT_JOBS_PER_USER", 2),
		MaxGlobalConcurrentJobs:  getInt("MAX_GLOBAL_CONCURRENT_JOBS", 3),
		MaxTotalStorageBytes:     getInt64("MAX_TOTAL_STORAGE_BYTES", 50<<30),
		MinFreeDiskBytes:         getInt64("MIN_FREE_DISK_BYTES", 1<<30),

		RequireEmailVerification: getBool("REQUIRE_EMAIL_VERIFICATION", false),
		EmailVerificationTTL:     getDuration("EMAIL_VERIFICATION_TTL", 24*time.Hour),
		DevEmailOutbox:           getBool("DEV_EMAIL_OUTBOX", !strings.EqualFold(getEnv("APP_ENV", "development"), "production")),
	}

	if cfg.TempDir == "" {
		cfg.TempDir = cfg.DataDir + "/tmp"
	}
	if cfg.PreviewDir == "" {
		cfg.PreviewDir = filepath.Join(cfg.DataDir, "previews")
	}

	if err := cfg.Validate(); err != nil {
		panic(err)
	}
	return cfg
}

// Validate rejects clearly invalid configuration.
func (c *Config) Validate() error {
	if c.SessionTTL <= 0 {
		return fmt.Errorf("invalid SESSION_TTL")
	}
	if c.SessionIdleTTL <= 0 {
		return fmt.Errorf("invalid SESSION_IDLE_TTL")
	}
	if c.SessionIdleTTL > c.SessionTTL {
		return fmt.Errorf("SESSION_IDLE_TTL (%s) must not exceed SESSION_TTL (%s)", c.SessionIdleTTL, c.SessionTTL)
	}
	if c.RateLimitLogin < 0 || c.RateLimitRegister < 0 || c.RateLimitReset < 0 || c.RateLimitVerify < 0 || c.RateLimitUpload < 0 {
		return fmt.Errorf("invalid rate limit")
	}
	if c.MaxUploadBytes <= 0 {
		return fmt.Errorf("invalid MAX_UPLOAD_BYTES")
	}
	if c.MaxDurationSeconds <= 0 {
		return fmt.Errorf("invalid MAX_DURATION_SECONDS")
	}
	if c.MaxWidth <= 0 || c.MaxHeight <= 0 {
		return fmt.Errorf("invalid MAX_WIDTH/MAX_HEIGHT")
	}
	if c.MaxActiveUploadsPerUser < 1 {
		return fmt.Errorf("MAX_ACTIVE_UPLOADS_PER_USER must be at least 1")
	}
	if c.MaxSourceFilesPerUser < 0 || c.MaxOutputsPerUser < 0 {
		return fmt.Errorf("invalid file-count quota")
	}
	if c.MaxStoredBytesPerUser < 0 || c.MaxTotalStorageBytes < 0 {
		return fmt.Errorf("invalid storage quota")
	}
	if c.MaxGlobalConcurrentJobs < 1 {
		return fmt.Errorf("MAX_GLOBAL_CONCURRENT_JOBS must be at least 1")
	}
	if c.MaxConcurrentJobsPerUser < 1 {
		return fmt.Errorf("MAX_CONCURRENT_JOBS_PER_USER must be at least 1")
	}
	if c.Retention <= 0 || c.CleanupInterval <= 0 {
		return fmt.Errorf("invalid retention/cleanup interval")
	}
	if c.EmailVerificationTTL <= 0 {
		return fmt.Errorf("invalid EMAIL_VERIFICATION_TTL")
	}
	if c.EncryptionKey != "" && len(c.EncryptionKey) < 16 {
		return fmt.Errorf("MOTION_ENCRYPTION_KEY looks invalid")
	}
	return nil
}

// EncryptionEnabled reports whether at-rest encryption is configured.
func (c *Config) EncryptionEnabled() bool { return c.EncryptionKey != "" }

func getEnv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func getInt(key string, def int) int {
	if v := os.Getenv(key); v != "" {
		if i, err := strconv.Atoi(v); err == nil {
			return i
		}
	}
	return def
}

func getInt64(key string, def int64) int64 {
	if v := os.Getenv(key); v != "" {
		if i, err := strconv.ParseInt(v, 10, 64); err == nil {
			return i
		}
	}
	return def
}

func getFloat(key string, def float64) float64 {
	if v := os.Getenv(key); v != "" {
		if f, err := strconv.ParseFloat(v, 64); err == nil {
			return f
		}
	}
	return def
}

func getBool(key string, def bool) bool {
	if v := os.Getenv(key); v != "" {
		v = strings.ToLower(v)
		if v == "1" || v == "true" || v == "yes" {
			return true
		}
		if v == "0" || v == "false" || v == "no" {
			return false
		}
	}
	return def
}

func getDuration(key string, def time.Duration) time.Duration {
	if v := os.Getenv(key); v != "" {
		if d, err := time.ParseDuration(v); err == nil {
			if d <= 0 {
				return def
			}
			return d
		}
	}
	return def
}

func split(s string) []string {
	if s == "" {
		return nil
	}
	parts := strings.Split(s, ",")
	var out []string
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}

func normalizeOrigins(origins []string) []string {
	var out []string
	for _, o := range origins {
		o = strings.TrimSpace(o)
		if o == "" {
			continue
		}
		if !strings.Contains(o, "://") {
			continue // reject malformed
		}
		u, err := url.Parse(o)
		if err != nil || u.Scheme == "" || u.Host == "" {
			continue
		}
		out = append(out, u.Scheme+"://"+u.Host)
	}
	return out
}
