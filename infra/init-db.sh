#!/bin/bash
# Database per Service: um schema + um usuario por servico, sem permissao cruzada.
# Executado pelo container do Postgres na primeira inicializacao (docker-entrypoint-initdb.d).
set -e

create_service() {
  local svc="$1" pass="$2"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-SQL
    CREATE USER ${svc} WITH PASSWORD '${pass}';
    CREATE SCHEMA ${svc} AUTHORIZATION ${svc};
    ALTER USER ${svc} SET search_path = ${svc};
    REVOKE ALL ON SCHEMA public FROM ${svc};
SQL
}

create_service catalog      "$CATALOG_DB_PASSWORD"
create_service registration "$REGISTRATION_DB_PASSWORD"
create_service payment      "$PAYMENT_DB_PASSWORD"
create_service notification "$NOTIFICATION_DB_PASSWORD"
