-- Motion standalone initial schema.
-- Clean baseline for fresh installations: no legacy/Go history tables, explicit
-- ownership (user_id NOT NULL with FK), lease columns for the durable job system.

CREATE TABLE users (
    id TEXT PRIMARY KEY,
    email TEXT NOT NULL,
    normalized_email TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL DEFAULT 'user',
    status TEXT NOT NULL DEFAULT 'active',
    email_verified INTEGER NOT NULL DEFAULT 0,
    email_verified_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sessions (
    id TEXT PRIMARY KEY,
    token_hash TEXT NOT NULL UNIQUE,
    user_id TEXT NOT NULL,
    expires_at DATETIME NOT NULL,
    last_activity_at DATETIME,
    revoked_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE password_reset_tokens (
    id TEXT PRIMARY KEY,
    token_hash TEXT NOT NULL UNIQUE,
    user_id TEXT NOT NULL,
    expires_at DATETIME NOT NULL,
    used_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE email_verification_tokens (
    id TEXT PRIMARY KEY,
    token_hash TEXT NOT NULL UNIQUE,
    user_id TEXT NOT NULL,
    expires_at DATETIME NOT NULL,
    used_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE videos (
    id TEXT PRIMARY KEY,
    original_path TEXT NOT NULL,
    original_name TEXT,
    file_size INTEGER,
    output_path TEXT,
    status TEXT NOT NULL,
    progress REAL DEFAULT 0,
    operations TEXT,
    duration REAL,
    width INTEGER,
    height INTEGER,
    error TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    user_id TEXT NOT NULL,
    encrypted INTEGER NOT NULL DEFAULT 0,
    enc_key TEXT,
    output_encrypted INTEGER NOT NULL DEFAULT 0,
    output_enc_key TEXT,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE jobs (
    id TEXT PRIMARY KEY,
    video_id TEXT NOT NULL,
    video_name TEXT,
    source_path TEXT NOT NULL,
    operations TEXT NOT NULL,
    status TEXT NOT NULL,
    progress REAL NOT NULL DEFAULT 0,
    output_path TEXT,
    error TEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at DATETIME,
    finished_at DATETIME,
    cancel_requested_at DATETIME,
    user_id TEXT NOT NULL,
    encrypted INTEGER NOT NULL DEFAULT 0,
    enc_key TEXT,
    execution_token TEXT,
    attempt INTEGER NOT NULL DEFAULT 0,
    worker_id TEXT,
    FOREIGN KEY (video_id) REFERENCES videos(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE timeline_projects (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    name TEXT NOT NULL,
    project TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_users_normalized_email ON users(normalized_email);
CREATE INDEX idx_sessions_user_id ON sessions(user_id);
CREATE INDEX idx_sessions_token_hash ON sessions(token_hash);
CREATE INDEX idx_password_reset_user_id ON password_reset_tokens(user_id);
CREATE INDEX idx_password_reset_token_hash ON password_reset_tokens(token_hash);
CREATE INDEX idx_email_verification_user_id ON email_verification_tokens(user_id);
CREATE INDEX idx_email_verification_token_hash ON email_verification_tokens(token_hash);
CREATE INDEX idx_videos_status ON videos(status);
CREATE INDEX idx_videos_user_id ON videos(user_id);
CREATE INDEX idx_jobs_status ON jobs(status);
CREATE INDEX idx_jobs_video ON jobs(video_id);
CREATE INDEX idx_jobs_created ON jobs(created_at);
CREATE INDEX idx_jobs_user_id ON jobs(user_id);
CREATE INDEX idx_jobs_execution_token ON jobs(execution_token);
CREATE UNIQUE INDEX idx_jobs_one_active_per_video
    ON jobs(video_id)
    WHERE status IN ('queued', 'processing', 'cancel_requested');
CREATE INDEX idx_timeline_projects_user_id ON timeline_projects(user_id);
