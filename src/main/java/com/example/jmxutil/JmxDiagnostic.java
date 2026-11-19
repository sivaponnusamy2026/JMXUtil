package com.example.jmxutil;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import javax.management.remote.rmi.RMIConnectorServer;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.rmi.Remote;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JMX connection troubleshooting tool.
 *
 * <p>Designed for the common "I can't connect to JMX on a remote / containerised
 * (Kubernetes, Docker) JVM" problem. Instead of a single opaque
 * {@code java.rmi.ConnectException}, it walks every layer of a default RMI-based
 * JMX connection and reports what passed, what failed, and the most likely fix.
 *
 * <p>The layers it checks, in order:
 * <ol>
 *   <li>DNS resolution of the registry host.</li>
 *   <li>Raw TCP reachability of the RMI registry port.</li>
 *   <li>RMI registry {@code list()} / {@code lookup("jmxrmi")}.</li>
 *   <li>The host:port embedded in the returned RMI stub &mdash; the #1 cause of
 *       container failures (the stub advertises the pod's internal IP, which the
 *       client outside the cluster cannot reach).</li>
 *   <li>Raw TCP reachability of that advertised RMI data port.</li>
 *   <li>The full {@link JMXConnector} handshake (auth / SSL included).</li>
 *   <li>A sanity MBean query (Runtime / Memory / Threads).</li>
 * </ol>
 */
public final class JmxDiagnostic {

