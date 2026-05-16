package io.columnar.viz;

import io.columnar.core.ColumnarSlice;
import io.columnar.core.RowRange;
import io.columnar.core.Schema;
import io.columnar.core.Table;
import io.columnar.core.TableVisualizer;
import io.columnar.core.Viewport;

import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AG Grid Community in the browser with the <b>infinite row model</b>: the grid requests only row
 * windows via {@code GET /api/rows}. Full AG Grid Server-Side Row Model (enterprise) is separate;
 * infinite + {@code /api/rows} is the Community equivalent.
 *
 * <p>Served with <b>Eclipse Vert.x</b>: SSE on {@code /stream}, optional polling with {@code GET
 * /api/version}.
 */
public final class AgGridTableVisualizer implements TableVisualizer {

    private static final Logger log = LoggerFactory.getLogger(AgGridTableVisualizer.class);

    /** Hard cap on rows returned by a single {@code /api/rows} request (abuse + payload size). */
    public static final int MAX_ROWS_PER_REQUEST = 2_000;

    @Override
    public void show(Table table, Viewport viewport) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(viewport, "viewport");

        Vertx vertx = Vertx.vertx();
        Router router = mountRoutes(vertx, table, viewport);
        HttpServer server = vertx.createHttpServer();
        server.requestHandler(router);

        CompletableFuture<Integer> portCf = new CompletableFuture<>();
        server.listen(0, "127.0.0.1")
                .onComplete(
                        ar -> {
                            if (ar.succeeded()) {
                                portCf.complete(ar.result().actualPort());
                            } else {
                                portCf.completeExceptionally(ar.cause());
                            }
                        });
        final int port;
        try {
            port = portCf.get(45, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            closeVertxQuietly(vertx);
            throw new IllegalStateException("interrupted binding HTTP server", ie);
        } catch (ExecutionException | TimeoutException e) {
            closeVertxQuietly(vertx);
            Throwable c = e instanceof ExecutionException ? e.getCause() : e;
            throw new IllegalStateException("failed to bind HTTP server", c);
        }

        String url = "http://127.0.0.1:" + port + '/';
        log.info("viz server listening {}", url);
        log.info(
                "routes: / (html), /api/meta, /api/version, /api/rows (max {} rows/block), /stream "
                        + "(invalidate SSE)",
                MAX_ROWS_PER_REQUEST);
        log.info(
                "backing table: {}, size={}, version={}",
                table.getClass().getSimpleName(),
                table.size(),
                table.version());
        if (!(table instanceof IrDv01WideBookTable)) {
            log.warn(
                    "table is not IrDv01WideBookTable — /stream will not push invalidations; grid "
                            + "may not auto-refresh on ticks");
        }

        System.out.println("AG Grid viewer (infinite datasource): " + url);
        System.out.println(
                "/api/rows returns at most "
                        + MAX_ROWS_PER_REQUEST
                        + " rows per request — only visible blocks load.");

        openBrowser(url);

        CountDownLatch stopped = new CountDownLatch(1);
        Runnable release = stopped::countDown;
        Runtime.getRuntime().addShutdownHook(new Thread(release, "columnar-viz-release"));

        Thread.ofVirtual()
                .name("columnar-viz-stdin")
                .start(
                        () -> {
                            try {
                                BufferedReader br =
                                        new BufferedReader(
                                                new InputStreamReader(
                                                        System.in, StandardCharsets.UTF_8));
                                String line = br.readLine();
                                if (line != null) {
                                    System.out.println("Stopping server…");
                                    release.run();
                                } else {
                                    System.out.println(
                                            "stdin reached EOF immediately (non-interactive). "
                                                    + "Keeping the server alive — use Ctrl+C or "
                                                    + "stop the run configuration to quit.");
                                }
                            } catch (IOException ignored) {
                                // ignore
                            }
                        });

        if (System.console() != null) {
            System.out.println("Press Enter here to stop the server…");
        }

        try {
            stopped.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            release.run();
        } finally {
            shutdownVertx(vertx);
        }
    }

