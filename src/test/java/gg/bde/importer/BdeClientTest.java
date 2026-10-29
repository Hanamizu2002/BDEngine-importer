package gg.bde.importer;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import static org.junit.jupiter.api.Assertions.*;

class BdeClientTest {
    private static long deadline() { return System.nanoTime() + 1_000_000_000L; }

    @Test void acceptsExactLimitAndRejectsOneMoreByte() throws Exception {
        assertEquals(1024, BdeClient.readLimited(new ByteArrayInputStream(new byte[1024]), 1024, deadline()).length);
        assertThrows(IOException.class, () -> BdeClient.readLimited(new ByteArrayInputStream(new byte[1025]), 1024, deadline()));
    }

    @Test void handlesStreamsWithNoContentLengthAndStopsReadingAtLimit() {
        var input = new ByteArrayInputStream(new byte[1_000_000]);
        assertThrows(IOException.class, () -> BdeClient.readLimited(input, 128, deadline()));
        assertEquals(1_000_000 - 129, input.available());
    }

    @Test void rejectsExpiredOrInterruptedRequests() {
        assertThrows(IOException.class, () -> BdeClient.readLimited(new ByteArrayInputStream(new byte[1]), 128, 0));
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> BdeClient.readLimited(new ByteArrayInputStream(new byte[1]), 128, deadline()));
        } finally { Thread.interrupted(); }
    }

    @Test void rejectsInterruptionDuringFinalRead() {
        InputStream input = new InputStream() {
            @Override public int read() {
                Thread.currentThread().interrupt();
                return -1;
            }
        };
        try {
            assertThrows(IOException.class, () -> BdeClient.readLimited(input, 128, deadline()));
        } finally { Thread.interrupted(); }
    }

    @Test void acceptsEmptyResponseAtStreamBoundary() throws Exception {
        assertArrayEquals(new byte[0], BdeClient.readLimited(new ByteArrayInputStream(new byte[0]), 128, deadline()));
    }
}
