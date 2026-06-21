-- ===============================================================
-- PostgreSQL renew script (Oracle-style object cleanup)
-- ===============================================================
-- Usage:
-- 1) Connect to target database.
-- 2) Optionally set target_schema below.
-- 3) Run the whole DO block.

DO $$
DECLARE
  -- Change this if you want a specific schema instead of current_schema().
  target_schema text := current_schema();
  r record;
BEGIN
  RAISE NOTICE 'Start renew schema: %', target_schema;

  -- MATERIALIZED VIEWS
  FOR r IN
    SELECT schemaname, matviewname
    FROM pg_matviews
    WHERE schemaname = target_schema
  LOOP
    EXECUTE format(
      'DROP MATERIALIZED VIEW IF EXISTS %I.%I CASCADE',
      r.schemaname,
      r.matviewname
    );
  END LOOP;

  -- VIEWS
  FOR r IN
    SELECT schemaname, viewname
    FROM pg_views
    WHERE schemaname = target_schema
  LOOP
    EXECUTE format(
      'DROP VIEW IF EXISTS %I.%I CASCADE',
      r.schemaname,
      r.viewname
    );
  END LOOP;

  -- TABLES
  FOR r IN
    SELECT schemaname, tablename
    FROM pg_tables
    WHERE schemaname = target_schema
  LOOP
    EXECUTE format(
      'DROP TABLE IF EXISTS %I.%I CASCADE',
      r.schemaname,
      r.tablename
    );
  END LOOP;

  -- SEQUENCES
  FOR r IN
    SELECT schemaname, sequencename
    FROM pg_sequences
    WHERE schemaname = target_schema
  LOOP
    EXECUTE format(
      'DROP SEQUENCE IF EXISTS %I.%I CASCADE',
      r.schemaname,
      r.sequencename
    );
  END LOOP;

  -- TRIGGERS (non-internal)
  FOR r IN
    SELECT n.nspname AS schemaname,
         c.relname AS tablename,
         t.tgname  AS trigger_name
    FROM pg_trigger t
    JOIN pg_class c ON c.oid = t.tgrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = target_schema
      AND NOT t.tgisinternal
  LOOP
    EXECUTE format(
      'DROP TRIGGER IF EXISTS %I ON %I.%I CASCADE',
      r.trigger_name,
      r.schemaname,
      r.tablename
    );
  END LOOP;

  -- FUNCTIONS / PROCEDURES
  FOR r IN
    SELECT n.nspname AS schemaname,
         p.proname,
         p.prokind,
         pg_get_function_identity_arguments(p.oid) AS identity_args
    FROM pg_proc p
    JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = target_schema
      AND p.prokind IN ('f', 'p')
  LOOP
    IF r.prokind = 'p' THEN
      EXECUTE format(
        'DROP PROCEDURE IF EXISTS %I.%I(%s) CASCADE',
        r.schemaname,
        r.proname,
        r.identity_args
      );
    ELSE
      EXECUTE format(
        'DROP FUNCTION IF EXISTS %I.%I(%s) CASCADE',
        r.schemaname,
        r.proname,
        r.identity_args
      );
    END IF;
  END LOOP;

  -- USER-DEFINED TYPES (domains/enums/range/composite/multirange)
  FOR r IN
    SELECT n.nspname AS schemaname,
         t.typname
    FROM pg_type t
    JOIN pg_namespace n ON n.oid = t.typnamespace
    WHERE n.nspname = target_schema
      AND t.typtype IN ('d', 'e', 'r', 'm', 'c')
      AND t.typrelid = 0
        AND left(t.typname, 1) <> '_'
  LOOP
    EXECUTE format(
      'DROP TYPE IF EXISTS %I.%I CASCADE',
      r.schemaname,
      r.typname
    );
  END LOOP;

  -- Oracle PACKAGE equivalent does not exist in PostgreSQL.
  -- Oracle recycle bin / PURGE RECYCLEBIN does not exist in PostgreSQL.

  RAISE NOTICE 'Renew completed for schema: %', target_schema;
END
$$;
