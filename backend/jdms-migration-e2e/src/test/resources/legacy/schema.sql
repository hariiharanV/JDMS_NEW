-- Legacy JDMS tables used by the migration (06-legacy-jdms-reference.md §2), the subset of columns the tool reads.
-- PostgreSQL stand-in for the bank's Oracle schema; the tool's SQL is ANSI so it runs on both.
create table dms_folder_setting (
    appln_name     varchar(50) primary key,
    base_folder    varchar(200),
    version_folder varchar(200),
    delete_folder  varchar(200)
);
create table doc_type_master (
    cabenit_name  varchar(50),
    doc_type_id   varchar(50),
    doc_type_desc varchar(200),
    category      varchar(50),
    primary key (cabenit_name, doc_type_id)
);
create table doc_field_details (
    cabenit_name    varchar(50),
    doc_type_id     varchar(50),
    field_name      varchar(100),
    field_id        varchar(50),
    field_label     varchar(200),
    field_mandatory varchar(1),
    field_order     integer,
    field_length    integer
);
create table dms_document_upload (
    document_id     varchar(50) primary key,
    appln_id        varchar(50),
    document_name   varchar(200),
    document_type   varchar(10),
    doc_type        varchar(50),
    doc_version     integer,
    expiry_date     date,
    creation_date   timestamp,
    folderpath      varchar(200),
    file_name       varchar(200),
    brn_cd          varchar(10),
    role_id         varchar(20),
    doc_permission  varchar(10),
    wf_status       varchar(20),
    status          varchar(20),
    customer_id     varchar(50),
    account_no      varchar(50),
    contract_ref_no varchar(50),
    cust_name       varchar(200),
    makerid         varchar(50),
    makedate        timestamp,
    checkerid       varchar(50),
    checkdate       timestamp
);
create table dms_custom_docfield_dts (
    document_id     varchar(50) primary key,
    cust_str_field1 varchar(200),
    cust_str_field2 varchar(200),
    cust_dt_field1  date,
    cust_num_field1 numeric(18, 3)
);
create table wf_workflowhistory (
    history_id  integer primary key,
    document_id varchar(50),
    stage_name  varchar(100),
    action      varchar(30),
    user_id     varchar(50),
    remarks     varchar(500),
    action_date timestamp
);
create table cm_users (
    user_id   varchar(50) primary key,
    user_name varchar(200),
    brn_cd    varchar(10)
);
create table cm_userroles (
    user_id varchar(50),
    role_id varchar(20)
);
create table dms_folder_detail (
    folder_id   integer primary key,
    appln_id    varchar(50),
    folder_name varchar(100),
    folder_path varchar(200),
    role_id     varchar(20),
    permission  varchar(10)
);
