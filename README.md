# jmxdiag — JMX connection troubleshooter

A command-line tool for diagnosing **why you can't connect to JMX on a remote
JVM**, with special attention to the **containerised (Kubernetes / Docker)**
case where the usual symptom is a vague `java.rmi.ConnectException` or a connect
that simply hangs.

Instead of one opaque error, `jmxdiag` walks every layer of a default RMI-based
JMX connection and tells you exactly where it breaks and what to change.

## Why remote JMX is hard (the short version)

A default JMX-over-RMI connection actually uses **two** channels:

1. The **RMI registry** on the port you configured
   (`-Dcom.sun.management.jmxremote.port=<p>`).
2. A **second "data" channel** on a *different, usually random* port. The
   registry hands your client an RMI *stub* that says "now connect to me at
   `host:dataPort`".

In a container, the stub advertises the **pod's internal IP** (e.g.
`10.1.2.3`) and a **random port**. Your client, sitting outside the cluster,
dutifully tries to reach `10.1.2.3:<random>` — and times out. The registry was
reachable; the data channel never is. That is the #1 remote-JMX failure, and
it's invisible in the stock error message.

`jmxdiag` extracts and shows you that advertised `host:port` so the problem
becomes obvious.

## Build

Requires a JDK (11+). No external dependencies.

```bash
# Plain JDK:
./build.sh            # or  build.bat  on Windows
# → produces jmxdiag.jar

# Or with Maven:
mvn -q package        # → target/jmxdiag.jar
```

## Usage

```bash
java -jar jmxdiag.jar --host <host> --port <port> [options]
java -jar jmxdiag.jar myhost:9010
java -jar jmxdiag.jar --url "service:jmx:rmi:///jndi/rmi://host:9010/jmxrmi"
```

| Option | Meaning |
|---|---|
| `--host <h>` | Registry host / k8s Service / node IP |
| `--port <p>` | JMX (RMI registry) port |
| `--url <u>` | Full `JMXServiceURL` (overrides host/port) |
| `--user` / `--pass` | JMX credentials |
| `--ssl` | Use SSL for the RMI connection |
| `--trustStore <path>` / `--trustStorePass` | Truststore for `--ssl` |
| `--advertise-as <h>` | External host you *expect* the stub to advertise (improves remedy output) |
| `--binding <name>` | Registry binding name (default `jmxrmi`) |
| `--timeout <ms>` | Per-step timeout (default 5000) — keeps a black-holed port from hanging |
| `--no-color` | Disable ANSI colour |

Exit codes: `0` healthy, `1` JMX broken, `2` bad usage.

## What it checks

1. **DNS** resolution of the registry host.
2. **TCP** reachability of the registry port.
3. **RMI registry** `list()` / `lookup("jmxrmi")`.
4. **Stub inspection** — the advertised data `host:port`, flagging private/internal
   IPs and random-port mismatches (the k8s gotcha).
5. **TCP** reachability of that advertised data port.
6. **Full JMXConnector** handshake (auth + SSL).
7. **Sanity MBean** query (Runtime / Memory / Threads) to confirm it really works.

When it detects the container stub problem it prints the exact server-side JVM
flags to fix it.

## The fix it will recommend (server side)

Start the remote JVM with a **pinned, single port** and an **externally
reachable advertised hostname**:

```
-Dcom.sun.management.jmxremote=true
-Dcom.sun.management.jmxremote.port=9010
-Dcom.sun.management.jmxremote.rmi.port=9010      # pin data port == registry port
-Djava.rmi.server.hostname=<external-host-or-node-ip>
-Dcom.sun.management.jmxremote.local.only=false
# dev only:
-Dcom.sun.management.jmxremote.authenticate=false
-Dcom.sun.management.jmxremote.ssl=false
```

Then expose **only** port `9010` and point the tool at the external host. For a
quick local test against a pod:

```bash
kubectl port-forward pod/<name> 9010:9010
java -jar jmxdiag.jar localhost:9010
```

> With `rmi.port` pinned equal to `port` and `java.rmi.server.hostname=localhost`,
> `port-forward` works because both channels ride the same forwarded port.

## Example

```
$ java -jar jmxdiag.jar prod-pod.internal:9010

[Step 4] Inspect RMI stub advertised endpoint (the k8s gotcha)
  Stub advertises data channel at: 10.42.3.17:41235
  WARN Stub host (10.42.3.17) differs from the host you connected to (prod-pod.internal).
  FAIL Stub advertises a private/internal address (10.42.3.17) that is probably the
       pod's internal IP. ... THIS IS THE CLASSIC CONTAINER JMX FAILURE.
  ...
  RESULT: JMX connection is BROKEN.
    -> Stub advertises unreachable internal address 10.42.3.17. Set
       -Djava.rmi.server.hostname and pin the rmi.port.
```

## License

Released under the [MIT License](LICENSE).

The software is provided **"AS IS", without warranty of any kind**. It is a
diagnostic utility that connects to and reads management data from systems you
specify; you are responsible for ensuring you are authorized to do so and you
use it entirely **at your own risk**. See the [LICENSE](LICENSE) file for the
full warranty disclaimer and risk-assumption terms.
