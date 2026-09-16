package io.reladynamo.testkit;

import com.amazonaws.services.dynamodbv2.local.main.ServerRunner;
import com.amazonaws.services.dynamodbv2.local.server.DynamoDBProxyServer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;

/**
 * DynamoDB Local, in-process. No Docker — the target environment has none, and testcontainers would
 * make the differential suite unrunnable there.
 *
 * <p>DynamoDB Local is a JNI wrapper over SQLite, so {@code sqlite4java.library.path} must point at
 * the unpacked native libraries before the server starts. The test-kit POM unpacks them to
 * {@code target/native-libs} and surefire sets the property. Without that the failure is an
 * {@code UnsatisfiedLinkError} that reads like a missing jar.
 *
 * <p>Not thread-safe; one instance per test class. Java 11 baseline.
 */
public final class LocalDynamoDb implements AutoCloseable {

    private final DynamoDBProxyServer server;
    private final DynamoDbClient client;
    private final int port;

    private LocalDynamoDb(DynamoDBProxyServer server, DynamoDbClient client, int port) {
        this.server = server;
        this.client = client;
        this.port = port;
    }

    public static LocalDynamoDb start() {
        if (System.getProperty("sqlite4java.library.path") == null) {
            throw new IllegalStateException(
                    "sqlite4java.library.path is not set. DynamoDB Local needs the unpacked native "
                            + "libraries; the test-kit POM unpacks them to target/native-libs and "
                            + "surefire sets this property. Running outside that setup will fail with "
                            + "UnsatisfiedLinkError.");
        }
        int port = freePort();
        try {
            // -inMemory keeps each run isolated; -sharedDb makes every credential/region hit the same
            // database, so a test does not silently write to a different store than it reads.
            DynamoDBProxyServer server = ServerRunner.createServerFromCommandLineArgs(
                    new String[]{"-inMemory", "-sharedDb", "-port", Integer.toString(port)});
            server.start();
            DynamoDbClient client = DynamoDbClient.builder()
                    .endpointOverride(URI.create("http://127.0.0.1:" + port))
                    .region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("local", "local")))
                    .build();
            return new LocalDynamoDb(server, client, port);
        } catch (Exception e) {
            throw new IllegalStateException("could not start DynamoDB Local on port " + port, e);
        }
    }

    public DynamoDbClient client() {
        return client;
    }

    public int port() {
        return port;
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("no free port for DynamoDB Local", e);
        }
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (RuntimeException ignored) {
            // closing the client must not mask a test failure
        }
        try {
            server.stop();
        } catch (Exception e) {
            throw new IllegalStateException("could not stop DynamoDB Local", e);
        }
    }
}
