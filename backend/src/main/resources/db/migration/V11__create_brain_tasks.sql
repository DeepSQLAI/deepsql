create table if not exists brain_tasks (
    id varchar(36) primary key,
    connection_id varchar(255) not null,
    task_type varchar(50) not null,
    status varchar(20) not null,
    summary text not null,
    table_name varchar(255),
    table_schema varchar(255),
    table_label varchar(255),
    bcnf_score integer,
    issues_json text,
    suggestions_json text,
    created_by varchar(255),
    created_at timestamp not null,
    updated_at timestamp
);

create index if not exists idx_brain_tasks_connection on brain_tasks (connection_id);
create index if not exists idx_brain_tasks_status on brain_tasks (status);
create index if not exists idx_brain_tasks_created on brain_tasks (created_at);
