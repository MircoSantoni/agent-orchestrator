alter table organization add column owner_sub text;

-- Existing organizations inherit the first project administrator, when one exists.
update organization o set owner_sub = (
  select pm.user_sub from project p join project_member pm on pm.project_id=p.id
  where p.organization_id=o.id and pm.role='ADMIN'
  order by p.created_at, pm.created_at limit 1
);