    private static final String RESET = "\u001B[0m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String CYAN = "\u001B[36m";
    private static final String BOLD = "\u001B[1m";

    // endpoint:[host:port] inside a UnicastRef stub's toString()
    private static final Pattern ENDPOINT =
            Pattern.compile("endpoint:\\[([^:\\]]+):(\\d+)");

    private boolean colorEnabled = true;
    private int failures = 0;
    private int warnings = 0;

    public static void main(String[] args) {
        try {
            System.exit(new JmxDiagnostic().run(Options.parse(args)));
        } catch (Options.UsageException e) {
            System.err.println(e.getMessage());
            Options.printUsage(System.err);
            System.exit(2);
        }
    }

    int run(Options o) {
        this.colorEnabled = !o.noColor;

        banner("JMX Connection Diagnostic");
        info("Target JMX URL : " + o.serviceUrl());
        info("Registry host  : " + o.host);
        info("Registry port  : " + o.port);
        info("Auth           : " + (o.user != null ? "username/password (" + o.user + ")" : "none"));
        info("SSL            : " + (o.ssl ? "enabled" : "disabled"));
        info("Per-step timeout: " + o.timeoutMs + " ms");
        System.out.println();

        // Make RMI itself honour a timeout so a black-holed port does not hang
        // the whole tool for the OS default (often 1-3 minutes).
        setRmiTimeouts(o.timeoutMs);
        if (o.ssl) {
            applySslSystemProperties(o);
        }

        InetAddress resolved = step1_dns(o);
        boolean registryTcp = step2_tcp(o.host, o.port, "RMI registry");
        StubEndpoint stub = (registryTcp) ? step3_registryLookup(o) : null;

        if (stub != null) {
            step4_inspectStub(o, stub);
            step5_dataPortTcp(stub);
        }

        boolean connected = step6_jmxConnect(o);

        System.out.println();
        return summary(o, resolved, registryTcp, stub, connected);
    }

    // ------------------------------------------------------------------ steps

    private InetAddress step1_dns(Options o) {
        startStep(1, "Resolve registry host DNS");
        try {
            InetAddress addr = InetAddress.getByName(o.host);
            pass(o.host + " -> " + addr.getHostAddress());
            if (addr.isLoopbackAddress() && !isLiteralLoopback(o.host)) {
                warn("Host resolves to loopback. If the JMX server is remote this will never work; "
                        + "you are pointing at your own machine.");
            }
            return addr;
        } catch (UnknownHostException e) {
            fail("Cannot resolve host '" + o.host + "': " + e.getMessage());
            hint("Check the hostname/Service DNS name. For k8s from outside the cluster use the "
                    + "node IP + a NodePort, a LoadBalancer address, or `kubectl port-forward` to localhost.");
            return null;
        }
    }

    private boolean step2_tcp(String host, int port, String label) {
        startStep(2, "TCP reachability of " + label + " port " + host + ":" + port);
        long t0 = System.nanoTime();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeout());
            pass("TCP connect succeeded in " + millis(t0) + " ms");
            return true;
        } catch (IOException e) {
            fail("TCP connect failed in " + millis(t0) + " ms: " + e.getMessage());
            hint("Nothing is accepting connections here. Common causes: the JVM was not started with "
                    + "`-Dcom.sun.management.jmxremote.port=" + port + "`, a NetworkPolicy/firewall blocks it, "
                    + "or (k8s) the port is not exposed via a Service / port-forward.");
            return false;
        }
    }

    private StubEndpoint step3_registryLookup(Options o) {
        startStep(3, "RMI registry lookup of '" + o.rmiBinding + "'");
        try {
            Registry registry = LocateRegistry.getRegistry(o.host, o.port);
            String[] names = registry.list();
            info("Registry bindings: " + String.join(", ", names));

            boolean found = false;
            for (String n : names) {
                if (n.equals(o.rmiBinding)) { found = true; break; }
            }
            if (!found) {
                warn("Expected binding '" + o.rmiBinding + "' not present. The JMX agent may use a "
                        + "different name, or this registry is not a JMX registry at all.");
            }

            Remote remote = registry.lookup(o.rmiBinding);
            String stubText = remote.toString();
            pass("Looked up stub: " + stubText);

            StubEndpoint ep = StubEndpoint.parse(stubText);
            if (ep == null) {
                warn("Could not parse host:port out of the stub. Continuing with registry host.");
            }
            return ep;
        } catch (Exception e) {
            fail("Registry lookup failed: " + rootCause(e));
            hint("TCP works but the RMI registry handshake does not. If you enabled SSL on the server "
                    + "(`com.sun.management.jmxremote.ssl=true`) you must pass --ssl here too.");
            return null;
        }
    }

    private void step4_inspectStub(Options o, StubEndpoint stub) {
        startStep(4, "Inspect RMI stub advertised endpoint (the k8s gotcha)");
        info("Stub advertises data channel at: " + bold(color(CYAN, stub.host + ":" + stub.port)));

        boolean hostMismatch = !stub.host.equalsIgnoreCase(o.host) && !sameAddress(stub.host, o.host);
        boolean looksInternal = isPrivateOrLoopback(stub.host);

        if (hostMismatch) {
            warn("Stub host (" + stub.host + ") differs from the host you connected to (" + o.host + ").");
        }
        if (looksInternal && hostMismatch) {
            fail("Stub advertises a private/internal address (" + stub.host + ") that is probably the "
                    + "pod's internal IP. Your client will try to open the JMX data channel to that "
                    + "address and time out. THIS IS THE CLASSIC CONTAINER JMX FAILURE.");
            remedy(o, stub);
        } else if (looksInternal) {
            warn("Stub advertises a private/loopback address. Fine only if the client is on the same host/network.");
            remedy(o, stub);
        } else {
            pass("Stub host looks externally reachable.");
        }

        if (stub.port != o.port) {
            warn("RMI data port (" + stub.port + ") differs from the registry port (" + o.port + "). "
                    + "By default this port is random, so firewall/Service rules that only open " + o.port
                    + " will break the data channel.");
            hint("Pin it: start the server with `-Dcom.sun.management.jmxremote.rmi.port=" + o.port
                    + "` so registry and data share one port, then expose only that port.");
        } else {
            pass("RMI data port equals registry port (" + o.port + ") - good, single port to expose.");
        }
    }

    private void step5_dataPortTcp(StubEndpoint stub) {
        startStep(5, "TCP reachability of advertised RMI data port " + stub.host + ":" + stub.port);
        long t0 = System.nanoTime();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(stub.host, stub.port), timeout());
            pass("TCP connect to data channel succeeded in " + millis(t0) + " ms");
        } catch (IOException e) {
            fail("TCP connect to data channel failed in " + millis(t0) + " ms: " + e.getMessage());
            hint("The registry is reachable but the actual JMX data channel (" + stub.host + ":" + stub.port
                    + ") is not. The full connect in the next step will hang/fail for the same reason.");
        }
    }

    private boolean step6_jmxConnect(Options o) {
        startStep(6, "Full JMXConnector handshake" + (o.user != null ? " (with credentials)" : ""));
        Map<String, Object> env = new HashMap<>();
        if (o.user != null) {
            env.put(JMXConnector.CREDENTIALS, new String[]{o.user, o.pass == null ? "" : o.pass});
        }
        if (o.ssl) {
            // Tell the RMI client transport to use SSL sockets.
            env.put("com.sun.jndi.rmi.factory.socket",
                    new javax.rmi.ssl.SslRMIClientSocketFactory());
        }

        long t0 = System.nanoTime();
        JMXServiceURL url;
        try {
            url = new JMXServiceURL(o.serviceUrl());
        } catch (Exception e) {
            fail("Malformed JMX service URL: " + e.getMessage());
            return false;
        }

        try (JMXConnector connector = JMXConnectorFactory.connect(url, env)) {
            MBeanServerConnection mbsc = connector.getMBeanServerConnection();
            int beans = mbsc.getMBeanCount();
            pass("Connected in " + millis(t0) + " ms. MBean count: " + beans);
            step7_sanityQuery(mbsc);
            return true;
        } catch (SecurityException e) {
            fail("Authentication/authorization rejected: " + rootCause(e));
            hint("Credentials were refused. Check jmxremote.password / jmxremote.access files on the server, "
                    + "and that the password file is chmod 600 (the JVM refuses to start otherwise).");
            return false;
        } catch (IOException e) {
            String cause = rootCause(e);
            fail("JMX connect failed in " + millis(t0) + " ms: " + cause);
            if (cause.toLowerCase().contains("ssl") || cause.toLowerCase().contains("handshake")) {
                hint("Looks SSL-related. Server has `com.sun.management.jmxremote.ssl=true`? Then pass --ssl "
                        + "and a trusted certificate (see --trustStore). To confirm plaintext first, set the "
                        + "server to ssl=false temporarily.");
            } else if (cause.toLowerCase().contains("refused")) {
                hint("Connection refused on the data channel - re-read step 4/5: the stub host:port is almost "
                        + "certainly unreachable from here.");
            } else {
                hint("If this hangs rather than fails fast, it is the stub host:port from step 4 being black-holed.");
            }
            return false;
        } catch (Exception e) {
            fail("JMX connect failed: " + rootCause(e));
            return false;
        }
    }

    private void step7_sanityQuery(MBeanServerConnection mbsc) {
        try {
            ObjectName rt = new ObjectName("java.lang:type=Runtime");
            Object vmName = mbsc.getAttribute(rt, "VmName");
            Object vmVer = mbsc.getAttribute(rt, "VmVersion");
            Object uptime = mbsc.getAttribute(rt, "Uptime");
            info("Remote JVM     : " + vmName + " " + vmVer);
            info("Uptime         : " + (((Number) uptime).longValue() / 1000) + " s");

            ObjectName threads = new ObjectName("java.lang:type=Threading");
            Object threadCount = mbsc.getAttribute(threads, "ThreadCount");
            info("Live threads   : " + threadCount);
            pass("MBean sanity query succeeded - JMX is fully functional.");
        } catch (Exception e) {
            warn("Connected, but the sanity MBean query failed: " + rootCause(e));
        }
    }

    // --------------------------------------------------------------- remedies

    private void remedy(Options o, StubEndpoint stub) {
        String externalHost = (o.advertiseAs != null) ? o.advertiseAs : "<externally-reachable-host-or-ip>";
        System.out.println();
        System.out.println(color(YELLOW, "  Suggested server-side JVM flags for a container:"));
        System.out.println("    -Dcom.sun.management.jmxremote=true");
        System.out.println("    -Dcom.sun.management.jmxremote.port=" + o.port);
        System.out.println("    -Dcom.sun.management.jmxremote.rmi.port=" + o.port + "        # pin data port = registry port");
        System.out.println("    -Djava.rmi.server.hostname=" + externalHost + "   # what the stub advertises");
        System.out.println("    -Dcom.sun.management.jmxremote.local.only=false");
        System.out.println("    # (dev only) -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false");
        System.out.println();
        System.out.println(color(YELLOW, "  Then expose ONE port (" + o.port + ") and point this tool at the external host:"));
        System.out.println("    kubectl port-forward pod/<name> " + o.port + ":" + o.port
                + "   # and run this tool against localhost:" + o.port);
        System.out.println();
    }

    // ----------------------------------------------------------------- output

    private int summary(Options o, InetAddress dns, boolean registryTcp,
                        StubEndpoint stub, boolean connected) {
        banner("Summary");
        System.out.println("  DNS resolution ...... " + verdict(dns != null));
        System.out.println("  Registry TCP ........ " + verdict(registryTcp));
        System.out.println("  Registry lookup ..... " + verdict(stub != null || registryTcp));
        System.out.println("  Full JMX connect .... " + verdict(connected));
        System.out.println();

        if (connected) {
            System.out.println(bold(color(GREEN, "  RESULT: JMX connection is healthy.")));
            return 0;
        }

        System.out.println(bold(color(RED, "  RESULT: JMX connection is BROKEN.")));
        System.out.println("  " + failures + " failure(s), " + warnings + " warning(s).");
        System.out.println();
        System.out.println(color(YELLOW, "  Most likely root cause (based on where the walk stopped):"));
        if (dns == null) {
            System.out.println("    -> Hostname does not resolve. Fix DNS / use an IP or port-forward.");
        } else if (!registryTcp) {
            System.out.println("    -> Registry port " + o.port + " is unreachable. JMX not enabled, or port not exposed/allowed.");
        } else if (stub != null && isPrivateOrLoopback(stub.host)
                && !sameAddress(stub.host, o.host)) {
            System.out.println("    -> Stub advertises unreachable internal address " + stub.host
                    + ". Set -Djava.rmi.server.hostname and pin the rmi.port (see step 4).");
        } else {
            System.out.println("    -> Registry works but the data channel/handshake fails. "
                    + "Re-check SSL (--ssl), auth (--user/--pass), and the stub host:port above.");
        }
        return 1;
    }

    // ------------------------------------------------------------- formatting

    private void banner(String s) {
        System.out.println();
        System.out.println(color(BOLD, "=== " + s + " ==="));
    }

    private void startStep(int n, String title) {
        System.out.println();
        System.out.println(color(BOLD, "[Step " + n + "] " + title));
    }

    private void info(String s) { System.out.println("  " + s); }

    private void pass(String s) {
        System.out.println("  " + color(GREEN, "PASS") + " " + s);
    }

    private void warn(String s) {
        warnings++;
        System.out.println("  " + color(YELLOW, "WARN") + " " + s);
    }

    private void fail(String s) {
        failures++;
        System.out.println("  " + color(RED, "FAIL") + " " + s);
    }

    private void hint(String s) {
        System.out.println("       " + color(CYAN, "hint: ") + s);
    }

    private String verdict(boolean ok) {
        return ok ? color(GREEN, "OK") : color(RED, "FAILED");
    }

    private String color(String code, String s) {
        return colorEnabled ? code + s + RESET : s;
    }

    private String bold(String s) {
        return colorEnabled ? BOLD + s + RESET : s;
    }

    // --------------------------------------------------------------- helpers

    private int timeout() { return currentTimeout; }
    private int currentTimeout = 5000;

    private void setRmiTimeouts(int ms) {
        this.currentTimeout = ms;
        // Fail fast instead of hanging on the OS default.
        System.setProperty("sun.rmi.transport.tcp.responseTimeout", String.valueOf(ms));
        System.setProperty("sun.rmi.transport.connectionTimeout", String.valueOf(ms));
        System.setProperty("sun.rmi.transport.tcp.handshakeTimeout", String.valueOf(ms));
        System.setProperty("sun.rmi.transport.proxy.connectTimeout", String.valueOf(ms));
    }

    private void applySslSystemProperties(Options o) {
        if (o.trustStore != null) {
            System.setProperty("javax.net.ssl.trustStore", o.trustStore);
            if (o.trustStorePass != null) {
                System.setProperty("javax.net.ssl.trustStorePassword", o.trustStorePass);
            }
        }
    }

    private static long millis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String msg = c.getMessage();
        return c.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }

    private static boolean isLiteralLoopback(String host) {
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
    }

    private static boolean isPrivateOrLoopback(String host) {
        try {
            InetAddress a = InetAddress.getByName(host);
            return a.isLoopbackAddress() || a.isSiteLocalAddress()
                    || a.isLinkLocalAddress() || a.isAnyLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static boolean sameAddress(String a, String b) {
        try {
            return InetAddress.getByName(a).equals(InetAddress.getByName(b));
        } catch (UnknownHostException e) {
            return false;
        }
    }

    // --------------------------------------------------------- stub endpoint

    private static final class StubEndpoint {
        final String host;
        final int port;

        StubEndpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }

        static StubEndpoint parse(String stubToString) {
            Matcher m = ENDPOINT.matcher(stubToString);
            if (m.find()) {
                return new StubEndpoint(m.group(1), Integer.parseInt(m.group(2)));
            }
            return null;
        }
    }

    // ---------------------------------------------------------------- options

    static final class Options {
        String host;
        int port;
        String url;          // optional full service URL override
        String user;
        String pass;
        boolean ssl;
        String trustStore;
        String trustStorePass;
        String advertiseAs;  // hint only: expected external hostname
        String rmiBinding = "jmxrmi";
        int timeoutMs = 5000;
        boolean noColor = false;

        String serviceUrl() {
            if (url != null) return url;
            return "service:jmx:rmi:///jndi/rmi://" + host + ":" + port + "/" + rmiBinding;
        }

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--host": o.host = req(args, ++i, a); break;
                    case "--port": o.port = Integer.parseInt(req(args, ++i, a)); break;
                    case "--url": o.url = req(args, ++i, a); break;
                    case "--user": o.user = req(args, ++i, a); break;
                    case "--pass": o.pass = req(args, ++i, a); break;
                    case "--ssl": o.ssl = true; break;
                    case "--trustStore": o.trustStore = req(args, ++i, a); break;
                    case "--trustStorePass": o.trustStorePass = req(args, ++i, a); break;
                    case "--advertise-as": o.advertiseAs = req(args, ++i, a); break;
                    case "--binding": o.rmiBinding = req(args, ++i, a); break;
                    case "--timeout": o.timeoutMs = Integer.parseInt(req(args, ++i, a)); break;
                    case "--no-color": o.noColor = true; break;
                    case "-h":
                    case "--help": throw new UsageException("");
                    default:
                        // allow "host:port" positional shorthand
                        if (o.host == null && a.contains(":") && !a.startsWith("--")) {
                            int idx = a.lastIndexOf(':');
                            o.host = a.substring(0, idx);
                            o.port = Integer.parseInt(a.substring(idx + 1));
                        } else {
                            throw new UsageException("Unknown argument: " + a);
                        }
                }
            }
            if (o.url == null) {
                if (o.host == null) throw new UsageException("Missing --host (or host:port, or --url)");
                if (o.port == 0) throw new UsageException("Missing --port");
            } else {
                // Best-effort parse host/port out of the URL for the TCP preflight.
                Matcher m = Pattern.compile("rmi://([^:/]+):(\\d+)").matcher(o.url);
                if (m.find()) {
                    o.host = m.group(1);
                    o.port = Integer.parseInt(m.group(2));
                }
            }
            return o;
        }

        private static String req(String[] args, int i, String flag) {
            if (i >= args.length) throw new UsageException("Missing value for " + flag);
            return args[i];
        }

        static void printUsage(java.io.PrintStream out) {
            out.println();
            out.println("JMX connection diagnostic - troubleshoots remote/containerised JMX.");
            out.println();
            out.println("Usage:");
            out.println("  java -jar jmxdiag.jar --host <h> --port <p> [options]");
            out.println("  java -jar jmxdiag.jar <host:port> [options]");
            out.println("  java -jar jmxdiag.jar --url \"service:jmx:rmi:///jndi/rmi://h:p/jmxrmi\" [options]");
            out.println();
            out.println("Options:");
            out.println("  --host <h>            registry host / k8s Service / node IP");
            out.println("  --port <p>            JMX (RMI registry) port");
            out.println("  --url <u>             full JMXServiceURL (overrides host/port)");
            out.println("  --user <u>            JMX username");
            out.println("  --pass <p>            JMX password");
            out.println("  --ssl                 use SSL for the RMI connection");
            out.println("  --trustStore <path>   truststore for --ssl");
            out.println("  --trustStorePass <p>  truststore password");
            out.println("  --advertise-as <h>    external host you EXPECT the stub to advertise (for remedy output)");
            out.println("  --binding <name>      RMI registry binding name (default: jmxrmi)");
            out.println("  --timeout <ms>        per-step timeout (default: 5000)");
            out.println("  --no-color            disable ANSI colour");
            out.println();
            out.println("Exit codes: 0 = healthy, 1 = JMX broken, 2 = bad usage.");
        }

        static final class UsageException extends RuntimeException {
            UsageException(String m) { super(m); }
        }
    }
}
