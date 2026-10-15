"use strict";

// Explicit operator repair for the reviewed 26-pool/revision-28 export; never run on app startup.
const fs = require("node:fs");
const crypto = require("node:crypto");
const assert = require("node:assert/strict");
const AUDIT = "recruitment_duplicate_pool_repairs";
const alive = (row) => row.deleted_at == null;
const reference = (pool) => (pool.mapped_snapshot ?? pool.snapshot).catalog_pool_id;
const sum = (values) => values.reduce((total, value) => total + Number(value ?? 0), 0);

function summary(source) {
  return {
    pools: source.pools.length,
    liveEvents: source.events.filter(alive).length,
    liveBatches: source.batches.filter(alive).length,
    total: Number(source.baseline) + sum(source.pools.map((p) => p.progress))
      + sum(source.events.filter((e) => alive(e) && e.batch_id == null).map((e) => e.pull_span))
      + sum(source.batches.filter(alive).map((b) => b.total_pull_count)),
  };
}

function buildPlan(source) {
  assert.equal(source.schema, "yuanhub.recruitment.v1", "不是招募完整备份");
  assert.match(source.source_account.account_id, /^acc_[a-zA-Z0-9_-]+$/);
  assert.equal(source.archive_revision, 28, "只接受已确认的 revision 28；数据更新后请重新核对");
  assert.equal(source.game, "如鸢");
  assert.equal(source.events.length, 85);
  assert.equal(source.batches.length, 20);
  assert.equal(source.temporary_agents.length, 0);
  assert.equal(source.current_pool_id, null);
  assert.equal(source.baseline, 0);
  assert.deepEqual(summary(source), { pools: 26, liveEvents: 76, liveBatches: 20, total: 2673 });
  for (const [rows, key] of [[source.pools, "pool_id"], [source.events, "event_id"], [source.batches, "batch_id"]]) {
    assert.equal(new Set(rows.map((row) => row[key])).size, rows.length, `重复 ${key}`);
  }
  assert(source.events.every((e) => e.acquired_date == null), "出现未核对的出货日期");
  const groups = [];
  for (const canonical of source.pools.filter((p) => p.pool_id.startsWith("catalog:"))) {
    const alias = source.pools.find((p) => p.pool_id === canonical.pool_id.slice(8));
    if (!alias) continue;
    assert.equal(reference(canonical), alias.pool_id);
    assert.equal(reference(alias), alias.pool_id);
    assert.equal(canonical.mapped_snapshot, null);
    assert.equal(alias.mapped_snapshot, null);
    assert.deepEqual(canonical.wish_agent_ids, []);
    assert.deepEqual(alias.wish_agent_ids, []);
    assert(alias.progress == null || alias.progress === 0, "别名进度未经确认");
    const manual = source.events.filter((e) => e.pool_id === canonical.pool_id);
    const imported = source.events.filter((e) => e.pool_id === alias.pool_id);
    const batches = source.batches.filter((b) => b.pool_id === alias.pool_id);
    assert.equal(batches.length, 1);
    const batch = batches[0];
    assert(alive(batch));
    assert(batch.import_batch_id);
    assert(imported.length > 0);
    assert(imported.every((e) => e.batch_id === batch.batch_id && e.import_batch_id === batch.import_batch_id));
    assert(manual.every((e) => e.batch_id == null));
    const identity = (event) => {
      const slot = canonical.snapshot.up_agents.find((agent) => agent.id === event.agent_snapshot.agent_id);
      const operatorId = slot?.operator_id ?? event.agent_snapshot.agent_id;
      assert(canonical.snapshot.up_agents.some((agent) => agent.operator_id === operatorId));
      assert.equal(event.agent_snapshot.rarity, 5);
      assert.equal(event.agent_snapshot.temporary, false);
      return JSON.stringify([operatorId, event.pull_span, event.up_status]);
    };
    const redundant = manual.some(alive);
    if (redundant) {
      assert.deepEqual(manual.filter(alive).map(identity).sort(), imported.map(identity).sort(), "两份历史不匹配");
      assert.equal(sum(manual.filter(alive).map((e) => e.pull_span)) + Number(canonical.progress ?? 0), batch.total_pull_count);
    } else {
      assert(imported.every((e) => !alive(e)), "无手工历史时不能删除仍有效的导入记录");
      assert.equal(canonical.progress, 0);
    }
    groups.push({
      name: canonical.snapshot.name,
      alias: alias.pool_id,
      canonical: canonical.pool_id,
      batchId: batch.batch_id,
      deleteBatch: redundant,
      deleteEvents: redundant ? imported.filter(alive).map((e) => e.event_id) : [],
      removedDraws: redundant ? batch.total_pull_count : 0,
    });
  }
  assert.equal(groups.length, 6);
  assert.equal(groups.filter((g) => g.deleteBatch).length, 5);
  assert.equal(sum(groups.map((g) => g.deleteEvents.length)), 7);
  assert.equal(sum(groups.map((g) => g.removedDraws)), 333);
  return { groups, before: summary(source) };
}

