package dev.agentorchestrator.control.identity;

import dev.agentorchestrator.control.web.ApiProblem;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.DeliveryMediumType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserStatusType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;

@Component
public class CognitoUserDirectory implements UserDirectory, AutoCloseable {
    private final CognitoIdentityProviderClient client;
    private final String poolId;

    public CognitoUserDirectory(@Value("${app.identity.user-pool-id:}") String poolId,
            @Value("${app.identity.region:us-east-1}") String region) {
        this.poolId = poolId;
        this.client = CognitoIdentityProviderClient.builder().region(Region.of(region)).build();
    }

    private void configured() {
        if (poolId.isBlank()) throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "Cognito user pool is not configured");
    }

    private String sub(List<AttributeType> attributes) {
        return attributes.stream().filter(a -> "sub".equals(a.name())).map(AttributeType::value).findFirst()
                .orElseThrow(() -> new ApiProblem(HttpStatus.BAD_GATEWAY, "Cognito did not return a user id"));
    }

    @Override
    public Optional<User> findByEmail(String email) {
        configured();
        try {
            var response = client.adminGetUser(AdminGetUserRequest.builder().userPoolId(poolId).username(email).build());
            return Optional.of(new User(sub(response.userAttributes()), email,
                    response.userStatus() == UserStatusType.CONFIRMED));
        } catch (UserNotFoundException e) {
            return Optional.empty();
        } catch (CognitoIdentityProviderException e) {
            throw new ApiProblem(HttpStatus.BAD_GATEWAY, "Cognito could not look up the user");
        }
    }

    @Override
    public User createAndInvite(String email) {
        configured();
        try {
            var response = client.adminCreateUser(AdminCreateUserRequest.builder().userPoolId(poolId)
                    .username(email).userAttributes(AttributeType.builder().name("email").value(email).build())
                    .desiredDeliveryMediums(DeliveryMediumType.EMAIL).build());
            return new User(sub(response.user().attributes()), email, false);
        } catch (UsernameExistsException e) {
            return findByEmail(email).orElseThrow(() -> new ApiProblem(HttpStatus.CONFLICT, "User already exists"));
        } catch (CognitoIdentityProviderException e) {
            throw new ApiProblem(HttpStatus.BAD_GATEWAY, "Cognito could not create or invite the user");
        }
    }

    @Override
    public void resendInvitation(String email) {
        configured();
        try {
            client.adminCreateUser(AdminCreateUserRequest.builder().userPoolId(poolId).username(email)
                    .messageAction(MessageActionType.RESEND).desiredDeliveryMediums(DeliveryMediumType.EMAIL).build());
        } catch (CognitoIdentityProviderException e) {
            throw new ApiProblem(HttpStatus.BAD_GATEWAY, "Cognito could not resend the invitation");
        }
    }

    @Override
    public void close() { client.close(); }
}
