package com.fitraditya.androidwebsocket.util;

/**
 * Created by fitra on 07/06/17.
 */

public class HttpException extends Exception {
    public HttpException() {
        throw new RuntimeException("HTTP exception");
    }

    public HttpException(String message) {
        throw new RuntimeException("HTTP exception: " + message);
    }

    public HttpException(String message, Throwable cause) {
        throw new RuntimeException("HTTP exception: " + message);
    }
}