function transform(source, now = new Date()) {
  const plan = buildPlan(source);
  const after = JSON.parse(JSON.stringify(source));
  const byAlias = new Map(plan.groups.map((g) => [g.alias, g]));
  after.archive_revision += 1;
  after.exported_at = now.toISOString();
  after.pools = after.pools.filter((p) => !byAlias.has(p.pool_id));
  for (const event of after.events) {
    const group = byAlias.get(event.pool_id);
    if (!group) continue;
    event.pool_id = group.canonical;
    event.updated_at = now.toISOString();
    if (group.deleteEvents.includes(event.event_id)) {
      event.deleted_at = now.toISOString();
      event.deleted_revision = after.archive_revision;
    }
  }
  for (const batch of after.batches) {
    const group = byAlias.get(batch.pool_id);
    if (!group) continue;
    batch.pool_id = group.canonical;
    if (group.deleteBatch) {
      batch.deleted_at = now.toISOString();
      batch.deleted_revision = after.archive_revision;
    }
  }
  assert.deepEqual(summary(after), { pools: 20, liveEvents: 69, liveBatches: 15, total: 2340 });
  assert(after.events.every((e) => after.pools.some((p) => p.pool_id === e.pool_id)));
  assert(after.batches.every((b) => after.pools.some((p) => p.pool_id === b.pool_id)));
  return { ...plan, after, result: summary(after) };
}

// Pool UP display metadata can be refreshed during export; compare persisted business facts instead.
function snapshotView(snapshot, snake) {
  if (snapshot == null) return null;
  return [snapshot.game, snapshot[snake ? "catalog_pool_id" : "catalogPoolId"] ?? null];
}

function normalized(value) {
  if (value == null) return null;
  if (value instanceof Date) return value.getTime();
  if (value._bsontype === "Long" || value._bsontype === "Int32") return Number(value.toString());
  if (Array.isArray(value)) return value.map(normalized);
  if (typeof value === "object") return Object.fromEntries(Object.keys(value).sort().map((k) => [k, normalized(value[k])]));
  return value;
}

function compareFields(expected, actual, fields) {
  for (const snake of fields.split(" ")) {
    const camel = snake.replace(/_([a-z])/g, (_, letter) => letter.toUpperCase());
    const wanted = snake.endsWith("_at") && expected[snake] != null ? new Date(expected[snake]) : expected[snake];
    assert.deepEqual(normalized(actual[camel]), normalized(wanted), `服务器数据不符: ${snake}`);
  }
}

