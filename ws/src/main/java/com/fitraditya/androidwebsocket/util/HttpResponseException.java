package com.fitraditya.androidwebsocket.util;

/**
 * Created by fitra on 07/06/17.
 */

public class HttpResponseException  extends Exception{
    private int statusCode;

    public HttpResponseException(int statusCode, String s) {
        this.statusCode = statusCode;
        throw new RuntimeException("HTTP status code: " + statusCode + ", " + s);
    }

    public int getStatusCode() {
        return statusCode;
    }
}
