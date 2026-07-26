-- Chat-app seed data — runs after all qhorus schema migrations (V1–V41+).

INSERT INTO channel (id, name, description, semantic, paused, auto_created, tenancy_id, created_at, last_activity_at)
VALUES ('550e8400-e29b-41d4-a716-446655440001', 'general', 'General discussion', 'APPEND', false, false, 'chat-app', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO channel (id, name, description, semantic, paused, auto_created, tenancy_id, created_at, last_activity_at)
VALUES ('550e8400-e29b-41d4-a716-446655440002', 'engineering', 'Engineering team', 'APPEND', false, false, 'chat-app', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO channel (id, name, description, semantic, paused, auto_created, tenancy_id, created_at, last_activity_at)
VALUES ('550e8400-e29b-41d4-a716-446655440003', 'design', 'Design discussions', 'APPEND', false, false, 'chat-app', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO channel (id, name, description, semantic, paused, auto_created, tenancy_id, created_at, last_activity_at)
VALUES ('550e8400-e29b-41d4-a716-446655440004', 'random', 'Water cooler', 'APPEND', false, false, 'chat-app', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO topic (channel_id, name, resolved, tenancy_id, created_at)
VALUES ('550e8400-e29b-41d4-a716-446655440001', 'general', false, 'chat-app', CURRENT_TIMESTAMP);
INSERT INTO topic (channel_id, name, resolved, tenancy_id, created_at)
VALUES ('550e8400-e29b-41d4-a716-446655440002', 'general', false, 'chat-app', CURRENT_TIMESTAMP);
INSERT INTO topic (channel_id, name, resolved, tenancy_id, created_at)
VALUES ('550e8400-e29b-41d4-a716-446655440003', 'general', false, 'chat-app', CURRENT_TIMESTAMP);
INSERT INTO topic (channel_id, name, resolved, tenancy_id, created_at)
VALUES ('550e8400-e29b-41d4-a716-446655440004', 'general', false, 'chat-app', CURRENT_TIMESTAMP);
