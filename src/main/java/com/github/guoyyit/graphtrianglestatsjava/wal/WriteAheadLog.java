package com.github.guoyyit.graphtrianglestatsjava.wal;

import com.github.guoyyit.graphtrianglestatsjava.domain.EdgeEvent;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.FileOutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Durable commit log used for crash recovery.
 *
 * <p>Each committed batch is appended as one framed record:
 * {@code [int frameLength][int crc32][payload]}, where the payload contains
 * {@code [long version][event...]}. A record is considered committed only when
 * the full frame is on disk and fsync-ed; on recovery, replay stops at the
 * first truncated or checksum-mismatching frame, so partially written batches
 * never take effect.
 */
public final class WriteAheadLog implements AutoCloseable {

    private static final int OP_ADD = 0;
    private static final int OP_REMOVE = 1;

    private final Path logFile;
    private final FileOutputStream output;

    public WriteAheadLog(Path directory) {
        try {
            Files.createDirectories(directory);
            this.logFile = directory.resolve("wal.log");
            this.output = new FileOutputStream(logFile.toFile(), true);
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to open write-ahead log in " + directory, exception);
        }
    }

    /**
     * Truncates any torn trailing frames left by a crash mid-write, so stale
     * uncommitted bytes cannot precede later valid commits.
     */
    public static void truncateAfterLastValidRecord(Path directory) {
        Path file = directory.resolve("wal.log");
        if (!Files.isReadable(file)) {
            return;
        }
        long validLength = 0;
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            while (true) {
                int length;
                try {
                    length = input.readInt();
                } catch (EOFException cleanEnd) {
                    return;
                } catch (IOException end) {
                    break;
                }
                if (length < 0 || length > 1 << 28) {
                    break;
                }
                int expectedCrc;
                byte[] payload;
                try {
                    expectedCrc = input.readInt();
                    payload = new byte[length];
                    input.readFully(payload);
                } catch (EOFException torn) {
                    break;
                }
                CRC32 crc = new CRC32();
                crc.update(payload);
                if ((int) crc.getValue() != expectedCrc) {
                    break;
                }
                validLength += 8L + length;
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to scan write-ahead log", exception);
        }
        try {
            long currentSize = Files.size(file);
            if (currentSize > validLength) {
                try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                        file, StandardOpenOption.WRITE)) {
                    channel.truncate(validLength);
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to truncate write-ahead log", exception);
        }
    }

    /**
     * Durably appends one committed batch record.
     */
    public synchronized void append(long version, List<EdgeEvent> events) {
        try {
            byte[] payload = encodePayload(version, events);
            CRC32 crc = new CRC32();
            crc.update(payload);
            ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 8);
            DataOutputStream frameOutput = new DataOutputStream(frame);
            frameOutput.writeInt(payload.length);
            frameOutput.writeInt((int) crc.getValue());
            frameOutput.write(payload);
            output.write(frame.toByteArray());
            output.getFD().sync();
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to append batch version " + version, exception);
        }
    }

    private static byte[] encodePayload(long version, List<EdgeEvent> events) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeLong(version);
        out.writeInt(events.size());
        for (EdgeEvent event : events) {
            out.writeByte(event.operation().name().equals("ADD_EDGE") ? OP_ADD : OP_REMOVE);
            out.writeUTF(event.source());
            out.writeUTF(event.target());
        }
        return bytes.toByteArray();
    }

    /**
     * Reads all valid committed records in order. Stops silently at a torn
     * trailing frame (crash mid-write) or a checksum mismatch.
     */
    public static List<CommittedBatch> recover(Path directory) {
        Path file = directory.resolve("wal.log");
        if (!Files.isReadable(file)) {
            return List.of();
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            List<CommittedBatch> batches = new ArrayList<>();
            while (true) {
                int length;
                try {
                    length = input.readInt();
                } catch (IOException end) {
                    break;
                }
                if (length < 0 || length > 1 << 28) {
                    break;
                }
                int expectedCrc;
                byte[] payload;
                try {
                    expectedCrc = input.readInt();
                    payload = new byte[length];
                    input.readFully(payload);
                } catch (EOFException torn) {
                    break;
                }
                CRC32 crc = new CRC32();
                crc.update(payload);
                if ((int) crc.getValue() != expectedCrc) {
                    break;
                }
                batches.add(decodePayload(payload));
            }
            return batches;
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to recover write-ahead log", exception);
        }
    }

    private static CommittedBatch decodePayload(byte[] payload) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            long version = in.readLong();
            int count = in.readInt();
            List<EdgeEvent> events = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                int operation = in.readByte() & 0xFF;
                String source = in.readUTF();
                String target = in.readUTF();
                events.add(new EdgeEvent(
                        operation == OP_ADD
                                ? com.github.guoyyit.graphtrianglestatsjava.domain.EventOperation.ADD_EDGE
                                : com.github.guoyyit.graphtrianglestatsjava.domain.EventOperation.REMOVE_EDGE,
                        source,
                        target));
            }
            return new CommittedBatch(version, events);
        }
    }

    @Override
    public synchronized void close() {
        try {
            output.close();
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to close write-ahead log", exception);
        }
    }

    /**
     * One committed batch read back from the log.
     */
    public record CommittedBatch(long version, List<EdgeEvent> events) {
    }
}
