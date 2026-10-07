package com.example.mdp;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ODataController {
    private static final Logger LOGGER = LoggerFactory.getLogger(ODataController.class);
    private final ODataGateway gateway;

    public ODataController(ODataGateway gateway) {
        this.gateway = gateway;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/test")
    public Map<String, String> test() {
        return Map.of("status", "authorized");
    }

    @RequestMapping(value = {"/odata", "/odata/**"}, method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<Object> read(HttpServletRequest request, @RequestHeader HttpHeaders headers) {
        String suffix = request.getRequestURI().substring(request.getContextPath().length() + "/odata".length());
        try {
            var response = gateway.read(request.getMethod(), suffix, request.getQueryString(), headers);
            return new ResponseEntity<>(response.getBody(), response.getHeaders(), response.getStatusCode());
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid request or destination URL"));
        } catch (Exception exception) {
            LOGGER.warn("Destination request failed ({})", exception.getClass().getSimpleName());
            return ResponseEntity.status(502).body(Map.of("error", "Backend request failed"));
        }
    }
}