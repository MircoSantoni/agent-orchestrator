package dev.agentorchestrator.control.web;

import org.springframework.http.HttpStatus;

public class ApiProblem extends RuntimeException {
    private final HttpStatus status;
    public ApiProblem(HttpStatus status, String message) { super(message); this.status = status; }
    public HttpStatus status() { return status; }
    public static ApiProblem badRequest(String message) { return new ApiProblem(HttpStatus.BAD_REQUEST, message); }
    public static ApiProblem unauthorized(String message) { return new ApiProblem(HttpStatus.UNAUTHORIZED, message); }
    public static ApiProblem forbidden(String message) { return new ApiProblem(HttpStatus.FORBIDDEN, message); }
    public static ApiProblem notFound(String message) { return new ApiProblem(HttpStatus.NOT_FOUND, message); }
    public static ApiProblem conflict(String message) { return new ApiProblem(HttpStatus.CONFLICT, message); }
}
