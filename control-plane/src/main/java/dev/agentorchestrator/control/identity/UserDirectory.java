package dev.agentorchestrator.control.identity;

import java.util.Optional;

/** Identity provider boundary. Project membership remains in PostgreSQL. */
public interface UserDirectory {
    record User(String sub, String email, boolean active) {}
    Optional<User> findByEmail(String email);
    User createAndInvite(String email);
    void resendInvitation(String email);
}
