# Synthetic migration fixtures

`v1` is the fixture format version, independent of app releases and the encrypted WSB1 envelope
version. Every value is synthetic; no device dump, real tag, user backup, or credential is accepted.

The [migration testing guide](../../../../../docs/testing/migrations.md#fixture-format-and-provenance)
records the source tag/commit, represented fields, purpose, expected result, and rollback behavior
for each case. `alpha1.json` and `alpha2-current.json` use historically verified field shapes;
`mixed.json`, `dormant.json`, `duration-unbound.json`, and `malformed.json` are explicitly synthetic
compatibility or corruption probes. A serializable historical type does not prove historical UI use.

Each file wraps `fixtureVersion`, `kind`, `source`, `preferences`, and optional `rawOverrides`.
The JVM and Android copies must remain byte-identical; `FixtureCatalogTest` checks the catalog.
Collection/object preferences ending in `_json` are stored as JSON strings, retention as an
integer, and other values as strings. `rawOverrides` represents exact malformed stored strings.

To add a case, verify historical source or label it synthetic, add both fixture copies, update the
catalog, and test the production migration/store/repository. Specify whether it succeeds or rejects
atomically, assert retained state and authorization, and use Android close/reload coverage for disk
behavior. Change the fixture version only when the fixture format changes incompatibly.

Raw synthetic UID inputs are allowed only to exercise conversion. Never dump raw preferences in
assertion failures, reports, diagnostics, screenshots, or exports. Expected protected output must
contain no raw identifiers. Generate encrypted test envelopes ephemerally with production
randomness and KDF settings; never commit backups or replace production cryptography to obtain
deterministic tests. See the guide for safe isolated-store, coroutine-scope, and Keystore cleanup.

`MigrationEnforcementAcceptanceTest` is the enabled Android gate for runtime blocking after a
migration failure, and it passes through production behavior: the failure is reported, the engine
fails closed, and `StorageRecoveryScreen` provides the retry/approval route. See the guide; this
corpus still does not prove signed in-place package upgrades (MIG-001B) or general corrupt-state
handling (DATA-001).
