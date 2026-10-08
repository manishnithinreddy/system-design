package fs;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** A leaf holding bytes. Text goes in and out as UTF-8, so size() is in bytes like `ls -l`. */
public final class File extends Node {
    private byte[] data = new byte[0];

    File(String name, Directory parent, String owner, String group, int mode) {
        super(name, parent, owner, group, mode);
    }

    void write(String text) { data = text.getBytes(StandardCharsets.UTF_8); }

    void append(String text) {
        byte[] more = text.getBytes(StandardCharsets.UTF_8);
        byte[] joined = Arrays.copyOf(data, data.length + more.length);
        System.arraycopy(more, 0, joined, data.length, more.length);
        data = joined;
    }

    String read() { return new String(data, StandardCharsets.UTF_8); }

    @Override long size() { return data.length; }

    @Override char typeChar() { return '-'; }
}
