-- HubSpot/CRM ticket ingestion removed in V1 — DeepSQL is database-workflows only.
-- Drop tables created by V60 and V62.

DROP TABLE IF EXISTS ticket_sync_state;
DROP TABLE IF EXISTS support_tickets;
DROP TABLE IF EXISTS crm_connections;
