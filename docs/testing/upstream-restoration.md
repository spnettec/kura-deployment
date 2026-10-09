# Deployment test restoration

Seven upstream suites run as ordinary JUnit 5 tests under Maven 3.10/JDK 21: 31 core download/install/uninstall options, download callbacks and hook-manager cases; 11 marketplace-descriptor cases; and 19 deployment REST cases. All 61 pass, with no failures, errors or skips.

Expected-exception tests use Jupiter assertions. REST tests provide the Jersey response implementation explicitly, avoiding a missing runtime delegate on the plain Maven test classpath. Download tests use mocked connections and clean up their workers; they do not download from an external endpoint or install a deployment package on the host.

```sh
mvn test
```

A separately committed HTTPS handshake-order fix and its negative regression evidence are described in `https-download-handshake-order.md`. Remaining upstream installer, agent and real-container integration suites remain under audit; this batch is not claimed as complete deployment coverage.
