package com.baidu.location;

/** `com/baidu/location/Poi.java:48` — the adapter only ever reads the name of the first entry. */
public class Poi {

    private final String name;

    public Poi(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
