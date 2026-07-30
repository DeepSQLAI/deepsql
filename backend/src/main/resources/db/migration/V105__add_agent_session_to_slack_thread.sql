-- Slack → DeepSQL Agent groundwork (Path A, phase 1).
--
-- A Slack thread already maps to a chat id (chat_id) for the legacy in-backend
-- ChatService. The new agent path reuses an agent session per thread for
-- server-side context continuity; store its id separately so both brains can be
-- selected via the slack.brain flag without colliding.
ALTER TABLE slack_thread_session
    ADD COLUMN IF NOT EXISTS agent_session_id VARCHAR(64);
