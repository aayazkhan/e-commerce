# Migration release gate

Before production apply, attach:

- Flyway version list and checksum review;
- expand/contract compatibility note;
- `EXPLAIN`/lock/index plan;
- duration on fresh and production-like data;
- replication/storage headroom;
- restart/failure behavior;
- reconciliation and rollback/forward plan.

The application currently runs Flyway during service startup. If migration duration or locking is material, move execution to a one-shot, approval-gated migration job before rollout. Never assume an application image rollback can undo a schema change.
