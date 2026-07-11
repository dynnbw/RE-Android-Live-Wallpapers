package com.reandroid.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Stream reading helpers.
 *
 * <p>Reads a stream fully into a buffer with a read-until-EOF loop. This is preferred over
 * {@code new byte[is.available()]} + a single {@code is.read(buf)}, because {@link
 * InputStream#available()} is not guaranteed to return the total stream length and a single
 * {@code read(byte[])} may return without filling the buffer — both of which silently truncate
 * the data.
 */
public final class IoUtils {
    private IoUtils() {}

    /** Read all remaining bytes from the stream into a newly allocated byte[]. */
    public static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = is.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
