package dev.agentorchestrator.control.identity;

import dev.agentorchestrator.control.data.Store;
import dev.agentorchestrator.control.web.ApiProblem;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class InvitationService {
    private final Store store;
    private final UserDirectory users;

    public InvitationService(Store store, UserDirectory users) {
        this.store = store;
        this.users = users;
    }

    public Map<String, String> invite(UUID projectId, String rawEmail, String rawDisplayName) {
        store.requireAdmin(projectId);
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        String displayName = rawDisplayName.trim();
        var existing = users.findByEmail(email);
        UserDirectory.User user;
        String result;
        if (existing.isEmpty()) {
            user = users.createAndInvite(email);
            result = user.active() ? "ADDED" : "INVITED";
        } else {
            user = existing.get();
            if (user.active()) {
                result = "ADDED";
            } else {
                users.resendInvitation(email);
                result = "RESENT";
            }
        }
        store.addMember(projectId, user.sub(), displayName, email, user.active() ? "ACTIVE" : "INVITED");
        return Map.of("status", result, "email", email);
    }

    public Map<String, String> resend(UUID projectId, String rawEmail) {
        store.requireAdmin(projectId);
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        if (!store.invitedMemberEmail(projectId, email)) throw ApiProblem.notFound("No pending invitation for this email");
        var user = users.findByEmail(email).orElseThrow(() -> ApiProblem.notFound("Cognito user not found"));
        if (user.active()) {
            store.markMemberEmailActive(projectId, email);
            return Map.of("status", "ACTIVE", "email", email);
        }
        users.resendInvitation(email);
        return Map.of("status", "RESENT", "email", email);
    }
}