    private static void closeVertxQuietly(Vertx vertx) {
        vertx.close();
    }

    private static void shutdownVertx(Vertx vertx) {
        CountDownLatch done = new CountDownLatch(1);
        vertx.close()
                .onComplete(
                        ar -> {
                            if (ar.failed()) {
                                log.warn("Vert.x shutdown: {}", ar.cause().toString());
                            }
                            done.countDown();
                        });
        try {
            boolean finished = done.await(45, TimeUnit.SECONDS);
            if (!finished) {
                log.warn("Vert.x close did not complete within timeout");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static Router mountRoutes(Vertx vertx, Table table, Viewport baseVp) {
        Router router = Router.router(vertx);
        router.get("/").handler(rc -> serveIndex(rc));
        router.get("/api/meta").handler(rc -> pollMeta(rc, table));
        router.get("/api/version").handler(rc -> pollVersion(rc, table));
        router.get("/api/rows").blockingHandler(rc -> blockingRows(rc, table, baseVp), false);
        router.get("/stream").handler(rc -> streamInvalidate(rc, table));
        return router;
    }

    private static boolean isGet(RoutingContext rc) {
        return rc.request().method() == HttpMethod.GET;
    }

    private static void serveIndex(RoutingContext rc) {
        if (!isGet(rc)) {
            rc.response().setStatusCode(405).end();
            return;
        }
        byte[] html = loadHtmlBytes();
        rc.response()
                .setStatusCode(200)
                .putHeader(HttpHeaders.CONTENT_TYPE, "text/html; charset=UTF-8")
                .end(Buffer.buffer(html));
    }

    private static void pollMeta(RoutingContext rc, Table table) {
        if (!isGet(rc)) {
            rc.response().setStatusCode(405).end();
            return;
        }
        String q = rc.request().query();
        String gb = queryString(q, "groupBy");
        if (gb != null && "book".equalsIgnoreCase(gb.trim()) && table instanceof IrDv01WideBookTable iw) {
            String json = SliceJson.metaJson(iw.aggregateSchema(), iw.aggregateRowCount());
            log.debug(
                    "GET /api/meta (book aggregate) -> lastRow={} cols={}",
                    iw.aggregateRowCount(), iw.aggregateSchema().size());
            sendUtf8Json(rc, 200, json);
            return;
        }
        String json = SliceJson.metaJson(table.schema(), table.size());
        log.debug("GET /api/meta -> lastRow={} cols={}", table.size(), table.schema().size());
        sendUtf8Json(rc, 200, json);
    }

    private static void pollVersion(RoutingContext rc, Table table) {
        if (!isGet(rc)) {
            rc.response().setStatusCode(405).end();
            return;
        }
        String json = "{\"version\":" + table.version() + '}';
        log.debug("GET /api/version -> {}", table.version());
        sendUtf8Json(rc, 200, json);
    }

    private static void blockingRows(RoutingContext rc, Table table, Viewport baseVp) {
        if (!isGet(rc)) {
            rc.response().setStatusCode(405).end();
            return;
        }

        String q = rc.request().query();
        long rev = queryLong(q, "rev", -1L);
        long startRow = Math.max(queryLong(q, "startRow", 0), 0);
        String gb = queryString(q, "groupBy");
        boolean bookAgg =
                gb != null && "book".equalsIgnoreCase(gb.trim()) && table instanceof IrDv01WideBookTable;

        log.debug(
                "GET /api/rows startHint={} rev={} query={} remote={} bookAgg={}",
                startRow,
                rev,
                q,
                rc.request().remoteAddress(),
                bookAgg);

        long logicalLastExclusive =
                bookAgg ? IrDv01WideBookTable.BOOK_COUNT : table.size();

        if (startRow >= logicalLastExclusive) {
            Schema emptySchema =
                    bookAgg
                            ? ((IrDv01WideBookTable) table).aggregateSchema()
                            : table.schema();
            ColumnarSlice empty = ColumnarSlice.empty(emptySchema, table.version());
            sendUtf8Json(rc, 200, SliceJson.infiniteBlock(empty, logicalLastExclusive));
            return;
        }
        long defaultSpan =
                Math.min(MAX_ROWS_PER_REQUEST, Math.max(0L, logicalLastExclusive - startRow));
        long endRow = queryLong(q, "endRow", startRow + defaultSpan);
        if (endRow <= startRow) {
            sendUtf8Json(rc, 400, "{\"error\":\"endRow must be greater than startRow\"}");
            return;
        }

        long span = endRow - startRow;
        if (span > MAX_ROWS_PER_REQUEST) {
            endRow = startRow + MAX_ROWS_PER_REQUEST;
        }

        RowRange fetched =
                RowRange.of(startRow, endRow).intersect(RowRange.head(logicalLastExclusive));
        RowRange clipped = fetched.intersect(baseVp.rows());

        Viewport.Builder vb = Viewport.builder().rows(clipped);
        baseVp.columns().ifPresent(vb::columns);
        baseVp.order().ifPresent(vb::order);
        if (baseVp.hasLimit()) {
            vb.limit(baseVp.limit().getAsLong());
        }

        ColumnarSlice slice =
                bookAgg
                        ? ((IrDv01WideBookTable) table).readAggregatedByBook(vb.build())
                        : table.read(vb.build());

        String body = SliceJson.infiniteBlock(slice, logicalLastExclusive);
        log.debug(
                "/api/rows DONE start={} endRequest={} clippedRange=[{}, {}) sliceRows={} "
                        + "tableVer={} sliceVer={} clientRev={} jsonChars={} bookAgg={}",
                startRow,
                endRow,
                clipped.from(),
                clipped.to(),
                slice.rowCount(),
                table.version(),
                slice.version(),
                rev,
                body.length(),
                bookAgg);

        sendUtf8Json(rc, 200, body);
    }

    /**
     * SSE: tick callbacks only enqueue onto the originating event-loop with {@link
     * Context#runOnContext(Handler)} before touching {@link HttpServerResponse}.
     */
    private static void streamInvalidate(RoutingContext rc, Table table) {
        if (!isGet(rc)) {
            rc.response().setStatusCode(405).end();
            return;
        }

        HttpServerResponse resp =
                rc.response()
                        .setChunked(true)
                        .setStatusCode(200)
                        .putHeader(HttpHeaders.CONTENT_TYPE, "text/event-stream; charset=UTF-8")
                        .putHeader(HttpHeaders.CACHE_CONTROL, "no-store")
                        .putHeader(HttpHeaders.CONNECTION, "keep-alive");

        resp.write(Buffer.buffer("retry: 2000\n\n"));

        log.info(
                "/stream SSE client connected {}, table={}",
                rc.request().remoteAddress(),
                table.getClass().getSimpleName());

        AtomicBoolean sseClosed = new AtomicBoolean(false);
        AtomicReference<Runnable> detachListeners = new AtomicReference<>(() -> {});

        if (table instanceof IrDv01WideBookTable iw) {
            Context eventLoopCtx = Vertx.currentContext();
            Objects.requireNonNull(eventLoopCtx, "Vert.x context");

            AtomicReference<Consumer<Long>> holder = new AtomicReference<>();

            Consumer<Long> listener =
                    ver -> {
                        if (sseClosed.get()) {
                            return;
                        }
                        eventLoopCtx.runOnContext(
                                (Handler<Void>)
                                        idle ->
                                                pushSseInvalidateFrame(resp, ver, sseClosed));
                    };
            holder.set(listener);

            detachListeners.set(
                    () -> {
                        sseClosed.set(true);
                        Consumer<Long> l = holder.get();
                        if (l != null) {
                            iw.removeListener(l);
                        }
                    });

            iw.addListener(listener);
            pushSseInvalidateFrame(resp, iw.version(), sseClosed);

            HttpConnection conn = rc.request().connection();
            conn.closeHandler(v -> sseSessionEnded(resp, detachListeners.get(), sseClosed));

            resp.exceptionHandler(
                    err -> {
                        log.debug("/stream SSE response exception {}", err.toString());
                        sseClosed.set(true);
                        detachListeners.get().run();
                    });

            rc.request().resume();
            return;
        }

        log.warn(
                "/stream opened but table is {}; no SSE invalidates will run",
                table.getClass().getName());

        HttpConnection conn = rc.request().connection();
        conn.closeHandler(
                v -> {
                    if (!resp.ended()) {
                        resp.end();
                    }
                });
        resp.write(Buffer.buffer(": wait\n\n"));
        rc.request().resume();
    }

    private static void sseSessionEnded(
            HttpServerResponse resp, Runnable detachListeners, AtomicBoolean sseClosed) {
        sseClosed.set(true);
        detachListeners.run();
        if (!resp.ended()) {
            resp.end();
        }
    }

    /** Must run on the event-loop associated with {@code resp}. */
    private static void pushSseInvalidateFrame(
            HttpServerResponse resp, long version, AtomicBoolean sseClosed) {
        if (sseClosed.get() || resp.ended()) {
            return;
        }
        try {
            resp.write(invalidateFrame(version));
            log.debug("SSE wrote invalidate payload version={}", version);
        } catch (RuntimeException ex) {
            log.warn("SSE write failed version {}: {}", version, ex.toString());
            sseClosed.set(true);
        }
    }

    private static Buffer invalidateFrame(long version) {
        String json = "{\"kind\":\"invalidate\",\"version\":" + version + '}';
        return Buffer.buffer("event: invalidate\ndata: " + json + "\n\n");
    }

    private static void sendUtf8Json(RoutingContext rc, int status, String json) {
        Buffer body = Buffer.buffer(json.getBytes(StandardCharsets.UTF_8));
        rc.response()
                .setStatusCode(status)
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=UTF-8")
                .putHeader(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate")
                .putHeader("Pragma", "no-cache")
                .end(body);
    }

    private static long queryLong(String query, String key, long missingDefault) {
        if (query == null || query.isEmpty()) {
            return missingDefault;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = decode(pair.substring(0, eq));
            if (!key.equals(k)) {
                continue;
            }
            String v = decode(pair.substring(eq + 1));
            try {
                return Long.parseLong(v);
            } catch (NumberFormatException ignored) {
                return missingDefault;
            }
        }
        return missingDefault;
    }

    /** @return decoded value, or {@code null} when {@code key} is absent from the raw query string */
    private static String queryString(String query, String key) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = decode(pair.substring(0, eq));
            if (!key.equals(k)) {
                continue;
            }
            return decode(pair.substring(eq + 1));
        }
        return null;
    }

    private static String decode(String raw) {
        return java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    private static void openBrowser(String url) {
        if (!Desktop.isDesktopSupported()) {
            return;
        }
        Desktop d = Desktop.getDesktop();
        if (!d.isSupported(Desktop.Action.BROWSE)) {
            return;
        }
        try {
            d.browse(java.net.URI.create(url));
        } catch (Exception e) {
            System.err.println("Could not open browser: " + e.getMessage());
        }
    }

    private static byte[] loadHtmlBytes() {
        try (InputStream in = AgGridTableVisualizer.class.getResourceAsStream("ag-grid-sse.html")) {
            if (in == null) {
                return "<h1>missing ag-grid-sse.html</h1>".getBytes(StandardCharsets.UTF_8);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            return ("<h1>" + e + "</h1>").getBytes(StandardCharsets.UTF_8);
        }
    }
}
