-- Persist the per-dashboard build/edit chat thread (the left-panel conversation
-- in the dashboard workspace) so it survives a session refresh. One thread per
-- saved dashboard, stored as a JSON array of {role,text} messages.
ALTER TABLE saved_dashboards ADD COLUMN chat_messages TEXT;
