package com.fitraditya.androidwebsocket.util;

import android.os.PowerManager.WakeLock;
import android.util.Log;

import com.fitraditya.androidwebsocket.WebsocketClient;

import java.io.*;
import java.util.Arrays;
import java.util.List;

/**
 * Created by fitra on 07/06/17.
 */

public class HybiParser {
    private static final String TAG = HybiParser.class.getSimpleName();

    private static final int BYTE = 255;
    private static final int FIN = 128;
    private static final int MASK = 128;
    private static final int RSV1 = 64;
    private static final int RSV2 = 32;
    private static final int RSV3 = 16;
    private static final int OPCODE = 15;
    private static final int LENGTH = 127;
    private static final int MODE_TEXT = 1;
    private static final int MODE_BINARY = 2;

    private static final int OP_CONTINUATION = 0;
    private static final int OP_TEXT = 1;
    private static final int OP_BINARY = 2;
    private static final int OP_CLOSE = 8;
    private static final int OP_PING = 9;
    private static final int OP_PONG = 10;

    private static final List<Integer> OPCODES = Arrays.asList(
        OP_CONTINUATION,
        OP_TEXT,
        OP_BINARY,
        OP_CLOSE,
        OP_PING,
        OP_PONG
    );

    private WebsocketClient websocketClient;
    private WakeLock wakeLock;

    private byte[] mask = new byte[0];
    private byte[] payload = new byte[0];

    private boolean isClosed = false;
    private boolean isMasking = true;
    private boolean isMasked;
    private boolean isFinal;
    private int opCode;
    private int lengthSize;
    private int length;
    private int stage;
    private int mode;

    private ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    private static final List<Integer> FRAGMENTED_OPCODES = Arrays.asList(
        OP_CONTINUATION, OP_TEXT, OP_BINARY
    );

    public HybiParser(WebsocketClient websocketClient) {
        this.websocketClient = websocketClient;
    }

    public HybiParser(WebsocketClient websocketClient, WakeLock wakelock) {
        this.websocketClient = websocketClient;
        this.wakeLock = wakelock;
    }

    private static byte[] mask(byte[] payload, byte[] mask, int offset) {
        if (mask.length == 0) {
            return payload;
        }

        for (int i = 0; i < payload.length - offset; i++) {
            payload[offset + i] = (byte) (payload[offset + i] ^ mask[i % 4]);
        }

        return payload;
    }

    public void start(HappyDataInputStream stream) throws IOException {
        while (true) {
            if (stream.available() == -1) {
                break;
            }

            switch (stage) {
                case 0:
                    parseOpcode(stream.readByte());
                    break;
                case 1:
                    parseLength(stream.readByte());
                    break;
                case 2:
                    parseExtendedLength(stream.readBytes(lengthSize));
                    break;
                case 3:
                    mask = stream.readBytes(4);
                    stage = 4;
                    break;
                case 4:
                    payload = stream.readBytes(length);
                    emitFrame();
                    stage = 0;
                    break;
            }

            if (wakeLock != null && isFinal) synchronized (wakeLock) {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
            }
        }

        websocketClient.getListener().onDisconnect(0, "EOF");
    }

    private void parseOpcode(byte data) throws ProtocolError {
        if (wakeLock != null) synchronized (wakeLock) {
            wakeLock.acquire();
        }

        boolean rsv1 = (data & RSV1) == RSV1;
        boolean rsv2 = (data & RSV2) == RSV2;
        boolean rsv3 = (data & RSV3) == RSV3;

        if (rsv1 || rsv2 || rsv3) {
            throw new ProtocolError("RSV is not zero");
        }

        isFinal = (data & FIN) == FIN;
        opCode = (data & OPCODE);
        mask = new byte[0];
        payload = new byte[0];

        if (!OPCODES.contains(opCode)) {
            throw new ProtocolError("Bad opcode");
        }

        if (!FRAGMENTED_OPCODES.contains(opCode) && !isFinal) {
            throw new ProtocolError("Expected non-final packet");
        }

        stage = 1;
    }

    private void parseLength(byte data) {
        isMasked = (data & MASK) == MASK;
        length = (data & LENGTH);

        if (length >= 0 && length <= 125) {
            stage = isMasked ? 3 : 4;
        } else {
            lengthSize = (length == 126) ? 2 : 8;
            stage = 2;
        }
    }

    private void parseExtendedLength(byte[] buffer) throws ProtocolError {
        length = getInteger(buffer);
        stage = isMasked ? 3 : 4;
    }

    public byte[] frame(String data) {
        return frame(data, OP_TEXT, -1);
    }

    public byte[] frame(byte[] data) {
        return frame(data, OP_BINARY, -1);
    }

    private byte[] frame(byte[] data, int opcode, int errorCode)  {
        return frame((Object)data, opcode, errorCode);
    }

    private byte[] frame(String data, int opcode, int errorCode) {
        return frame((Object)data, opcode, errorCode);
    }

