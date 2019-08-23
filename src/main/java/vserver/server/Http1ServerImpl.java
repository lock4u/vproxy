package vserver.server;

import vjson.JSON;
import vproxy.app.Application;
import vproxy.connection.BindServer;
import vproxy.connection.NetEventLoop;
import vproxy.http.HttpContext;
import vproxy.http.HttpProtocolHandler;
import vproxy.processor.http1.entity.Chunk;
import vproxy.processor.http1.entity.Header;
import vproxy.processor.http1.entity.Request;
import vproxy.processor.http1.entity.Response;
import vproxy.protocol.ProtocolHandlerContext;
import vproxy.protocol.ProtocolServerConfig;
import vproxy.protocol.ProtocolServerHandler;
import vproxy.selector.SelectorEventLoop;
import vproxy.util.ByteArray;
import vserver.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.stream.Collectors;

import static vserver.HttpMethod.*;

public class Http1ServerImpl implements HttpServer {
    private static class RouteEntry {
        final List<HttpMethod> methods;
        final Route route;
        final RoutingHandler handler;

        private RouteEntry(HttpMethod[] methods, Route route, RoutingHandler handler) {
            this.methods = Arrays.asList(methods);
            this.route = route;
            this.handler = handler;
        }
    }

    private boolean started = false;
    private final List<RouteEntry> routes = new LinkedList<>();
    private NetEventLoop loop;

    public Http1ServerImpl() {
        this(null);
    }

    public Http1ServerImpl(NetEventLoop loop) {
        this.loop = loop;
    }

    @Override
    public HttpServer handle(HttpMethod[] methods, Route route, RoutingHandler handler) {
        if (started) {
            throw new IllegalStateException("This http server is already started");
        }
        routes.add(new RouteEntry(methods, route, handler));
        return this;
    }

    @Override
    public void listen(InetSocketAddress addr) throws IOException {
        if (started) {
            throw new IllegalStateException("This http server is already started");
        }
        started = true;
        routes.add(new RouteEntry(ALL_METHODS, Route.create("/"), this::handle404));

        initLoop();

        ProtocolServerHandler.apply(loop, BindServer.create(addr),
            new ProtocolServerConfig().setInBufferSize(4096).setOutBufferSize(4096),
            new HttpProtocolHandler(true) {
                @Override
                protected void request(ProtocolHandlerContext<HttpContext> ctx) {
                    handle(ctx);
                }
            });
    }

    private void handle404(RoutingContext ctx) {
        ctx.response()
            .status(404)
            .end(ByteArray.from(("Cannot GET " + ctx.uri() + "\r\n").getBytes()));
    }

    private void initLoop() throws IOException {
        if (loop != null) {
            return;
        }
        loop = new NetEventLoop(SelectorEventLoop.open());
        loop.getSelectorEventLoop().loop(Thread::new);
    }

    private void sendResponse(ProtocolHandlerContext<HttpContext> _pctx, Response response) {
        if (response.headers == null) {
            response.headers = new ArrayList<>(2);
            // may add
            // x-powered-by
            // content-length
        }
        boolean needAddServerId = true;
        for (Header h : response.headers) {
            if (h.key.equalsIgnoreCase("x-powered-by")) {
                needAddServerId = false;
                break;
            }
        }
        if (needAddServerId) {
            response.headers.add(new Header("X-Powered-By", "vproxy/" + Application.VERSION));
        }
        // fill in default values
        if (response.version == null) {
            response.version = "HTTP/1.1";
        }
        if (response.statusCode == 0) {
            response.statusCode = 200;
            response.reason = "OK";
        }
        if (response.body != null) {
            response.headers.add(new Header("Content-Length", Integer.toString(response.body.length())));
        }
        _pctx.write(response.toByteArray().toJavaArray());
    }

