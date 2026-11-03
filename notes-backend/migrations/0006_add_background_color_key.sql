-- Migration 0006: Add background_color_key to notes table
ALTER TABLE notes ADD COLUMN background_color_key TEXT NOT NULL DEFAULT 'default';