    private byte[] frame(Object data, int opcode, int errorCode) {
        if (isClosed) {
            return null;
        }

        Log.d(TAG, "Creating frame for: " + data + " op: " + opcode + " err: " + errorCode);

        byte[] buffer = (data instanceof String) ? decode((String) data) : (byte[]) data;
        int insert = (errorCode > 0) ? 2 : 0;
        int length = buffer.length + insert;
        int header = (length <= 125) ? 2 : (length <= 65535 ? 4 : 10);
        int offset = header + (isMasking ? 4 : 0);
        int masked = isMasking ? MASK : 0;
        byte[] frame = new byte[length + offset];

        frame[0] = (byte) ((byte)FIN | (byte)opcode);

        if (length <= 125) {
            frame[1] = (byte) (masked | length);
        } else if (length <= 65535) {
            frame[1] = (byte) (masked | 126);
            frame[2] = (byte) Math.floor(length / 256);
            frame[3] = (byte) (length & BYTE);
        } else {
            frame[1] = (byte) (masked | 127);
            frame[2] = (byte) (((int) Math.floor(length / Math.pow(2, 56))) & BYTE);
            frame[3] = (byte) (((int) Math.floor(length / Math.pow(2, 48))) & BYTE);
            frame[4] = (byte) (((int) Math.floor(length / Math.pow(2, 40))) & BYTE);
            frame[5] = (byte) (((int) Math.floor(length / Math.pow(2, 32))) & BYTE);
            frame[6] = (byte) (((int) Math.floor(length / Math.pow(2, 24))) & BYTE);
            frame[7] = (byte) (((int) Math.floor(length / Math.pow(2, 16))) & BYTE);
            frame[8] = (byte) (((int) Math.floor(length / Math.pow(2, 8)))  & BYTE);
            frame[9] = (byte) (length & BYTE);
        }

        if (errorCode > 0) {
            frame[offset] = (byte) (((int) Math.floor(errorCode / 256)) & BYTE);
            frame[offset+1] = (byte) (errorCode & BYTE);
        }

        System.arraycopy(buffer, 0, frame, offset + insert, buffer.length);

        if (isMasking) {
            byte[] mask = {
                (byte) Math.floor(Math.random() * 256), (byte) Math.floor(Math.random() * 256),
                (byte) Math.floor(Math.random() * 256), (byte) Math.floor(Math.random() * 256)
            };

            System.arraycopy(mask, 0, frame, header, mask.length);
            mask(frame, mask, offset);
        }

        return frame;
    }

    public void ping(String message) {
        websocketClient.send(frame(message, OP_PING, -1));
    }

    public void close(int code, String reason) {
        if (isClosed) {
            return;
        }

        websocketClient.send(frame(reason, OP_CLOSE, code));
        isClosed = true;
    }

    private void emitFrame() throws IOException {
        byte[] payload = mask(this.payload, mask, 0);
        int opcode = opCode;

        if (opcode == OP_CONTINUATION) {
            if (mode == 0) {
                throw new ProtocolError("Mode was not set.");
            }

            buffer.write(payload);

            if (isFinal) {
                byte[] message = buffer.toByteArray();

                if (mode == MODE_TEXT) {
                    websocketClient.getListener().onMessage(encode(message));
                } else {
                    websocketClient.getListener().onMessage(message);
                }

                reset();
            }
        } else if (opcode == OP_TEXT) {
            if (isFinal) {
                String messageText = encode(payload);
                websocketClient.getListener().onMessage(messageText);
            } else {
                mode = MODE_TEXT;
                buffer.write(payload);
            }
        } else if (opcode == OP_BINARY) {
            if (isFinal) {
                websocketClient.getListener().onMessage(payload);
            } else {
                mode = MODE_BINARY;
                buffer.write(payload);
            }
        } else if (opcode == OP_CLOSE) {
            int code = (payload.length >= 2) ? 256 * payload[0] + payload[1] : 0;
            String reason = (payload.length >  2) ? encode(slice(payload, 2)) : null;
            websocketClient.getListener().onDisconnect(code, reason);
        } else if (opcode == OP_PING) {
            if (payload.length > 125) {
                throw new ProtocolError("Ping payload too large");
            }

            websocketClient.sendFrame(frame(payload, OP_PONG, -1));
        } else if (opcode == OP_PONG) {
            String message = encode(payload);
            Log.d(TAG, "Got pong message: " + message);
        }
    }

    private void reset() {
        mode = 0;
        buffer.reset();
    }

    private String encode(byte[] buffer) {
        try {
            return new String(buffer, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] decode(String string) {
        try {
            return (string).getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private int getInteger(byte[] bytes) throws ProtocolError {
        long i = byteArrayToLong(bytes, 0, bytes.length);

        if (i < 0 || i > Integer.MAX_VALUE) {
            throw new ProtocolError("Bad integer: " + i);
        }

        return (int) i;
    }

    private static byte[] copyOfRange(byte[] original, int start, int end) {
        if (start > end) {
            throw new IllegalArgumentException();
        }

        int originalLength = original.length;

        if (start < 0 || start > originalLength) {
            throw new ArrayIndexOutOfBoundsException();
        }

        int resultLength = end - start;
        int copyLength = Math.min(resultLength, originalLength - start);
        byte[] result = new byte[resultLength];
        System.arraycopy(original, start, result, 0, copyLength);

        return result;
    }

    private byte[] slice(byte[] array, int start) {
        return copyOfRange(array, start, array.length);
    }

    private static long byteArrayToLong(byte[] b, int offset, int length) {
        if (b.length < length) {
            throw new IllegalArgumentException("length must be less than or equal to b.length");
        }

        long value = 0;

        for (int i = 0; i < length; i++) {
            int shift = (length - 1 - i) * 8;
            value += (b[i + offset] & 0x000000FF) << shift;
        }

        return value;
    }

    public static class HappyDataInputStream extends DataInputStream {
        public HappyDataInputStream(InputStream in) {
            super(in);
        }

        public byte[] readBytes(int length) throws IOException {
            byte[] buffer = new byte[length];

            int total = 0;

            while (total < length) {
                int count = read(buffer, total, length - total);

                if (count == -1) {
                    break;
                }

                total += count;
            }

            if (total != length) {
                throw new IOException(String.format("Read wrong number of bytes. Got: %s, Expected: %s.", total, length));
            }

            return buffer;
        }
    }

    public static class ProtocolError extends IOException {
        public ProtocolError(String detailMessage) {
            super(detailMessage);
        }
    }
}