function assertMatches(source, stored) {
  const archive = stored.archive;
  compareFields(source, archive, "archive_revision baseline current_pool_id");
  assert.equal(archive.gameSnapshot, source.game);
  assert.deepEqual(normalized(archive.temporaryAgents), []);
  assert.equal(archive.pools.length, source.pools.length);
  source.pools.forEach((pool, index) => {
    const actual = archive.pools[index];
    compareFields(pool, actual, "pool_id progress wish_agent_ids");
    assert.deepEqual(snapshotView(actual.snapshot, false), snapshotView(pool.snapshot, true));
    assert.deepEqual(snapshotView(actual.mappedSnapshot, false), snapshotView(pool.mapped_snapshot, true));
  });
  for (const [rows, expected, id] of [[stored.events, source.events, "event"], [stored.batches, source.batches, "batch"]]) {
    assert.equal(rows.length, expected.length, `服务器 ${id} 数量已变化`);
    const byId = new Map(rows.map((row) => [row[`${id}Id`], row]));
    assert.equal(byId.size, rows.length);
    for (const row of expected) {
      const actual = byId.get(row[`${id}_id`]);
      assert(actual, `服务器缺少 ${id} 记录`);
      compareFields(row, actual, `${id}_id pool_id created_at deleted_at deleted_revision import_batch_id imported_at`);
      if (id === "batch") compareFields(row, actual, "total_pull_count");
      else {
        compareFields(row, actual, "pull_span sort_order up_status acquired_date note source batch_id updated_at");
        compareFields(row.agent_snapshot, actual.agentSnapshot, "agent_id temporary rarity");
        assert.deepEqual(snapshotView(actual.poolSnapshot, false), snapshotView(row.pool_snapshot, true));
      }
    }
  }
}

async function readStored(database, owner) {
  const archives = await (await database.getCollection("recruitment_archives").find(owner)).limit(2).toArray();
  assert.equal(archives.length, 1, "找不到唯一的目标档案");
  assert.equal(typeof archives[0].userId, "string");
  const scope = { userId: archives[0].userId, accountId: archives[0].accountId };
  return {
    archive: archives[0],
    events: await (await database.getCollection("recruitment_events").find(scope)).limit(86).toArray(),
    batches: await (await database.getCollection("recruitment_batches").find(scope)).limit(21).toArray(),
  };
}

async function fence(database, owner, game) {
  const result = await database.getCollection("sub_accounts").updateOne({ ...owner, game }, { $inc: { recruitmentFence: 1 } });
  assert.equal(result.matchedCount, 1, "子账号所有者或游戏已变化");
}

async function transaction(database, action) {
  const session = database.getMongo().startSession();
  try {
    session.startTransaction({ readConcern: { level: "snapshot" }, writeConcern: { w: "majority" } });
    const result = await action(session.getDatabase(database.getName()));
    await session.commitTransaction();
    return result;
  } catch (error) {
    await session.abortTransaction();
    throw error;
  } finally {
    await session.endSession();
  }
}

