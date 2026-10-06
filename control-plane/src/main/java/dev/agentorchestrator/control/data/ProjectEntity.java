package dev.agentorchestrator.control.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project")
public class ProjectEntity {
    @Id public UUID id;
    @Column(name = "organization_id", nullable = false) public UUID organizationId;
    @Column(nullable = false) public String name;
    @Column(nullable = false) public String slug;
    @Column(name = "repository_url") public String repositoryUrl;
    @Column(name = "default_branch") public String defaultBranch;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;

    protected ProjectEntity() {}
    public ProjectEntity(UUID organizationId, String name, String slug) {
        this.id = UUID.randomUUID(); this.organizationId = organizationId;
        this.name = name; this.slug = slug;
        this.createdAt = Instant.now(); this.updatedAt = this.createdAt;
    }
}
