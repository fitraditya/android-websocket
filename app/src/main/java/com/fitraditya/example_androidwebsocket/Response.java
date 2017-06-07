package com.fitraditya.example_androidwebsocket;

import com.google.gson.Gson;

import java.util.ArrayList;

/**
 * Created by fitra on 07/06/17.
 */

public class Response {
    private String action;
    private ArrayList<String> list;
    private String name;
    private long lastUpdate;

    public Response() {
        //
    }

    public ArrayList<String> getList() {
        return list;
    }

    public void setList(ArrayList<String> list) {
        this.list = list;
    }

    public long getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(long last_update) {
        this.lastUpdate = last_update;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public static Response deserializeList(String json){
        return new Gson().fromJson(json, Response.class);
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