async function repair(database, source, { apply = false } = {}) {
  const now = new Date();
  const plan = transform(source, now);
  const accountId = source.source_account.account_id;
  const repairId = `duplicate-pools:${accountId}:${source.archive_revision}`;
  const sourceHash = crypto.createHash("sha256").update(JSON.stringify(source)).digest("hex");
  return transaction(database, async (tx) => {
    const existing = await tx.getCollection(AUDIT).findOne({ _id: repairId });
    if (existing) {
      assert.equal(existing.sourceHash, sourceHash, "同修复编号的源备份不同");
      assert(!existing.rolledBackAt, "已回滚，拒绝重新使用旧修复计划");
      return { status: "already_applied", repairId, before: plan.before, after: plan.result };
    }
    const before = await readStored(tx, { accountId });
    const owner = { userId: before.archive.userId, accountId };
    assert.equal(typeof owner.userId, "string");
    assertMatches(source, before);
    const accounts = await (await tx.getCollection("sub_accounts").find(owner)).limit(2).toArray();
    assert.equal(accounts.length, 1);
    assert.equal(accounts[0].game, source.game);
    const report = { status: apply ? "applied" : "dry_run", repairId, before: plan.before, after: plan.result, groups: plan.groups };
    if (!apply) return report;
    await fence(tx, owner, source.game);
    assert(Buffer.byteLength(JSON.stringify(before), "utf8") < 8 * 1024 * 1024, "原始档案过大，停止修复");
    // Raw BSON is retained in the same transaction; rollback never depends on the lossy JSON export.
    await tx.getCollection(AUDIT).insertOne({ _id: repairId, ...owner, sourceHash, before, afterRevision: 29, appliedAt: now });
    const aliases = new Map(plan.groups.map((g) => [g.alias, g]));
    for (const event of before.events) {
      const group = aliases.get(event.poolId);
      if (!group) continue;
      const changes = { poolId: group.canonical, updatedAt: now };
      if (group.deleteEvents.includes(event.eventId)) Object.assign(changes, { deletedAt: now, deletedRevision: 29 });
      const result = await tx.getCollection("recruitment_events").updateOne({ ...owner, _id: event._id }, { $set: changes });
      assert.equal(result.matchedCount, 1);
    }
    for (const batch of before.batches) {
      const group = aliases.get(batch.poolId);
      if (!group) continue;
      const changes = { poolId: group.canonical };
      if (group.deleteBatch) Object.assign(changes, { deletedAt: now, deletedRevision: 29 });
      const result = await tx.getCollection("recruitment_batches").updateOne({ ...owner, _id: batch._id }, { $set: changes });
      assert.equal(result.matchedCount, 1);
    }
    const result = await tx.getCollection("recruitment_archives").updateOne({ ...owner, _id: before.archive._id, archiveRevision: 28 }, {
      $set: { pools: before.archive.pools.filter((p) => !aliases.has(p.poolId)), archiveRevision: 29, updatedAt: now },
    });
    assert.equal(result.matchedCount, 1, "档案并发更新，停止修复");
    assertMatches(plan.after, await readStored(tx, owner));
    return report;
  });
}

async function rollback(database, repairId) {
  return transaction(database, async (tx) => {
    const audit = await tx.getCollection(AUDIT).findOne({ _id: repairId });
    assert(audit, "找不到修复备份");
    if (audit.rolledBackAt) return { status: "already_rolled_back", repairId };
    const owner = { userId: audit.userId, accountId: audit.accountId };
    const current = await readStored(tx, owner);
    assert.equal(Number(current.archive.archiveRevision), audit.afterRevision, "修复后已有新操作，禁止覆盖回滚");
    assert.equal(current.events.length, audit.before.events.length);
    assert.equal(current.batches.length, audit.before.batches.length);
    await fence(tx, owner, audit.before.archive.gameSnapshot);
    for (const [collection, rows] of [["recruitment_events", audit.before.events], ["recruitment_batches", audit.before.batches]]) {
      for (const row of rows) {
        const result = await tx.getCollection(collection).replaceOne({ ...owner, _id: row._id }, row);
        assert.equal(result.matchedCount, 1);
      }
    }
    const now = new Date();
    const revision = audit.afterRevision + 1;
    const result = await tx.getCollection("recruitment_archives").replaceOne({ ...owner, _id: current.archive._id, archiveRevision: audit.afterRevision }, {
      ...audit.before.archive, archiveRevision: revision, updatedAt: now,
    });
    assert.equal(result.matchedCount, 1);
    await tx.getCollection(AUDIT).updateOne({ _id: repairId }, { $set: { rolledBackAt: now, rollbackRevision: revision } });
    return { status: "rolled_back", repairId, archiveRevision: revision };
  });
}

function readSource(filename) {
  const content = fs.readFileSync(filename, "utf8");
  assert(Buffer.byteLength(content, "utf8") <= 5 * 1024 * 1024, "源备份超过 5 MiB");
  return JSON.parse(content);
}

module.exports = { AUDIT, summary, buildPlan, transform, assertMatches, repair, rollback, readSource };
