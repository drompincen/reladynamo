package com.reladynamo.demo.petstore;

import com.reladynamo.demo.petstore.demo.DemoPrinter;
import com.reladynamo.demo.petstore.runtime.PetstoreHarness;

/** Prints the six unitemporal demonstrations against an in-memory H2 database. */
public final class PetstoreApp {

    private PetstoreApp() {
    }

    public static void main(String[] args) {
        PetstoreHarness.start();
        DemoPrinter.printAll(System.out);
    }
}
