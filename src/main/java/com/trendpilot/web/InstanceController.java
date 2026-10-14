package com.trendpilot.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.trendpilot.lease.ActiveLease;

/** http://localhost:8080/api/instance：本机是运行还是待命，当前运行的是哪台电脑。 */
@RestController
public class InstanceController {

    private final ActiveLease lease;

    public InstanceController(ActiveLease lease) {
        this.lease = lease;
    }

    @GetMapping("/api/instance")
    public Map<String, Object> instance() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("me", lease.me());
        m.put("role", lease.active() ? "ACTIVE" : "STANDBY");
        m.put("leaseEnabled", lease.enabled());
        m.put("owner", lease.owner().orElse(null));
        m.put("ownerHeartbeat", lease.ownerHeartbeat().map(Object::toString).orElse(null));
        return m;
    }
}
