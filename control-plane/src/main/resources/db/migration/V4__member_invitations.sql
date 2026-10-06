alter table project_member add column email text;
alter table project_member add column invitation_status text not null default 'ACTIVE';
create unique index project_member_email_unique on project_member(project_id, lower(email)) where email is not null;
