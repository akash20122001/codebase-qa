-- Change github_id column from BIGINT to VARCHAR(255)
-- This prevents potential overflow issues with large GitHub user IDs

ALTER TABLE users 
ALTER COLUMN github_id TYPE VARCHAR(255) 
USING github_id::VARCHAR;

-- Update the unique constraint to work with VARCHAR
ALTER TABLE users 
DROP CONSTRAINT IF EXISTS uk_users_github_id;

ALTER TABLE users 
ADD CONSTRAINT uk_users_github_id UNIQUE (github_id);
