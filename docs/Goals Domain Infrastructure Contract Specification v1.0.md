# Goals Domain Infrastructure Contract Specification v1.0

**Status:** Official architectural contract

**Scope:** Xpendz / Goals domain

**Type:** Conceptual, platform-neutral, DDD infrastructure contract

---

## 0. Purpose

This document defines the definitive architectural contract between the **Goals domain** and its **infrastructure**.

It is intentionally **conceptual** and **platform-independent**. It does not prescribe Java, Kotlin, Room, SQLite, Firebase, or any concrete implementation technique.

This contract exists to guarantee that the Goals domain can be implemented consistently across Desktop, Android, local storage, cloud synchronization, backups, and future platforms without changing business semantics.

---

## 1. Normative language

The following terms are normative:

- **MUST**: required by contract.
- **MUST NOT**: forbidden by contract.
- **SHOULD**: recommended unless a justified exception exists.
- **MAY**: optional and non-normative.

If a future implementation conflicts with this document, **this document takes precedence**.

---

## 2. Architectural boundaries

### 2.1 Domain boundary

The Goals domain owns:

- `Goal` as Aggregate Root.
- Value Objects.
- Domain state transitions.
- Domain events.
- Invariants.
- Business meaning of progress, terminality, and archival.

### 2.2 Infrastructure boundary

Infrastructure owns:

- Persistence formats.
- Rehydration mechanics.
- Snapshot storage.
- Event and transaction storage.
- Version conflict detection.
- Synchronization transport.
- Legacy adaptation.

### 2.3 Prohibited boundary violations

Infrastructure MUST NOT:

- invent business state;
- bypass aggregate invariants;
- mutate the aggregate without loading it first;
- emit business events on behalf of the domain;
- reinterpret terminal states;
- silently discard conflicts;
- treat snapshots as source of truth.

---

## 3. Contract of `GoalRepository`

This section defines the conceptual repository contract for the Goals aggregate.

### 3.1 Responsibility

The repository is the infrastructure contract responsible for:

- loading a Goal aggregate;
- rehydrating the aggregate from durable data;
- persisting the aggregate atomically with its version;
- persisting associated domain events and related history;
- storing and retrieving snapshots as optimization artifacts;
- enforcing optimistic concurrency;
- protecting aggregate integrity across local and synchronized environments.

### 3.2 Repository duties

The repository MUST:

- return either a fully reconstructed Goal or an explicit absence result;
- preserve the aggregate identity exactly as persisted;
- restore the aggregate version exactly as persisted;
- ensure history is replayed in the correct order;
- detect stale writes before overwrite;
- preserve terminal states once committed;
- keep archived state separate from business status;
- reject partial writes that would leave the aggregate inconsistent.

### 3.3 Repository inputs

The repository MAY receive:

- a Goal identifier;
- a persistence version or concurrency token;
- an aggregate snapshot;
- an ordered event stream;
- an associated transaction stream;
- history cursors;
- synchronization metadata.

The repository MUST NOT require the caller to supply raw internal domain state that can only be known by the aggregate itself.

### 3.4 Repository outputs

The repository MAY return:

- a reconstructed Goal aggregate;
- an explicit not-found result;
- a version conflict result;
- a persistence acknowledgement;
- a snapshot cursor or history cursor;
- a reconstruction failure when the durable data is inconsistent.

The repository MUST NOT return a partially reconstructed aggregate as if it were valid.

### 3.5 Permitted operations

The repository MAY conceptually support:

- load by identity;
- save current aggregate state;
- append event history;
- persist transaction history linked to the aggregate;
- store snapshot;
- load snapshot;
- query history for reconstruction or audit;
- compare versions for conflict detection.

### 3.6 Prohibited operations

The repository MUST NOT:

- alter business rules;
- decide whether a goal completes or cancels;
- create events from external assumptions;
- normalize terminal states beyond the aggregate contract;
- silently repair invalid data;
- downgrade terminal states to active states;
- erase event history as a normal write path;
- overwrite a newer version with an older one;
- merge conflicting histories without explicit semantic rules.

### 3.7 Integrity protection

The repository protects aggregate integrity by requiring:

- identity consistency;
- version consistency;
- ordered history consistency;
- atomic persistence of state and history when required;
- rejection of stale or contradictory writes;
- preservation of terminality.

---

## 4. Rehydration of the Aggregate

### 4.1 Definition

Rehydration means reconstructing a Goal from persisted durable representation so that the aggregate regains its last valid business state without re-running creation semantics.

### 4.2 Creation vs rehydration

Creation and rehydration are not equivalent.

- **Creation** introduces a brand-new aggregate instance and may emit `GoalCreated`.
- **Rehydration** restores an already existing aggregate and MUST NOT emit `GoalCreated` again.

