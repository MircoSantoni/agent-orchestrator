create table organization (
  id uuid primary key default gen_random_uuid(), name text not null, slug text not null unique,
  created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create table project (
  id uuid primary key default gen_random_uuid(), organization_id uuid not null references organization(id),
  name text not null, slug text not null, repository_url text, default_branch text,
  created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
  unique(organization_id, slug)
);
create table project_member (
  project_id uuid not null references project(id), user_sub text not null, display_name text not null,
  created_at timestamptz not null default now(), primary key(project_id, user_sub)
);
create table workspace (
  id uuid primary key default gen_random_uuid(), project_id uuid not null references project(id),
  owner_id text not null, owner_display_name text not null, name text not null, hostname text not null, os text,
  status text not null default 'ONLINE', last_heartbeat_at timestamptz not null default now(),
  created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
  unique(project_id, owner_id, name)
);
create index workspace_project_status_idx on workspace(project_id, status);
create table orchestrator (
  id uuid primary key default gen_random_uuid(), workspace_id uuid not null references workspace(id),
  name text not null, type text not null, model text, status text not null default 'ACTIVE',
  last_heartbeat_at timestamptz not null default now(), metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
  unique(workspace_id, name)
);
create table task (
  id uuid primary key default gen_random_uuid(), project_id uuid not null references project(id),
  parent_task_id uuid references task(id), title text not null, description text not null default '',
  status text not null default 'BACKLOG', owner_workspace_id uuid references workspace(id),
  executor_agent_id uuid, required_capabilities jsonb not null default '[]'::jsonb,
  created_by text not null, created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
  started_at timestamptz, completed_at timestamptz
);
create index task_project_status_idx on task(project_id, status);
create index task_executor_idx on task(executor_agent_id);
create table agent (
  id uuid primary key default gen_random_uuid(), orchestrator_id uuid not null references orchestrator(id),
  external_id text not null, name text not null, role text, status text not null default 'IDLE', model text,
  current_task_id uuid references task(id), capabilities jsonb not null default '[]'::jsonb,
  metadata jsonb not null default '{}'::jsonb, last_heartbeat_at timestamptz not null default now(),
  created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
  unique(orchestrator_id, external_id)
);
alter table task add constraint task_executor_fk foreign key(executor_agent_id) references agent(id);
create index agent_orchestrator_status_idx on agent(orchestrator_id, status);
create index agent_task_idx on agent(current_task_id);
create table task_dependency (
  id uuid primary key default gen_random_uuid(), task_id uuid not null references task(id),
  depends_on_task_id uuid not null references task(id), created_at timestamptz not null default now(),
  unique(task_id, depends_on_task_id), check(task_id <> depends_on_task_id)
);
create table resource_intent (
  id uuid primary key default gen_random_uuid(), project_id uuid not null references project(id),
  workspace_id uuid not null references workspace(id), agent_id uuid not null references agent(id),
  task_id uuid references task(id), resource_type text not null, resource_path text not null,
  intent_type text not null, status text not null default 'ACTIVE', lease_until timestamptz not null,
  created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create index resource_intent_lookup_idx on resource_intent(project_id, resource_path, status, lease_until);
create table context_entry (
  id uuid primary key default gen_random_uuid(), project_id uuid not null references project(id),
  type text not null, title text not null, content text not null, status text not null,
  created_by_workspace_id uuid references workspace(id), created_by_agent_id uuid references agent(id),
  approved_by text, approved_at timestamptz, related_task_id uuid references task(id),
  metadata jsonb not null default '{}'::jsonb, created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index context_entry_filter_idx on context_entry(project_id, type, status);
create table agent_message (
  id uuid primary key default gen_random_uuid(), project_id uuid not null references project(id),
  from_workspace_id uuid not null references workspace(id), from_agent_id uuid references agent(id),
  to_workspace_id uuid references workspace(id), to_agent_id uuid references agent(id),
  type text not null, task_id uuid references task(id), subject text, body text not null,
  context_refs jsonb not null default '[]'::jsonb, metadata jsonb not null default '{}'::jsonb,
  status text not null default 'PENDING', created_at timestamptz not null default now(),
  read_at timestamptz, accepted_task_id uuid references task(id)
);
create index agent_message_inbox_idx on agent_message(to_workspace_id, to_agent_id, status);
create table activity_event (
  id bigserial primary key, event_id uuid not null unique default gen_random_uuid(),
  project_id uuid not null references project(id), workspace_id uuid references workspace(id),
  agent_id uuid references agent(id), task_id uuid references task(id), type text not null,
  summary text not null, payload jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);
create index activity_event_project_id_idx on activity_event(project_id, id);
