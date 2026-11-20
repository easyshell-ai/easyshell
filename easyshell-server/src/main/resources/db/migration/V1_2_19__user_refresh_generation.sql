-- Kept independently of sys_user: deletion must not silently reset a generation.
-- User ID reuse and database restore/rollback still require separate prevention.
CREATE TABLE user_refresh_generation (
    user_id BIGINT NOT NULL PRIMARY KEY,
    generation BIGINT NOT NULL,
    CONSTRAINT chk_user_refresh_generation_nonnegative CHECK (generation >= 0)
);