A rehydrated aggregate is the same business entity; it is not a new one.

### 4.3 Rehydration sources

A Goal MAY be reconstructed from:

- current persisted state;
- an ordered event history;
- a snapshot plus later history;
- a complete backup set;
- a combined local and remote history, if and only if semantic ordering is preserved.

### 4.4 Rehydration rules

Rehydration MUST:

- preserve the original identity;
- restore the latest valid status;
- restore archived state exactly;
- restore the last known target, date, description, and savings-account linkage;
- restore version metadata;
- avoid re-emitting creation semantics;
- respect terminality already present in history.

Rehydration MUST NOT:

- create a new domain identity;
- reset terminal states;
- infer business facts not supported by durable data;
- drop history order;
- apply duplicate events as new facts;
- silently ignore reconstruction gaps.

### 4.5 Snapshot-based rehydration

When a snapshot exists, rehydration MAY begin from that snapshot and then apply only the later durable facts.

A snapshot is an acceleration mechanism, not a replacement for history.

### 4.6 History-based rehydration

When no valid snapshot exists, rehydration MUST rely on the ordered durable history.

If the ordered history is incomplete or inconsistent, the repository MUST fail the reconstruction instead of producing a misleading aggregate.

### 4.7 Backup restoration

A backup is an external packaging of the same conceptual sources of truth.

A backup MAY restore a Goal if it preserves:

- identity;
- version order;
- terminality;
- event order;
- transaction order;
- snapshot compatibility.

A backup MUST NOT invent missing facts.

### 4.8 Cross-platform compatibility

The rehydration contract MUST remain stable across Desktop, Android, and future platforms.

The durable representation may differ by platform, but the reconstructed business meaning MUST remain identical.

---

## 5. Domain Events Buffer

### 5.1 Definition

The domain events buffer is the transient collection of domain events produced by a Goal after valid state transitions.

It is internal domain memory, not persisted truth.

### 5.2 When an event enters the buffer

An event enters the buffer only when:

- a state transition succeeds;
- the aggregate remains valid after the transition;
- the event represents a real business fact.

Examples:

- creation produces `GoalCreated`;
- completion produces `GoalCompleted`;
- cancellation produces `GoalCancelled`.

### 5.3 When an event leaves the buffer

An event leaves the buffer only after the infrastructure acknowledges successful durable handling.

The contract MUST avoid losing events due to premature clearing.

### 5.4 Who may consume it

Only infrastructure or application-level coordination MAY consume buffered events.

The buffer MUST NOT be consumed by presentation code or by unrelated domain objects.

### 5.5 Clearing rules

The aggregate MAY expose its buffered events as a read-only conceptual collection.

The buffer MUST be cleared only after the infrastructure layer confirms successful persistence or durable publication.

If persistence fails, buffered events MUST remain available for retry or inspection.

### 5.6 Consistency rules

The event buffer MUST:

- preserve event order;
- preserve event identity;
- preserve event-to-goal association;
- remain aligned with the aggregate version;
- prevent duplicate semantic publication unless idempotency is explicitly handled.

### 5.7 Relation to repository

The repository is responsible for consuming buffered events as part of a durable write cycle.

The repository MUST ensure that:

- aggregate state and buffered events are consistent;
- event persistence follows the correct order;
- event publication does not occur without a successful durable write or a defined outbox-like guarantee.

This document does not define an EventBus.

---

## 6. Snapshot of the Aggregate

### 6.1 Definition

A snapshot is a compact representation of the Goal aggregate at a specific version.

It captures the latest complete business state required to accelerate rehydration.

### 6.2 What a snapshot represents

A snapshot represents:

- aggregate identity;
- current version;
- current status;
- archived flag;
- target;
- target date;
- description;
- savings-account association;
- any minimal metadata needed to resume reconstruction.

### 6.3 What a snapshot is not

A snapshot is NOT:

- the source of truth;
- a replacement for history;
- an event log;
- a transaction log;
- a projection with independent business meaning.

### 6.4 When a snapshot may update

A snapshot MAY update only after a valid durable commit and only when doing so improves reconstruction efficiency.

A snapshot SHOULD NOT be treated as mandatory on every write.

### 6.5 Snapshot content restrictions

A snapshot MUST NOT contain:

- raw event history as a substitute for history storage;
- transient buffer contents;
- unrelated aggregate data;
- derived UI-only presentation state;
- speculative future state;
- any business meaning not already validated by the aggregate.

### 6.6 Reconstructing from snapshot

When reconstructing from snapshot, the repository MUST:

- trust the snapshot only up to its own version;
- apply later ordered history after the snapshot version;
- reject snapshot/history mismatches;
- prefer authoritative history when conflicts arise.

### 6.7 Snapshot vs history

