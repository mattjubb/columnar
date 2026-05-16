# columnar

High-performance in-memory column-oriented table framework for Java 25.

## Design pillars

1. **Pull, not push.** Computation is triggered by a subscriber asking for a `Viewport` (slice of rows + subset of columns). The engine walks the operator DAG backward and asks each operator only for the upstream slice it actually needs. Anything not requested is not calculated.
2. **Dirty-mark invalidation.** Live source mutations don't push deltas; they bump a version and mark every downstream operator dirty. The next pull recomputes only what's needed for the requested viewport.
3. **Tiered memory.** Column chunks live in HOT (on-heap primitive arrays) or WARM (off-heap `MemorySegment` via the FFM API). A residency manager evicts cold chunks off-heap to keep the GC happy.
4. **One table, two modes.** A single `BaseTable` covers both reference data and live ticking streams: it starts mutable (appendable, monotonic version), and `seal()` freezes it permanently. Use `Table.builder(schema).build()` for sealed reference data; `Table.create(schema)` for an open live table. Derived tables are virtual until pulled.
5. **Codegen for hot paths.** ByteBuddy emits specialized vector kernels per `(expression, column types, residency)` triple. Cached classes are reused across many invocations.

## Modules

| Module     | Purpose                                                                                                                |
| ---------- | ---------------------------------------------------------------------------------------------------------------------- |
| `:core`    | `Schema`, `DataType`, `Column`, `ColumnChunk`, `Table` interface, `Viewport`, `TableSnapshot`.                         |
| `:memory`  | `ChunkResidencyManager`, on-heap and off-heap (`MemorySegment`) chunk implementations, eviction policy.                |
| `:expr`    | Expression AST, interpreter, ByteBuddy codegen (`VectorPredicate`, `VectorProjector`, `AggregateUpdater`).             |
| `:engine`  | Physical operators: `Filter`, `Project`, `HashAggregate`, `HashJoin`, `Pivot`, `OrderBy`. All viewport-aware.          |
| `:query`   | `DependencyGraph`, `DirtyTracker`, `MaterializationCache`, pull executor, `TickCoordinator`.                           |
| `:api`     | Fluent user-facing API. `TableContext`, `Table.builder(...)`, `Table.create(...)`, viewport subscriptions.             |
| `:bench`   | JMH benchmarks.                                                                                                        |
| `:viz`     | `Table.show()` / `TableVisualizer` SPI: local HTTP + Server-Sent Events + AG Grid in the browser for live viewports. |

## AG Grid browser demo (IR DV01 pivot / wide sensitivities)

The demo builds an **`IrDv01WideBookTable`**: logically the same pivoted DV01 cube as `trades` × tenor × `dv01`, but stored **wide** (one DOUBLE column per bucket) so we can mutate **every** trade on each tick.

The browser loads **only scrolled row ranges** via **AG Grid Community’s _infinite row model_**: `GET /api/rows?startRow=…&endRow=…` returns a materialized **`Viewport`** backed by **`Table.read`**. Full Enterprise **Server-Side Row Model** (SSRM) is not bundled; infinite + keyed row requests are the closest supported pattern on Community.

Trade rows expose a **`book`** column (fixed **10** desk labels). Check **“Server rollup by book”** in the toolbar for **server-side aggregation**: **`/api/rows?groupBy=book`** (and **`/api/meta?groupBy=book`**) serves **ten** summed-DV01 rows per book—the **`trades`** id is omitted because it cannot be aggregated like notionals.

SSE on `/stream` sends tiny `{ kind: "invalidate", version }` events when **`IrDv01WideBookTable`** bumps version; the UI calls **`refreshInfiniteCache()`** so cached blocks (~viewport + buffer) reload from **`/api/rows`**.

```bash
./gradlew :viz:run
```

Uses **Eclipse Vert.x** for HTTP (see `viz/build.gradle.kts`). Heap needs are modest (~2–4 GB heap is plenty for `100 000 × 15` doubles). Stop the JVM with **Ctrl+C**, **Stop Run** (IDE), or **Enter** in a real interactive terminal after the banner.

To use **`Table.show(viewport)`** from your own **`Table`**, add `implementation(project(":viz"))`; grid invalidation pings over SSE only propagate automatically for **`IrDv01WideBookTable`** listeners—other **`Table`** types still fetch row windows lazily via **`/api/rows`** whenever the cache refetches after a reload.
## Build

Requires Java 25.

```bash
./gradlew build
```

## Status

Early scaffold. See `.cursor/plans/columnar-reactive-table-framework_*.plan.md` for the design spec.

