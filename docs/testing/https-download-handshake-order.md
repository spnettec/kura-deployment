# Configure Kura SSL before the HTTPS handshake

Unlike upstream `6b403dbd`, the fork's downloader called `getResponseCode()` before applying the socket factory from `SslManagerService`. That call can initiate the TLS handshake with the JVM default trust configuration, making a Kura-specific truststore or client certificate ineffective for the first connection.

The downloader now applies its existing SSL/protocol checks before reading the response, including each redirect hop. A protected connection-opening method permits a deterministic connection fixture. Existing redirect behavior, timeouts, virtual-thread execution and hostname verification policy are retained.

The three restored download scenarios pass with JUnit 5. The HTTPS case verifies the order of socket-factory configuration and response access. A negative run restoring the old ordering fails that exact assertion; restoring the fix passes again. Test teardown cancels workers and restores the JVM redirect flag. No external endpoint is contacted.

Maven 3.10/JDK 21 also passes the current deployment batch: 31 core deployment, 11 marketplace-descriptor and 19 REST deployment cases (61 total).
