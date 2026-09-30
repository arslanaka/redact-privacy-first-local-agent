-- Who triggered an audit event (the signed-in username forwarded by the frontend proxy).
-- NULL for system events (retention cleanup) and for local runs without sign-in.
ALTER TABLE audit_events ADD COLUMN actor VARCHAR(64);
