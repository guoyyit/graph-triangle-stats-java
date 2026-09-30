package com.github.guoyyit.graphtrianglestatsjava.wal;

import com.github.guoyyit.graphtrianglestatsjava.domain.EdgeEvent;
import com.github.guoyyit.graphtrianglestatsjava.domain.EventType;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Append-only journal of committed batches used for crash recovery.
 *
 * <p>Each record is framed as: magic (int), version (long), event count
 * (int), then per event a type byte (0=ADD, 1=REMOVE) and two longs,
 * followed by a CRC-32 (long) over all preceding record bytes. A record is
 * committed only after it is forced to disk; on recovery a torn or corrupt
 * tail is truncated so uncommitted records never take effect and committed
 * records are replayed exactly once.
 */
public class WriteAheadLog implements AutoCloseable {

    private static final int MAGIC = 0x47545741;
    private static final int HEADER_BYTES = Integer.BYTES + Long.BYTES + Integer.BYTES;
    private static final int EVENT_BYTES = 1 + Long.BYTES + Long.BYTES;

    private final Path file;
    private final FileChannel channel;

    public WriteAheadLog(Path file) throws IOException {
        this.file = file;
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        this.channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    public synchronized void append(long version, List<EdgeEvent> events) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + events.size() * EVENT_BYTES + Long.BYTES);
        buffer.putInt(MAGIC);
        buffer.putLong(version);
        buffer.putInt(events.size());
        for (EdgeEvent event : events) {
            buffer.put(event.type() == EventType.ADD ? (byte) 0 : (byte) 1);
            buffer.putLong(event.u());
            buffer.putLong(event.v());
        }
        CRC32 crc = new CRC32();
        crc.update(buffer.array(), 0, buffer.position());
        buffer.putLong(crc.getValue());
        buffer.flip();
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        channel.force(true);
    }

    /**
     * Reads all committed batches in write order. Any trailing bytes that do
     * not form a complete, checksum-valid record are truncated from the file.
     */
    public List<CommittedBatch> recover() throws IOException {
        List<CommittedBatch> batches = new ArrayList<>();
        if (!Files.exists(file)) {
            return batches;
        }
        byte[] content = Files.readAllBytes(file);
        long goodLength = 0;
        int offset = 0;
        long lastVersion = 0;
        while (offset < content.length) {
            CommittedBatch batch = parseRecord(content, offset);
            if (batch == null) {
                break;
            }
            if (batch.version() <= lastVersion) {
                break;
            }
            lastVersion = batch.version();
            batches.add(batch);
            offset += recordLength(content, offset);
            goodLength = offset;
        }
        if (goodLength < content.length) {
            channel.truncate(goodLength);
            channel.force(true);
        }
        return batches;
    }

    private static CommittedBatch parseRecord(byte[] content, int offset) {
        int length = recordLength(content, offset);
        if (length < 0 || offset + length > content.length) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(content, offset, length);
        if (buffer.getInt() != MAGIC) {
            return null;
        }
        long version = buffer.getLong();
        int count = buffer.getInt();
        List<EdgeEvent> events = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            EventType type = buffer.get() == 0 ? EventType.ADD : EventType.REMOVE;
            events.add(new EdgeEvent(type, buffer.getLong(), buffer.getLong()));
        }
        long expectedCrc = buffer.getLong();
        CRC32 crc = new CRC32();
        crc.update(content, offset, length - Long.BYTES);
        if (crc.getValue() != expectedCrc) {
            return null;
        }
        return new CommittedBatch(version, events);
    }

    private static int recordLength(byte[] content, int offset) {
        if (offset + HEADER_BYTES > content.length) {
            return -1;
        }
        ByteBuffer header = ByteBuffer.wrap(content, offset, HEADER_BYTES);
        if (header.getInt() != MAGIC) {
            return -1;
        }
        header.getLong();
        int count = header.getInt();
        if (count < 0) {
            return -1;
        }
        long length = (long) HEADER_BYTES + (long) count * EVENT_BYTES + Long.BYTES;
        if (length > Integer.MAX_VALUE) {
            return -1;
        }
        return (int) length;
    }

    @Override
    public synchronized void close() throws IOException {
        channel.close();
    }
}
