alter table project_member add column role text not null default 'MEMBER';
update project_member pm set role='ADMIN'
where pm.user_sub = (
  select first_member.user_sub from project_member first_member
  where first_member.project_id=pm.project_id
  order by first_member.created_at, first_member.user_sub limit 1
);
