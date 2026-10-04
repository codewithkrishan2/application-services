package com.kksg.applicationServices.repository.mapper;

import com.kksg.applicationServices.repository.dto.RepositoryResponse;
import com.kksg.applicationServices.repository.dto.RepositoryVisibility;
import com.kksg.applicationServices.repository.dto.ScmResourceProvider;
import com.kksg.applicationServices.repository.service.RepositoryRef;
import com.kksg.applicationServices.scm.common.model.NormalizedRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Repository mapping. */
class RepositoryMapperTest {

    private static final ScmResourceProvider PROVIDER = new ScmResourceProvider("GITHUB", "GitHub");

    private NormalizedRepository repository() {
        return NormalizedRepository.builder()
                .externalId("987654")
                .name("my-service")
                .fullName("acme/my-service")
                .owner("acme")
                .ownerExternalId("1234")
                .ownerAvatarUrl("https://example.invalid/avatar.png")
                .isPrivate(true)
                .defaultBranch("main")
                .description("Service description")
                .cloneUrl("https://example.invalid/acme/my-service.git")
                .webUrl("https://example.invalid/acme/my-service")
                .updatedAt("2026-10-01T10:00:00Z")
                .build();
    }

    @Test
    @DisplayName("maps every field the repository page needs")
    void mapsFields() {
        RepositoryResponse response = RepositoryMapper.toResponse(repository(), PROVIDER);

        assertThat(response.getId()).isEqualTo("987654");
        assertThat(response.getName()).isEqualTo("my-service");
        assertThat(response.getFullName()).isEqualTo("acme/my-service");
        assertThat(response.getDescription()).isEqualTo("Service description");
        assertThat(response.getDefaultBranch()).isEqualTo("main");
        assertThat(response.getVisibility()).isEqualTo(RepositoryVisibility.PRIVATE);
        assertThat(response.getWebUrl()).isEqualTo("https://example.invalid/acme/my-service");
        assertThat(response.getOwner().name()).isEqualTo("acme");
        assertThat(response.getOwner().id()).isEqualTo("1234");
        assertThat(response.getOwner().avatarUrl()).isEqualTo("https://example.invalid/avatar.png");
        assertThat(response.getProvider()).isSameAs(PROVIDER);
        assertThat(response.getUpdatedAt()).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
    }

    @Test
    @DisplayName("a public repository reports PUBLIC and an unreported one reports UNKNOWN")
    void mapsVisibility() {
        NormalizedRepository publicRepo = repository();
        publicRepo.setIsPrivate(false);
        assertThat(RepositoryMapper.toResponse(publicRepo, PROVIDER).getVisibility())
                .isEqualTo(RepositoryVisibility.PUBLIC);

        // Rendering an unreported repository as public would be a misstatement about access control -
        // the one field in this response where guessing is unacceptable.
        NormalizedRepository unknownRepo = repository();
        unknownRepo.setIsPrivate(null);
        assertThat(RepositoryMapper.toResponse(unknownRepo, PROVIDER).getVisibility())
                .isEqualTo(RepositoryVisibility.UNKNOWN);
    }

    @Test
    @DisplayName("reconstructs fullName when the provider omitted it")
    void reconstructsFullName() {
        // fullName is what clients build nested links from, so an absent one would be a dead end.
        NormalizedRepository partial = repository();
        partial.setFullName(null);

        assertThat(RepositoryMapper.toResponse(partial, PROVIDER).getFullName())
                .isEqualTo("acme/my-service");
    }

    @Test
    @DisplayName("omits the owner object when the provider reported nothing about it")
    void omitsEmptyOwner() {
        NormalizedRepository ownerless = repository();
        ownerless.setOwner(null);
        ownerless.setOwnerExternalId(null);
        ownerless.setOwnerAvatarUrl(null);

        assertThat(RepositoryMapper.toResponse(ownerless, PROVIDER).getOwner()).isNull();
    }

    @Test
    @DisplayName("blank provider strings become null rather than empty text")
    void blankStringsBecomeNull() {
        NormalizedRepository blank = repository();
        blank.setDescription("   ");
        blank.setDefaultBranch("");

        RepositoryResponse response = RepositoryMapper.toResponse(blank, PROVIDER);

        // NON_NULL serialisation then drops the keys, so a client gets "absent" rather than "empty",
        // which are different things to render.
        assertThat(response.getDescription()).isNull();
        assertThat(response.getDefaultBranch()).isNull();
    }

    @Test
    @DisplayName("an unparseable timestamp becomes null instead of failing the listing")
    void tolerantTimestampParsing() {
        NormalizedRepository odd = repository();
        odd.setUpdatedAt("yesterday");

        assertThat(RepositoryMapper.toResponse(odd, PROVIDER).getUpdatedAt()).isNull();
    }

    @Test
    @DisplayName("search matches name, full name and description")
    void searchCoversNameOwnerAndDescription() {
        NormalizedRepository repo = repository();

        assertThat(RepositoryMapper.matches(repo, "my-serv")).isTrue();
        assertThat(RepositoryMapper.matches(repo, "acme")).isTrue();
        assertThat(RepositoryMapper.matches(repo, "description")).isTrue();
        assertThat(RepositoryMapper.matches(repo, "unrelated")).isFalse();
    }

    @Test
    @DisplayName("search tolerates a repository with null text fields")
    void searchTolerantOfNulls() {
        NormalizedRepository sparse = NormalizedRepository.builder().externalId("1").build();

        assertThat(RepositoryMapper.matches(sparse, "anything")).isFalse();
    }

    @Test
    @DisplayName("builds a repository reference from the request path")
    void buildsRef() {
        var ref = RepositoryMapper.toRef(new RepositoryRef("acme", "api"), PROVIDER);

        // Built from the path rather than from a fetched repository, so it costs no provider call and
        // cannot disagree with the URL the client used.
        assertThat(ref.owner()).isEqualTo("acme");
        assertThat(ref.name()).isEqualTo("api");
        assertThat(ref.fullName()).isEqualTo("acme/api");
        assertThat(ref.provider()).isSameAs(PROVIDER);
    }
}
