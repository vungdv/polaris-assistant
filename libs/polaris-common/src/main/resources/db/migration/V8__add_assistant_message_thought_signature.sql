-- V8__add_assistant_message_thought_signature.sql
-- Store model thought signature for reasoning continuity across multi-turn function calls (Gemini 3/2.5)
ALTER TABLE assistant_messages ADD COLUMN thought_signature TEXT;
