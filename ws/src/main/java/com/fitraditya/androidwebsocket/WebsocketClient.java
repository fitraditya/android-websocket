package com.fitraditya.androidwebsocket;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.fitraditya.androidwebsocket.util.HttpException;
import com.fitraditya.androidwebsocket.util.HttpResponseException;
import com.fitraditya.androidwebsocket.util.HttpStatus;
import com.fitraditya.androidwebsocket.util.HybiParser;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

import okhttp3.internal.http.StatusLine;

/**
 * Created by fitra on 07/06/17.
 */

public class WebsocketClient {
    private final Object sendLock = new Object();

    private static TrustManager[] trustManager;

    private URI uri;
    private Socket socket;
    private Thread thread;
    private Handler handler;
    private HandlerThread handlerThread;
    private PowerManager.WakeLock wakeLock;
    private HybiParser hybiParser;
    private WebsocketListener websocketListener;

    private Map<String, String> extras = new HashMap<>();
    private boolean isConnected;

    public interface WebsocketListener {
        public void onConnect();
        public void onMessage(String message);
        public void onMessage(byte[] data);
        public void onDisconnect(int code, String reason);
        public void onError(Exception error);
    }

    public static void setTrustManagers(TrustManager[] tm) {
        trustManager = tm;
    }

    public WebsocketClient(URI uri, WebsocketListener websocketListener, Map<String, String> extras) {
        this.uri = uri;
        this.websocketListener = websocketListener;
        this.extras = extras;

        isConnected = false;
        hybiParser = new HybiParser(this);

        handlerThread = new HandlerThread("ws-thread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    public WebsocketClient(URI uri, WebsocketListener websocketListener, Map<String, String> extras, PowerManager.WakeLock wakelock) {
        this.uri = uri;
        this.websocketListener = websocketListener;
        this.extras = extras;
        this.wakeLock = wakelock;

        isConnected = false;
        hybiParser = new HybiParser(this);

        handlerThread = new HandlerThread("ws-thread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    public void connect() {
        if (thread != null && thread.isAlive()) {
            return;
        }

        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if(wakeLock != null) synchronized (wakeLock) {
                        wakeLock.acquire();
                    }

                    String secret = createSecret();

                    int port = (uri.getPort() != -1) ? uri.getPort() : ((uri.getScheme().equals("wss") || uri.getScheme().equals("https")) ? 443 : 80);
                    String path = TextUtils.isEmpty(uri.getPath()) ? "/" : uri.getPath();

                    if (!TextUtils.isEmpty(uri.getQuery())) {
                        path += "?" + uri.getQuery();
                    }

                    String originScheme = uri.getScheme().equals("wss") ? "https" : "http";
                    URI origin = new URI(originScheme, "//" + uri.getHost(), null);

                    SocketFactory factory = (uri.getScheme().equals("wss") || uri.getScheme().equals("https")) ? getSSLSocketFactory() : SocketFactory.getDefault();
                    socket = factory.createSocket(uri.getHost(), port);

                    PrintWriter out = new PrintWriter(socket.getOutputStream());
                    out.print("GET " + path + " HTTP/1.1\r\n");
                    out.print("Upgrade: websocket\r\n");
                    out.print("Connection: Upgrade\r\n");
                    out.print("Host: " + uri.getHost() + "\r\n");
                    out.print("Origin: " + origin.toString() + "\r\n");
                    out.print("Sec-WebSocket-Key: " + secret + "\r\n");
                    out.print("Sec-WebSocket-Version: 13\r\n");

                    if (extras != null) {
                        for (Map.Entry<String, String> extra : extras.entrySet()) {
                            String key = extra.getKey();
                            String value = extra.getValue();

                            out.print(String.format("%s: %s\r\n", key, value));
                        }
                    }

                    out.print("\r\n");
                    out.flush();

                    HybiParser.HappyDataInputStream stream = new HybiParser.HappyDataInputStream(socket.getInputStream());

                    StatusLine statusLine = parseStatusLine(readLine(stream));

                    if (statusLine == null) {
                        throw new HttpException("Received no reply from server.");
                    } else if (statusLine.code != HttpStatus.SC_SWITCHING_PROTOCOLS) {
                        throw new HttpResponseException(statusLine.code, statusLine.message);
                    }

                    boolean validated = false;
                    Map<String ,String> headers = parseHeaders(stream);

                    if (headers.containsKey("Sec-WebSocket-Accept") == true || headers.containsKey("Sec-Websocket-Accept") == true) {
                        String expected = createSecretValidation(secret);
                        String actual = headers.get("Sec-WebSocket-Accept");

                        if (actual == null) {
                            actual = headers.get("Sec-Websocket-Accept");
                        }

                        if (!expected.equals(actual)) {
                            throw new HttpException("Bad Sec-WebSocket-Accept header value.");
                        }

                        validated = true;
                    }

                    if (!validated) {
                        throw new HttpException("No Sec-WebSocket-Accept header.");
                    }

                    websocketListener.onConnect();
                    isConnected = true;

                    if (wakeLock != null && wakeLock.isHeld()) {
                        wakeLock.release();
                    }

                    hybiParser.start(stream);
                } catch (EOFException ex) {
                    Log.e("WS", "Websocket EOF error:", ex);
                    websocketListener.onDisconnect(0, "EOF");
                    isConnected = false;
                } catch (SSLException ex) {
                    Log.d("WS", "Websocket SSL error:", ex);
                    websocketListener.onDisconnect(0, "SSL");
                    isConnected = false;
                } catch (Exception ex) {
                    websocketListener.onError(ex);
                    isConnected = false;
                } finally {
                    if (wakeLock != null && wakeLock.isHeld()){
                        wakeLock.setReferenceCounted(false);
                        wakeLock.release();
                        wakeLock.setReferenceCounted(true);
                    }
                }
            }
        });

        thread.start();
    }

    public void disconnect() {
        if (socket != null) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        socket.close();
                        socket = null;
                    } catch (IOException ex) {
                        Log.e("WS", "Error while disconnecting:", ex);
                        websocketListener.onError(ex);
                    }
                }
            });
        }
    }

    public void send(String data) {
        sendFrame(hybiParser.frame(data));
    }

    public void send(byte[] data) {
        sendFrame(hybiParser.frame(data));
    }

    public void sendFrame(final byte[] frame) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (sendLock) {
                        if(wakeLock != null) synchronized (wakeLock) {
                            wakeLock.acquire();
                        }

                        if (socket == null) {
                            throw new IllegalStateException("Socket is not connected.");
                        }

                        OutputStream outputStream = socket.getOutputStream();
                        outputStream.write(frame);
                        outputStream.flush();
                    }
                } catch (IOException e) {
                    websocketListener.onError(e);
                }
            }
        });
    }

    private StatusLine parseStatusLine(String line) throws IOException {
        if (TextUtils.isEmpty(line)) {
            return null;
        }

        return StatusLine.parse(line);
    }

    private String readLine(HybiParser.HappyDataInputStream reader) throws IOException {
        int readChar = reader.read();

        if (readChar == -1) {
            return null;
        }

        StringBuilder stringBuilder = new StringBuilder("");

        while (readChar != '\n') {
            if (readChar != '\r') {
                stringBuilder.append((char) readChar);
            }

            readChar = reader.read();

            if (readChar == -1) {
                return null;
            }
        }

        return stringBuilder.toString();
    }

    private Map<String, String> parseHeaders(InputStream stream) throws IOException {
        int charRead;
        StringBuffer stringBuffer = new StringBuffer();

        while (true) {
            stringBuffer.append((char)(charRead = stream.read()));

            if ((char) charRead == '\r') {
                stringBuffer.append((char) stream.read());
                charRead = stream.read();

                if (charRead == '\r') {
                    stringBuffer.append((char) stream.read());
                    break;
                } else {
                    stringBuffer.append((char) charRead);
                }
            }
        }

        String[] headersArray = stringBuffer.toString().split("\r\n");
        Map<String, String> headers = new HashMap<>();

        for (int i = 1; i < headersArray.length - 1; i++) {
            headers.put(headersArray[i].split(": ")[0], headersArray[i].split(": ")[1]);
        }

        return headers;
    }

    private String createSecret() {
        byte[] nonce = new byte[16];

        for (int i = 0; i < 16; i++) {
            nonce[i] = (byte) (Math.random() * 256);
        }

        return Base64.encodeToString(nonce, Base64.DEFAULT).trim();
    }

    private String createSecretValidation(String secret) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-1");
            messageDigest.update((secret + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes());

            return Base64.encodeToString(messageDigest.digest(), Base64.DEFAULT).trim();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private SSLSocketFactory getSSLSocketFactory() throws NoSuchAlgorithmException, KeyManagementException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManager, null);

        return context.getSocketFactory();
    }

    public boolean isConnected() {
        return isConnected;
    }

    public WebsocketListener getListener() {
        return websocketListener;
    }
}
