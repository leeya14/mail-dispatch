CREATE TABLE IF NOT EXISTS mail_job (
  id VARCHAR(36) PRIMARY KEY,
  request_key VARCHAR(64) NOT NULL UNIQUE,
  fingerprint VARCHAR(64) NOT NULL,
  recipient VARCHAR(254) NOT NULL,
  subject VARCHAR(200) NOT NULL,
  body TEXT NOT NULL,
  scheduled_at TIMESTAMP(6) NOT NULL,
  next_attempt_at TIMESTAMP(6) NOT NULL,
  status VARCHAR(24) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE IF NOT EXISTS mail_attempt (
  id VARCHAR(36) PRIMARY KEY,
  job_id VARCHAR(36) NOT NULL,
  attempt_no INT NOT NULL,
  started_at TIMESTAMP(6) NOT NULL,
  finished_at TIMESTAMP(6),
  outcome VARCHAR(24) NOT NULL,
  error_code VARCHAR(80),
  CONSTRAINT fk_attempt_job FOREIGN KEY (job_id) REFERENCES mail_job(id),
  CONSTRAINT uk_attempt UNIQUE (job_id, attempt_no)
);
