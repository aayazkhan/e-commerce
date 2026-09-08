-- Bootstraps one role + database per service for the local docker-compose stack,
-- matching the names in .env.example, .env.phase6.example, .env.phase7.example,
-- backend/.env.phase4.example, and backend/.env.phase5.example exactly.
-- Auth is `trust` (see docker-compose.yml) so passwords are irrelevant locally;
-- roles still must exist for HikariCP to connect as the configured username.

-- Phase 1-3: one role per service, matching *_DATABASE_USERNAME in .env.example.
create role identity login;
create role category login;
create role catalog login;
create role pricing login;
create role media login;
create role search login;

create database commerce owner identity;
create database identity owner identity;
create database category owner category;
create database catalog owner catalog;
create database pricing owner pricing;
create database media owner media;
create database search owner search;

-- Phase 4-7: shared `commerce` role, matching *_DATABASE_USERNAME=commerce in the
-- phase4/5/6/7 env examples.
create role commerce login;

create database inventory owner commerce;
create database cart owner commerce;
create database wishlist owner commerce;
create database promotion owner commerce;

create database "order" owner commerce;
create database payment owner commerce;
create database shipping owner commerce;
create database refund owner commerce;
create database checkout owner commerce;

create database notification_db owner commerce;
create database review_db owner commerce;
create database recommendation_db owner commerce;
create database analytics_db owner commerce;

create database commerce_admin owner commerce;
create database commerce_seller owner commerce;
create database commerce_cms owner commerce;
create database commerce_audit owner commerce;
create database commerce_flags owner commerce;
