CREATE TABLE IF NOT EXISTS config_uploads (
  upload_id TEXT PRIMARY KEY,
  update_id TEXT NOT NULL,
  expected_channels INTEGER NOT NULL CHECK (expected_channels BETWEEN 1 AND 10000),
  categories_json TEXT NOT NULL,
  epg_source_encrypted TEXT,
  created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_config_uploads_created_at ON config_uploads(created_at);

CREATE TABLE IF NOT EXISTS config_upload_chunks (
  upload_id TEXT NOT NULL,
  chunk_index INTEGER NOT NULL CHECK (chunk_index >= 0),
  channels_json TEXT NOT NULL,
  channel_count INTEGER NOT NULL CHECK (channel_count BETWEEN 1 AND 80),
  checksum TEXT NOT NULL,
  PRIMARY KEY (upload_id, chunk_index),
  FOREIGN KEY (upload_id) REFERENCES config_uploads(upload_id) ON DELETE CASCADE
);