- **Snapshot:** optimization.
- **History:** durable narrative of facts.

If they disagree, the contract requires investigation and conflict handling, not silent preference for convenience.

---

## 7. GoalProgress

### 7.1 Definition

GoalProgress is the conceptual measure of how much of the goal target has been achieved.

It is a derived concept and MUST NOT change the business identity of the aggregate.

### 7.2 Official recommendation

The official recommendation is:

- **calculate GoalProgress under demand as the source of truth**;
- **cache it only as an optimization**;
- **store it in snapshots only as a derived convenience**;
- **treat any persisted progress value as disposable if it conflicts with authoritative facts**.

### 7.3 Why this recommendation is official

This model is preferred because it:

- minimizes semantic drift;
- reduces the risk of stale projections;
- remains platform-neutral;
- works across offline and synchronized environments;
- avoids making a derived value authoritative.

### 7.4 Risks of alternative approaches

- **Cache-first:** fast, but can drift from reality.
- **Snapshot-first:** efficient, but can lag behind history.
- **Projection-first:** useful for reads, but dangerous if treated as truth.

### 7.5 Required rule

No cached or projected progress value may override the aggregate's authoritative state.

---

## 8. Versioning of the Aggregate

### 8.1 Conceptual version

The aggregate version is the monotonic revision of the Goal business state.

Each valid state transition MUST advance the version in a strictly ordered way.

### 8.2 Persistence version

The persistence version is the stored form of the conceptual version used to verify that the aggregate being saved is still current.

### 8.3 Conflict detection

A write conflict exists when the persisted version is newer than the version being saved.

In that case, the repository MUST reject the write or force the caller to rehydrate and retry.

### 8.4 Protection against overwrites

The repository MUST prevent stale writes from overwriting newer committed facts.

This protection applies to:

- local concurrency;
- concurrent devices;
- delayed synchronization;
- backup restoration; 
- cross-platform edits.

### 8.5 Offline synchronization

Offline synchronization MUST preserve version order.

When reconnecting, the system MUST compare versions before accepting remote changes.

Remote data MUST NOT overwrite a newer local aggregate.

### 8.6 Version and terminality

Terminal transitions require special protection:

- once completed or cancelled, the aggregate remains terminal;
- no later version may reopen the goal;
- no synchronization event may downgrade terminality.

---

## 9. Concurrency

### 9.1 Optimistic locking

The official concurrency model is optimistic locking.

The repository MUST validate that the aggregate version has not changed before accepting a write.

### 9.2 Concurrent devices

When two devices edit the same Goal:

- the first committed valid transition establishes the next authoritative version;
- later stale writes MUST conflict;
- the system MUST not silently merge incompatible business facts.

### 9.3 Semantic precedence

If concurrent changes compete, the following semantic order applies:

1. **Preserve terminality.**
2. **Preserve already committed facts.**
3. **Reject stale non-terminal rewrites.**
4. **Avoid inventing reconciled state without explicit contract support.**

### 9.4 Incompatible operations

The following operations are semantically incompatible with terminal state:

- completing an already completed goal;
- cancelling an already cancelled goal;
- reactivating a terminal goal;
- applying a creation event to an existing rehydrated aggregate.

### 9.5 Duplicate transitions

Duplicate submissions MAY be treated as idempotent only if identity, version, and event identity make the duplication unambiguous.

Otherwise they MUST be rejected.

---

## 10. Persistence of History

### 10.1 GoalEvent persistence

GoalEvent persistence MUST preserve at least:

- event identity;
- aggregate identity;
- event type;
- timestamp;
- aggregate version or sequence position;
- ordering information.

### 10.2 GoalTransaction persistence

GoalTransaction, when introduced, MUST be persisted as a distinct durable fact because it models monetary movement, not lifecycle state.

It MUST retain its own identity and order relative to the goal timeline.

### 10.3 GoalHistory relation

GoalHistory is the ordered audit timeline that relates:

- goal lifecycle events;
- goal transactions;
- relevant version changes;
- reconstruction checkpoints.

GoalHistory is a historical view, not the authoritative state itself.

### 10.4 Complete reconstruction

A complete domain reconstruction MUST be possible from:

- a valid snapshot, plus
- ordered later events, plus
- ordered later transactions, plus
- version and identity metadata.

If any required segment is missing, reconstruction MUST fail or explicitly report incompleteness.

### 10.5 History integrity

History persistence MUST defend against:

- duplicate event records;
- out-of-order records;
- truncated histories;
- conflicting versions;
- mixed-aggregate contamination.

---

## 11. Integration with the legacy model

### 11.1 Coexistence principle

The legacy Goals model MAY coexist temporarily with the new domain model.

During coexistence, the legacy model MUST be treated as an adapter-facing compatibility shape, not as the final conceptual authority.

