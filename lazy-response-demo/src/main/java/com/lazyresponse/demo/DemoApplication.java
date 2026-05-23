package com.lazyresponse.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Lazy Response Framework mock demo application.
 *
 * <p>Models a realistic Order Details API. All downstream services (order, account, payment,
 * shipment, inventory, loyalty) are mocked in-process with realistic latencies.
 * No external dependencies  -  start and call the endpoint.
 *
 * <h3>Useful endpoints once running:</h3>
 * <ul>
 *   <li>{@code POST /api/orders/detail}  -  the lazy endpoint under test</li>
 *   <li>{@code GET  /lazy/graph}          -  live dependency graph visualisation</li>
 *   <li>{@code GET  /swagger-ui.html}     -  OpenAPI docs with field contract</li>
 * </ul>
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
