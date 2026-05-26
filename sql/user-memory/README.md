# user-memory migration

## Purpose

This directory contains PostgreSQL migration scripts for the user memory feature.

Current required script:

- `2026-05-25-add-user-memory-columns.sql`
- `2026-05-25-add-user-memory-candidate-status.sql`

## What it fixes

Adds the missing columns required by the current backend code:

- `user_memory.importance`
- `user_memory.evidence_message_id`
- `user_memory.evidence_text`
- `user_memory_candidate.importance`
- `user_memory_candidate.evidence_message_id`
- `user_memory_candidate.status`

## How to run

Use your normal PostgreSQL execution path. Example:

```powershell
psql -h <host> -U <user> -d <database> -f "D:\coding\Java\project\JchatMind\sql\user-memory\2026-05-25-add-user-memory-columns.sql"
```

Then run:

```powershell
psql -h <host> -U <user> -d <database> -f "D:\coding\Java\project\JchatMind\sql\user-memory\2026-05-25-add-user-memory-candidate-status.sql"
```

## Notes

- The script uses `IF NOT EXISTS`
- It is safe for partially migrated databases
- This repository does not execute the script automatically
- `2026-05-25-add-user-memory-candidate-status.sql` also backfills existing `NULL` status rows to `PERSISTED`