### 11.2 Conceptual mapping

The accepted high-level mapping is:

- **Legacy OPEN** → **Domain ACTIVE**
- **Legacy CLOSED** → **Domain COMPLETED or CANCELLED**, only when terminal semantics are recoverable

### 11.3 Semantic limitation of legacy CLOSED

If the legacy layer cannot distinguish completion from cancellation, then `CLOSED` is semantically lossy.

In that case:

- the adapter MUST NOT pretend that the lost meaning was recovered;
- the domain layer MUST preserve the actual terminal meaning if history or metadata provides it;
- otherwise the legacy record is not sufficient as the sole authoritative source for domain semantics.

### 11.4 Temporary adaptation strategy

The contract allows a temporary adapter strategy that:

- reads legacy data into domain meaning only when semantic equivalence is preserved;
- writes back only if the target store can preserve the same meaning;
- rejects mappings that would lose terminal distinctions.

### 11.5 No code migration contract

This document does not define migration code.

It only defines the architectural rule that legacy coexistence must not weaken the new domain semantics.

---

## 12. Edge cases

### 12.1 Restoration from backup

A backup restore MUST rebuild the latest valid business state.

If the backup contains snapshot and history:

- the snapshot may accelerate reconstruction;
- history MUST remain authoritative.

### 12.2 Delayed synchronization

Delayed synchronization MUST not overwrite a newer aggregate version.

Late-arriving records MUST be compared against version and ordering metadata.

### 12.3 Snapshot/history conflict

If a snapshot conflicts with later history, the snapshot MUST be treated as stale or invalid.

History wins because the snapshot is only an optimization.

### 12.4 Conflict between two snapshots

If two snapshots represent the same aggregate but incompatible versions:

- the higher compatible version MAY be considered newer;
- if compatibility cannot be proven, the conflict MUST be rejected.

### 12.5 Partial history loss

If part of the history is missing:

- reconstruction MUST not silently fabricate the missing segment;
- the system SHOULD fail fast;
- the inconsistency MUST be surfaced.

### 12.6 Duplicate events

Duplicate events MUST be handled by event identity and version rules.

They MUST NOT be applied twice as independent facts.

### 12.7 Out-of-order events

Out-of-order events MUST be rejected, reordered only when semantic order can be proven, or isolated as invalid history.

They MUST NOT silently rewrite chronology.

### 12.8 Incomplete reconstruction

Incomplete reconstruction MUST be reported as incomplete.

A partially reconstructed Goal MUST NOT be treated as valid domain truth.

---

## 13. Technical audit

### 13.1 Critical risks

- **No canonical version contract:** without ordered version metadata, reconstruction and synchronization can diverge.
- **Legacy CLOSED ambiguity:** if not guarded, terminal meaning can be lost between completed and cancelled goals.
- **Snapshot treated as truth:** this would corrupt reconstruction under conflict or history loss.

### 13.2 High risks

- **Silent overwrite under offline sync:** stale writes may erase newer facts without optimistic locking.
- **Partial history acceptance:** incomplete histories can produce false business states.
- **Out-of-order replay:** can invert lifecycle facts and damage terminality.
- **Unclear event buffer ownership:** may cause event loss or duplicate publication.

### 13.3 Medium risks

- **GoalProgress drift:** cached progress may become stale if treated as authoritative.
- **Cross-platform temporal mismatch:** different timestamp semantics can break ordering unless normalized.
- **Transaction/history overlap confusion:** goal lifecycle facts and monetary facts must remain distinct.
- **Snapshot refresh policy ambiguity:** overly aggressive snapshotting can add complexity without benefit.

### 13.4 Low risks

- **Naming inconsistencies between platforms.**
- **Different serialization formats for equivalent facts.**
- **UI projections accidentally reused as domain truth.**
- **Minor adapter duplication during coexistence.**

---

## 14. Final verdict

### 14.1 Architectural conclusion

Once this specification is certified, the Goals domain is **architecturally ready** to begin implementation of:

- Repository
- Rehydration
- Snapshot
- Persistence
- Synchronization

without requiring new architecture decisions.

### 14.2 Condition

That readiness is valid only if all future work obeys:

- the existing domain contracts;
- the aggregate boundary;
- terminality preservation;
- optimistic concurrency;
- snapshot-as-optimization only;
- history-as-authoritative-fact;
- no new business states.

### 14.3 Official recommendation

**Approved to proceed.**

The domain is now sufficiently specified for implementation planning and execution, provided the next phase remains strictly inside this contract.

---

## 15. Document authority

This specification is the definitive conceptual infrastructure contract for the Goals domain in Xpendz.

Any implementation, adapter, repository, synchronization rule, or persistence model for Goals MUST conform to this document.