    private void handle(ProtocolHandlerContext<HttpContext> _pctx) {
        Request request = _pctx.data.result;
        RoutingContext[] ctx = new RoutingContext[1];
        {
            final HttpMethod method;
            final Map<String, String> headers = new HashMap<>();
            final String uri = request.uri;
            final ByteArray body;
            final HttpResponse response;
            final HandlerChain chain;

            final List<String> paths;
            { // paths
                String path = uri;
                if (path.contains("?")) {
                    path = path.substring(0, path.indexOf('?'));
                }
                paths = Arrays.stream(path.split("/")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
            }

            { // method
                try {
                    method = HttpMethod.valueOf(request.method);
                } catch (RuntimeException e) {
                    Response resp = new Response();
                    resp.statusCode = 400;
                    resp.reason = "Bad Request";
                    resp.body = ByteArray.from("Bad Request: invalid method\r\n".getBytes());
                    sendResponse(_pctx, resp);
                    return;
                }
            }
            { // uri
                if (!uri.startsWith("/")) {
                    Response resp = new Response();
                    resp.statusCode = 400;
                    resp.reason = "Bad Request";
                    resp.body = ByteArray.from("Bad Request: invalid uri\r\n".getBytes());
                    sendResponse(_pctx, resp);
                    return;
                }
            }
            { // headers
                if (request.headers != null) {
                    for (Header h : request.headers) {
                        headers.put(h.key.toLowerCase(), h.value);
                    }
                }
                if (request.trailers != null) {
                    for (Header h : request.trailers) {
                        headers.put(h.key.toLowerCase(), h.value);
                    }
                }
            }
            { // body
                if (request.body != null) {
                    body = request.body;
                } else if (request.chunks != null) {
                    ByteArray foo = null;
                    for (Chunk c : request.chunks) {
                        if (foo == null) {
                            foo = c.content;
                        } else {
                            foo = foo.concat(c.content);
                        }
                    }
                    body = foo;
                } else {
                    body = null;
                }
            }
            { // response
                response = new HttpResponse() {
                    Response response = new Response();
                    boolean isEnd = false;

                    @Override
                    public HttpResponse status(int code, String msg) {
                        if (isEnd) {
                            throw new IllegalStateException("This response is already ended");
                        }
                        response.statusCode = code;
                        response.reason = msg;
                        return this;
                    }

                    @Override
                    public HttpResponse header(String key, String value) {
                        if (isEnd) {
                            throw new IllegalStateException("This response is already ended");
                        }
                        if (response.headers == null) {
                            response.headers = new LinkedList<>();
                        }
                        response.headers.add(new Header(key, value));
                        return this;
                    }

                    @Override
                    public void end(JSON.Instance inst) {
                        header("Content-Type", "application/json");
                        String ua = headers.get("user-agent");
                        if (ua != null && ua.startsWith("curl/")) {
                            // use pretty
                            end(inst.pretty() + "\r\n");
                        } else {
                            end(inst.stringify());
                        }
                    }

                    @Override
                    public void end(ByteArray body) {
                        if (isEnd) {
                            throw new IllegalStateException("This response is already ended");
                        }
                        isEnd = true;
                        response.body = body;
                        sendResponse(_pctx, response);
                    }
                };
            }
            { // chain
                chain = new HandlerChain() {
                    int idx = 0;

                    @Override
                    public void next() {
                        int[] idx = {this.idx};
                        RoutingHandler handler = nextHandler(ctx[0], paths, idx);
                        this.idx = idx[0] + 1;
                        handler.accept(ctx[0]);
                    }
                };
            }

            // build ctx
            ctx[0] = new RoutingContext(method, uri, headers, body, response, chain);
        }
        ctx[0].next();
    }

    private RoutingHandler nextHandler(RoutingContext ctx, List<String> paths, int[] index) {
        routeEntryLoop:
        for (int i = index[0]; i < routes.size(); i++) {
            RouteEntry entry = routes.get(i);
            if (!entry.methods.contains(ctx.method())) {
                continue;
            }
            Route r = entry.route;
            int idx = 0;
            while (r != null) {
                if (idx >= paths.size()) {
                    continue routeEntryLoop;
                }
                String p = paths.get(idx);
                if (!r.match(p)) {
                    continue routeEntryLoop;
                }
                r.fill(ctx, p);
                r = r.next();
                ++idx;
            }
            index[0] = i;
            return entry.handler;
        }
        throw new IllegalStateException("should not reach here");
    }
}