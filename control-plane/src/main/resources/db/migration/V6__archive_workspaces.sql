ALTER TABLE workspace ADD COLUMN deleted_at timestamptz;
ALTER TABLE workspace DROP CONSTRAINT workspace_project_id_owner_id_name_key;
CREATE UNIQUE INDEX workspace_active_identity_idx
    ON workspace(project_id, owner_id, name) WHERE deleted_at IS NULL;
