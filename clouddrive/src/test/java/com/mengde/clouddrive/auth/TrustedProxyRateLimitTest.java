package com.mengde.clouddrive.auth;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.RemoteIpValve;
import org.apache.catalina.valves.ValveBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.web.embedded.EmbeddedWebServerFactoryCustomizerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual Boot config + embedded Tomcat + real limiter; no database, SMTP or Redis server. */
class TrustedProxyRateLimitTest {
    @Test
    void trustedLocalProxyGivesDistinctClientsIndependentLimits() throws Exception {
        try (ServletWebServerApplicationContext context = start("deploy")) {
            assertEquals("127.0.0.1", context.getEnvironment().getProperty("server.address"));
            HttpResponse<String> first = get(context, "203.0.113.10");
            assertEquals(200, first.statusCode());
            assertEquals("203.0.113.10", first.body());
            assertEquals(429, get(context, "203.0.113.10").statusCode());
            HttpResponse<String> other = get(context, "203.0.113.11");
            assertEquals(200, other.statusCode());
            assertEquals("203.0.113.11", other.body());
        }
    }

    @Test
    void defaultDirectModeIgnoresSpoofedForwardingHeaders() throws Exception {
        try (ServletWebServerApplicationContext context = start("proxy-test-direct")) {
            HttpResponse<String> first = get(context, "203.0.113.20");
            assertEquals(200, first.statusCode());
            assertEquals("127.0.0.1", first.body());
            // Changing every forwarding header cannot buy another permit in direct mode.
            assertEquals(429, get(context, "203.0.113.21").statusCode());
        }
    }

    @Test
    void deployValveDoesNotTrustPublicOrPrivateDirectPeers() throws Exception {
        try (ServletWebServerApplicationContext context = start("deploy")) {
            RemoteIpValve configured = Arrays.stream(((TomcatWebServer) context.getWebServer())
                            .getTomcat().getEngine().getPipeline().getValves())
                    .filter(RemoteIpValve.class::isInstance).map(RemoteIpValve.class::cast)
                    .findFirst().orElseThrow();
            // Exercise the real valve with synthetic socket peers: deployment itself binds loopback,
            // so public/private peers cannot establish a real connection to that listener.
            RemoteIpValve valve = new RemoteIpValve();
            valve.setInternalProxies(configured.getInternalProxies());
            valve.setRemoteIpHeader(configured.getRemoteIpHeader());
            valve.setProtocolHeader(configured.getProtocolHeader());
            valve.setTrustedProxies(configured.getTrustedProxies());
            EmailRequestRateLimiter limiter = context.getBean(EmailRequestRateLimiter.class);
            for (String peer : List.of("198.51.100.9", "10.1.2.3", "192.168.1.8", "127.0.0.2")) {
                assertEquals(peer + ":http", invoke(valve, limiter, peer, "203.0.113.30"));
                ResponseStatusException rejected = assertThrows(ResponseStatusException.class,
                        () -> invoke(valve, limiter, peer, "203.0.113.31"));
                assertEquals(429, rejected.getStatusCode().value());
            }
            // IPv6 loopback is explicitly supported, not all private IPv6 addresses.
            assertEquals("203.0.113.32:https", invoke(valve, limiter, "::1", "203.0.113.32"));
            assertEquals("203.0.113.33:https", invoke(valve, limiter, "127.0.0.1",
                    "198.51.100.100, 203.0.113.33"));
        }
    }

    private static ServletWebServerApplicationContext start(String profile) {
        SpringApplication app = new SpringApplication(ProbeApp.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        List<String> arguments = new ArrayList<>(List.of(
                "--spring.config.location=" + Path.of("src/main/resources/application.yml").toAbsolutePath().toUri(),
                "--spring.profiles.active=" + profile,
                "--server.port=0",
                "--spring.main.banner-mode=off", "--spring.main.log-startup-info=false",
                "--logging.level.root=WARN"));
        // Deploy must get its loopback binding from the shipped config, not this test override.
        if (!profile.equals("deploy")) arguments.add("--server.address=127.0.0.1");
        return (ServletWebServerApplicationContext) app.run(arguments.toArray(String[]::new));
    }

    private static HttpResponse<String> get(ServletWebServerApplicationContext context, String ip)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + context.getWebServer().getPort() + "/probe"))
                .timeout(Duration.ofSeconds(10)).header("X-Forwarded-For", ip)
                .header("X-Real-IP", ip).header("Forwarded", "for=" + ip)
                .header("X-Forwarded-Proto", "https").GET().build();
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String invoke(RemoteIpValve valve, EmailRequestRateLimiter limiter,
                                 String peer, String claimedIp) throws Exception {
        org.apache.coyote.Request coyote = new org.apache.coyote.Request();
        coyote.scheme().setString("http");
        coyote.serverName().setString("localhost");
        coyote.setServerPort(8080);
        coyote.getMimeHeaders().addValue("X-Forwarded-For").setString(claimedIp);
        coyote.getMimeHeaders().addValue("X-Forwarded-Proto").setString("https");
        Request request = new Request(new Connector());
        request.setCoyoteRequest(coyote);
        request.setRemoteAddr(peer);
        request.setRemoteHost(peer);
        AtomicReference<String> actual = new AtomicReference<>();
        valve.setNext(new ValveBase() {
            @Override public void invoke(Request req, Response response) {
                actual.set(req.getRemoteAddr() + ":" + req.getScheme());
                limiter.requirePermit(req.getRemoteAddr());
            }
        });
        valve.invoke(request, new Response());
        return actual.get();
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class,
            EmbeddedWebServerFactoryCustomizerAutoConfiguration.class})
    static class ProbeApp {
        @Bean EmailRequestRateLimiter limiter() {
            // Only the shared counter transport is simulated; use the actual hashing/bucket logic.
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            Map<String, AtomicLong> counts = new ConcurrentHashMap<>();
            when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                    .thenAnswer(call -> counts.computeIfAbsent(((List<String>) call.getArgument(1)).get(0),
                            key -> new AtomicLong()).incrementAndGet());
            return new EmailRequestRateLimiter(redis, 1, 600);
        }

        @Bean ServletRegistrationBean<HttpServlet> probe(EmailRequestRateLimiter limiter) {
            return new ServletRegistrationBean<>(new HttpServlet() {
                @Override protected void doGet(HttpServletRequest request, HttpServletResponse response)
                        throws IOException {
                    try {
                        limiter.requirePermit(request.getRemoteAddr());
                        response.getWriter().write(request.getRemoteAddr());
                    } catch (ResponseStatusException rejected) {
                        response.setStatus(rejected.getStatusCode().value());
                    }
                }
            }, "/probe");
        }
    }
}
