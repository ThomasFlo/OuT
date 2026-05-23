-- Enable required PostgreSQL extensions for HomeStock.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- French full-text search configuration is bundled with PostgreSQL as "french".
-- No extra setup needed; tables and indexes are created by the application on startup.
